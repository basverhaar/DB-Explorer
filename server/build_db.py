#!/usr/bin/env python3
"""Builds the shared catalogue database that the Android app downloads.

Runs once a day on GitHub Actions. Sources:
  * IMDb non-commercial datasets  - movies & TV (no key)
  * SteamSpy                      - Steam games, reviews, price, owners (no key)
  * RAWG                          - games on every platform, Metascores (RAWG_KEY secret)
  * OMDb                          - posters/plots/Metascores for movies & TV (OMDB_KEY secret, optional)

Usage: build_db.py OUT_DIR [PREVIOUS_DB]
If a source fails, its rows are carried over from PREVIOUS_DB so one outage
never empties a tab. OMDb enrichment is also carried over and grows each day.
"""
import gzip
import json
import os
import re
import sqlite3
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

SCHEMA_VERSION = 1
HERE = os.path.dirname(os.path.abspath(__file__))
with open(os.path.join(HERE, "config.json")) as _f:
    CONFIG = json.load(_f)
RAWG_KEY = os.environ.get("RAWG_KEY", "").strip()
OMDB_KEY = os.environ.get("OMDB_KEY", "").strip()
UA = "DBExplorer-catalogue/1.0 (personal use)"

COLUMNS = [
    "id", "type", "source", "externalId", "title", "searchName", "year", "endYear",
    "genres", "platforms", "runtime", "rating", "votes", "metascore", "developer",
    "publisher", "price", "owners", "imageUrl", "plot", "info", "url", "enrichedAt",
]


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def open_url(url, retries=4, timeout=120):
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            return urllib.request.urlopen(req, timeout=timeout)
        except urllib.error.HTTPError as e:
            if e.code in (401, 403, 404) or attempt == retries - 1:
                raise
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            if attempt == retries - 1:
                raise
        time.sleep(2 ** (attempt + 1))


def get_json(url):
    with open_url(url) as r:
        return json.loads(r.read().decode("utf-8"))


def pipes(items):
    items = [i for i in dict.fromkeys(x.strip() for x in items) if i]
    return "|" + "|".join(items) + "|" if items else ""


def split_pipes(s):
    return [x for x in (s or "").split("|") if x]


def norm(name):
    return re.sub(r"[^0-9a-z]", "", name.lower())


def year_of(text):
    m = re.search(r"(18|19|20)\d{2}", text or "")
    return int(m.group(0)) if m else None


def row(**kw):
    r = {c: None for c in COLUMNS}
    r.update(genres="", platforms="", votes=0, enrichedAt=0)
    r.update(kw)
    r["searchName"] = r["title"].lower()
    return r


def null(v):
    return None if v == "\\N" else v


# ---------------------------------------------------------------- IMDb

def fetch_imdb(min_votes):
    base = "https://datasets.imdbws.com"
    ratings = {}
    log("IMDb: ratings")
    with open_url(f"{base}/title.ratings.tsv.gz") as r, gzip.open(r, "rt", encoding="utf-8") as f:
        next(f)
        for line in f:
            a = line.rstrip("\n").split("\t")
            if len(a) < 3 or not a[2].isdigit():
                continue
            votes = int(a[2])
            if votes >= min_votes:
                ratings[a[0]] = (float(a[1]), votes)
    log(f"IMDb: {len(ratings)} titles with >= {min_votes} votes; reading basics")
    rows = []
    kinds = {"movie": "MOVIE", "tvMovie": "MOVIE", "tvSeries": "TV", "tvMiniSeries": "TV"}
    with open_url(f"{base}/title.basics.tsv.gz") as r, gzip.open(r, "rt", encoding="utf-8") as f:
        next(f)
        for line in f:
            tconst = line[: line.find("\t")]
            rt = ratings.get(tconst)
            if rt is None:
                continue
            # tconst titleType primaryTitle originalTitle isAdult startYear endYear runtimeMinutes genres
            a = line.rstrip("\n").split("\t")
            if len(a) < 9 or a[4] == "1" or a[1] not in kinds:
                continue
            start, end, runtime = null(a[5]), null(a[6]), null(a[7])
            rows.append(row(
                id=f"imdb:{tconst}", type=kinds[a[1]], source="IMDB", externalId=tconst,
                title=a[2],
                year=int(start) if start and start.isdigit() else None,
                endYear=int(end) if end and end.isdigit() else None,
                genres=pipes((null(a[8]) or "").split(",")),
                runtime=int(runtime) if runtime and runtime.isdigit() else None,
                rating=rt[0], votes=rt[1], url=f"https://www.imdb.com/title/{tconst}/",
            ))
    if len(rows) < 1000:
        raise RuntimeError(f"IMDb returned only {len(rows)} titles")
    return rows


# ---------------------------------------------------------------- Steam

STEAM_GENRES = ["Action", "Adventure", "RPG", "Strategy", "Simulation", "Indie", "Casual",
                "Sports", "Racing", "Massively Multiplayer", "Free to Play", "Early Access"]


def to_int(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return 0


def fetch_steam(min_reviews):
    apps = {}

    def read(url, genre):
        data = get_json(url)
        if not isinstance(data, dict):
            return
        for key, a in data.items():
            if not isinstance(a, dict):
                continue
            name = (a.get("name") or "").strip()
            pos, neg = to_int(a.get("positive")), to_int(a.get("negative"))
            if not name or pos + neg < min_reviews:
                continue
            app_id = str(a.get("appid") or key)
            app = apps.setdefault(app_id, {"a": a, "genres": []})
            if genre:
                app["genres"].append("MMO" if genre == "Massively Multiplayer" else genre)

    log("Steam: top games")
    read("https://steamspy.com/api.php?request=all&page=0", None)
    for g in STEAM_GENRES:
        time.sleep(1.2)  # SteamSpy allows about one request per second
        log(f"Steam: {g}")
        try:
            read("https://steamspy.com/api.php?request=genre&genre=" + urllib.parse.quote(g), g)
        except Exception as e:  # one genre failing should not lose the rest
            log(f"Steam: {g} failed: {e}")
    if len(apps) < 100:
        raise RuntimeError(f"SteamSpy returned only {len(apps)} games")
    rows = []
    for app_id, v in apps.items():
        a = v["a"]
        pos, neg = to_int(a.get("positive")), to_int(a.get("negative"))
        price = str(a.get("price") or "")
        rows.append(row(
            id=f"steam:{app_id}", type="GAME", source="STEAM", externalId=app_id,
            title=a["name"].strip(), genres=pipes(v["genres"]), platforms=pipes(["PC"]),
            rating=round(pos * 10.0 / (pos + neg), 2), votes=pos + neg,
            developer=a.get("developer") or None, publisher=a.get("publisher") or None,
            price=int(price) / 100.0 if price.isdigit() else None,
            owners=(a.get("owners") or "").replace(" .. ", " – ") or None,
            imageUrl=f"https://cdn.akamai.steamstatic.com/steam/apps/{app_id}/header.jpg",
            url=f"https://store.steampowered.com/app/{app_id}/",
        ))
    return rows


# ---------------------------------------------------------------- RAWG

def fetch_rawg(popular_pages, top_pages, platform_pages=None):
    games = {}

    def pages(ordering, count, extra=""):
        for page in range(1, count + 1):
            url = (f"https://api.rawg.io/api/games?key={RAWG_KEY}&ordering={ordering}"
                   f"&page_size=40&page={page}{extra}")
            try:
                data = get_json(url)
            except urllib.error.HTTPError as e:
                if e.code == 404 and page > 1:  # past the last page
                    return
                raise
            for g in data.get("results") or []:
                if g.get("id") and g.get("name"):
                    games.setdefault(g["id"], g)
            if page % 25 == 0:
                log(f"RAWG {ordering}: page {page}, {len(games)} games")
            if not data.get("next"):
                return
            time.sleep(0.3)

    pages("-added", popular_pages)
    pages("-metacritic", top_pages, "&metacritic=1,100")
    # Popular games per current console, so e.g. PS5 and Switch are well covered.
    for platform_id, count in (platform_pages or {}).items():
        if platform_id.isdigit():
            pages("-added", count, f"&platforms={platform_id}")
    if not games:
        raise RuntimeError("RAWG returned no games")
    return list(games.values())


def rawg_platforms(g):
    return [p["platform"]["name"] for p in g.get("platforms") or [] if p.get("platform")]


def rawg_genres(g):
    return ["MMO" if x["name"] == "Massively Multiplayer" else x["name"] for x in g.get("genres") or []]


def merge_rawg(games, steam_rows):
    """Adds platforms/Metascores to matching Steam games; returns the RAWG-only games as rows."""
    by_name = {}
    for r in steam_rows:
        # Prefer the most-reviewed Steam app when names collide.
        k = norm(r["title"])
        if k not in by_name or r["votes"] > by_name[k]["votes"]:
            by_name[k] = r
    out, merged = [], 0
    for g in games:
        platforms = rawg_platforms(g)
        esrb = (g.get("esrb_rating") or {}).get("name")
        s = by_name.pop(norm(g["name"]), None)
        if s is not None:
            s["platforms"] = pipes(split_pipes(s["platforms"]) + platforms)
            s["genres"] = pipes(split_pipes(s["genres"]) + rawg_genres(g))
            s["metascore"] = g.get("metacritic") or s["metascore"]
            s["year"] = year_of(g.get("released")) or s["year"]
            if esrb:
                s["info"] = f"ESRB\t{esrb}"
            merged += 1
            continue
        info = [f"ESRB\t{esrb}"] if esrb else []
        if g.get("playtime"):
            info.append(f"Average playtime\t{g['playtime']} hours")
        slug = g.get("slug") or str(g["id"])
        out.append(row(
            id=f"rawg:{g['id']}", type="GAME", source="RAWG", externalId=slug,
            title=g["name"].strip(), year=year_of(g.get("released")), genres=pipes(rawg_genres(g)),
            platforms=pipes(platforms), rating=round(g["rating"] * 2, 2) if g.get("rating") else None,
            votes=to_int(g.get("ratings_count")), metascore=g.get("metacritic"),
            imageUrl=g.get("background_image"), info="\n".join(info) or None,
            url=f"https://rawg.io/games/{slug}",
        ))
    log(f"RAWG: {merged} matched Steam, {len(out)} other games")
    return out


# ---------------------------------------------------------------- OMDb

def enrich_omdb(rows, limit):
    todo = sorted((r for r in rows if r["source"] == "IMDB" and not r["enrichedAt"]),
                  key=lambda r: -r["votes"])[:limit]
    done = 0
    for r in todo:
        try:
            d = get_json(f"https://www.omdbapi.com/?apikey={OMDB_KEY}&i={r['externalId']}&plot=short")
        except urllib.error.HTTPError as e:
            if e.code == 401:  # invalid key or daily limit reached
                break
            continue
        except Exception:
            continue
        r["enrichedAt"] = int(time.time() * 1000)
        if d.get("Response") != "True":
            continue

        def v(k):
            x = d.get(k)
            return x if x and x != "N/A" else None

        info = [(label, v(k)) for label, k in [
            ("Rated", "Rated"), ("Released", "Released"), ("Director", "Director"),
            ("Cast", "Actors"), ("Language", "Language"), ("Country", "Country"), ("Awards", "Awards")]]
        rt = next((x.get("Value") for x in d.get("Ratings") or [] if x.get("Source") == "Rotten Tomatoes"), None)
        if rt:
            info.append(("Rotten Tomatoes", rt))
        r["info"] = "\n".join(f"{a}\t{b}" for a, b in info if b) or None
        r["plot"] = v("Plot")
        r["imageUrl"] = v("Poster")
        ms = v("Metascore")
        r["metascore"] = int(ms) if ms and ms.isdigit() else None
        done += 1
    log(f"OMDb: enriched {done} titles")
    return done


# ---------------------------------------------------------------- main

def load_previous(path):
    if not path or not os.path.exists(path):
        return []
    try:
        con = sqlite3.connect(path)
        con.row_factory = sqlite3.Row
        prev = [dict(r) for r in con.execute("SELECT * FROM titles")]
        con.close()
        log(f"Previous database: {len(prev)} titles")
        return [{c: r.get(c) for c in COLUMNS} for r in prev]
    except Exception as e:
        log(f"Previous database unreadable: {e}")
        return []


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "out"
    prev = load_previous(sys.argv[2] if len(sys.argv) > 2 else None)
    os.makedirs(out_dir, exist_ok=True)
    status = {}

    def previous(*sources):
        return [dict(r) for r in prev if r["source"] in sources]

    def run(name, fn, fallback):
        t = time.time()
        try:
            result = fn()
            status[name] = {"ok": True, "message": f"{len(result)} items", "seconds": int(time.time() - t)}
            return result
        except Exception as e:
            log(f"{name} FAILED: {e!r}; using previous data")
            status[name] = {"ok": False, "message": f"{type(e).__name__}: {e}"[:300]}
            return fallback()

    imdb = run("IMDb", lambda: fetch_imdb(CONFIG["imdb_min_votes"]), lambda: previous("IMDB"))
    steam = run("Steam", lambda: fetch_steam(CONFIG["steam_min_reviews"]), lambda: None)
    if RAWG_KEY:
        rawg_games = run("RAWG", lambda: fetch_rawg(CONFIG["rawg_popular_pages"], CONFIG["rawg_top_pages"],
                                                 CONFIG.get("rawg_platform_pages")),
                         lambda: None)
    else:
        rawg_games = None
        status["RAWG"] = {"ok": False, "message": "No RAWG_KEY secret configured"}

    if steam is not None and rawg_games is not None:
        games = steam + merge_rawg(rawg_games, steam)
    elif steam is not None:
        games = steam + previous("RAWG")
    else:
        # Steam rows from the previous build already carry their merged RAWG data.
        steam_prev = previous("STEAM")
        games = steam_prev + (merge_rawg(rawg_games, steam_prev) if rawg_games is not None else previous("RAWG"))

    # Keep OMDb enrichment from earlier runs so posters accumulate day by day.
    enriched = {r["id"]: r for r in prev if r["source"] == "IMDB" and r.get("enrichedAt")}
    for r in imdb:
        p = enriched.get(r["id"])
        if p and not r.get("enrichedAt"):
            for k in ("imageUrl", "plot", "info", "metascore", "enrichedAt"):
                r[k] = p[k]
    if OMDB_KEY:
        n = enrich_omdb(imdb, CONFIG["omdb_per_run"])
        status["OMDb"] = {"ok": True, "message": f"{n} titles enriched today, "
                                                 f"{sum(1 for r in imdb if r.get('imageUrl'))} with posters"}

    rows = {r["id"]: r for r in imdb + games}
    if len(rows) < 1000:
        raise SystemExit(f"Only {len(rows)} titles in total; not publishing")

    db_path = os.path.join(out_dir, "catalogue.db")
    if os.path.exists(db_path):
        os.remove(db_path)
    con = sqlite3.connect(db_path)
    con.executescript("""
        CREATE TABLE titles(
            id TEXT PRIMARY KEY, type TEXT NOT NULL, source TEXT NOT NULL, externalId TEXT NOT NULL,
            title TEXT NOT NULL, searchName TEXT NOT NULL, year INTEGER, endYear INTEGER,
            genres TEXT NOT NULL, platforms TEXT NOT NULL, runtime INTEGER, rating REAL,
            votes INTEGER NOT NULL, metascore INTEGER, developer TEXT, publisher TEXT, price REAL,
            owners TEXT, imageUrl TEXT, plot TEXT, info TEXT, url TEXT, enrichedAt INTEGER NOT NULL);
        CREATE TABLE facets(kind TEXT NOT NULL, type TEXT NOT NULL, name TEXT NOT NULL,
            count INTEGER NOT NULL, PRIMARY KEY(kind, type, name));
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
    """)
    con.executemany(f"INSERT INTO titles VALUES ({','.join('?' * len(COLUMNS))})",
                    ([r[c] for c in COLUMNS] for r in rows.values()))
    facets = {}
    for r in rows.values():
        for kind, field in (("genre", "genres"), ("platform", "platforms")):
            for name in split_pipes(r[field]):
                key = (kind, r["type"], name)
                facets[key] = facets.get(key, 0) + 1
    con.executemany("INSERT INTO facets VALUES (?,?,?,?)", ((*k, v) for k, v in facets.items()))
    version = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    counts = dict(con.execute("SELECT type, COUNT(*) FROM titles GROUP BY type").fetchall())
    con.executemany("INSERT INTO meta VALUES (?,?)", [
        ("schemaVersion", str(SCHEMA_VERSION)), ("version", version), ("status", json.dumps(status))])
    con.commit()
    con.execute("VACUUM")
    con.close()

    gz_path = db_path + ".gz"
    with open(db_path, "rb") as src, gzip.open(gz_path, "wb", compresslevel=9) as dst:
        while chunk := src.read(1 << 20):
            dst.write(chunk)
    manifest = {
        "schemaVersion": SCHEMA_VERSION, "version": version, "builtAt": int(time.time() * 1000),
        "counts": counts, "sources": status,
        "dbBytes": os.path.getsize(db_path), "gzBytes": os.path.getsize(gz_path),
    }
    with open(os.path.join(out_dir, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2)
    log("Done:", json.dumps(manifest))


if __name__ == "__main__":
    main()
