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


STEAM_TAG_GENRES = ["Open World"]


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
    # Steam tags that work as genres in the app (SteamSpy lists every game with the tag).
    for tag in STEAM_TAG_GENRES:
        time.sleep(1.2)
        log(f"Steam tag: {tag}")
        try:
            read("https://steamspy.com/api.php?request=tag&tag=" + urllib.parse.quote(tag), tag)
        except Exception as e:
            log(f"Steam tag {tag} failed: {e}")
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

def fetch_rawg(cfg):
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
                log(f"RAWG {ordering}{extra}: page {page}, {len(games)} games")
            if not data.get("next"):
                return
            time.sleep(0.3)

    pages("-added", cfg["rawg_popular_pages"])
    pages("-metacritic", cfg["rawg_top_pages"], "&metacritic=1,100")

    # Games released in the last year (and announced for the next months), most popular first.
    today = time.gmtime()
    start = f"{today.tm_year - 1}-{today.tm_mon:02d}-01"
    end = f"{today.tm_year + 1}-{today.tm_mon:02d}-01"
    pages("-added", cfg["rawg_recent_pages"], f"&dates={start},{end}")

    # Popular games per console, newest consoles included. Platforms are looked up by name
    # so new ones (e.g. Nintendo Switch 2) are picked up as soon as RAWG lists them.
    wanted = cfg.get("rawg_platforms") or {}
    if wanted:
        ids = {}
        url = f"https://api.rawg.io/api/platforms?key={RAWG_KEY}&page_size=50"
        while url:
            data = get_json(url)
            for p in data.get("results") or []:
                ids[norm(p["name"])] = (p["id"], p["name"])
            url = data.get("next")
        log("RAWG platforms: " + ", ".join(sorted(name for _, name in ids.values())))
        for name, count in wanted.items():
            found = ids.get(norm(name))
            if not found:
                log(f"RAWG: platform '{name}' not listed yet")
                continue
            pages("-added", count, f"&platforms={found[0]}")

    if not games:
        raise RuntimeError("RAWG returned no games")
    return list(games.values())


def rawg_platforms(g):
    return [p["platform"]["name"] for p in g.get("platforms") or [] if p.get("platform")]


def rawg_genres(g):
    genres = ["MMO" if x["name"] == "Massively Multiplayer" else x["name"] for x in g.get("genres") or []]
    if any(t.get("slug") == "open-world" for t in g.get("tags") or []):
        genres.append("Open World")
    return genres


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


# ---------------------------------------------------------------- Wikidata

# Review scores (Metacritic, OpenCritic, Rotten Tomatoes) that Wikidata editors copy from
# the review sites, including for brand-new games. CC0 data, no key needed.
WDQS = "https://query.wikidata.org/sparql"
CRITICS = ("Metacritic", "OpenCritic", "Rotten Tomatoes")

# Wikidata platform labels -> the names RAWG uses (anything else is ignored).
WD_PLATFORMS = {
    "Nintendo Switch 2": "Nintendo Switch 2", "PlayStation 5": "PlayStation 5",
    "Xbox Series X and Series S": "Xbox Series S/X", "Xbox Series X/S": "Xbox Series S/X",
    "Microsoft Windows": "PC", "Nintendo Switch": "Nintendo Switch", "PlayStation 4": "PlayStation 4",
    "Xbox One": "Xbox One", "macOS": "macOS", "Linux": "Linux", "Android": "Android", "iOS": "iOS",
    "PlayStation 3": "PlayStation 3", "Xbox 360": "Xbox 360", "Wii U": "Wii U", "Wii": "Wii",
    "Nintendo 3DS": "Nintendo 3DS", "Nintendo DS": "Nintendo DS", "PlayStation Vita": "PS Vita",
    "PlayStation Portable": "PSP", "PlayStation 2": "PlayStation 2", "Xbox": "Xbox",
    "GameCube": "GameCube", "Nintendo GameCube": "GameCube", "Game Boy Advance": "Game Boy Advance",
    "PlayStation": "PlayStation", "Nintendo 64": "Nintendo 64", "Dreamcast": "Dreamcast",
    "Stadia": "Stadia", "Meta Quest": "Meta Quest", "Steam Deck": "PC",
}


def sparql(query):
    body = urllib.parse.urlencode({"query": query}).encode()
    for attempt in range(3):
        try:
            req = urllib.request.Request(WDQS, data=body, headers={
                "User-Agent": UA + " (https://github.com/basverhaar/DB-Explorer)",
                "Accept": "application/sparql-results+json",
                "Content-Type": "application/x-www-form-urlencoded",
            })
            with urllib.request.urlopen(req, timeout=300) as r:
                data = json.loads(r.read().decode("utf-8"))
            return [{k: v["value"] for k, v in b.items()} for b in data["results"]["bindings"]]
        except Exception as e:
            log(f"Wikidata query failed ({e!r}); retrying")
            # 429/503 mean the public endpoint is busy or throttling us: back off generously.
            time.sleep(60 * (attempt + 1))
    raise RuntimeError("Wikidata query kept failing")


def qid(uri):
    return uri.rsplit("/", 1)[-1]


SCORE_QUERY = """
SELECT ?item ?label ?score ?critic ?mcid ?imdb WHERE {
  ?item p:P444 ?st .
  ?st ps:P444 ?score ; pq:P447 ?c .
  ?c rdfs:label ?cl . FILTER(LANG(?cl) = "en" && STR(?cl) IN ("Metacritic", "OpenCritic", "Rotten Tomatoes"))
  BIND(STR(?cl) AS ?critic)
  ?item wdt:P31 wd:%s .
  OPTIONAL { ?item rdfs:label ?label . FILTER(LANG(?label) = "en") }
  OPTIONAL { ?item wdt:P1712 ?mcid }
  %s
}"""


def parse_score(text, critic):
    """'92/100' -> 92; Rotten Tomatoes '93%' -> '93%'. User scores ('8.1/10') are skipped."""
    text = (text or "").strip()
    if critic == "Rotten Tomatoes":
        m = re.search(r"(\d+)\s*%", text)
        return f"{m.group(1)}%" if m else None
    m = re.search(r"(\d+(?:\.\d+)?)\s*/\s*100\b", text)
    if m:
        return round(float(m.group(1)))
    if re.fullmatch(r"\d{1,3}", text) and 0 < int(text) <= 100:
        return int(text)
    return None


CURRENT_CONSOLES = {"Nintendo Switch 2", "PlayStation 5", "Xbox Series S/X", "Nintendo Switch"}


def collect_scores(rows):
    """Groups SPARQL rows by item: best score per critic plus the Metacritic page id."""
    items = {}
    for r in rows:
        it = items.setdefault(qid(r["item"]), {"label": r.get("label"), "scores": {}, "mcid": None,
                                                "imdb": r.get("imdb")})
        v = parse_score(r.get("score"), r["critic"])
        if v is not None:
            old = it["scores"].get(r["critic"])
            # Several scores (per platform); keep the highest.
            if old is None or (isinstance(v, int) and v > old):
                it["scores"][r["critic"]] = v
        if r.get("mcid"):
            it["mcid"] = r["mcid"]
    per_critic = {}
    for v in items.values():
        for c in v["scores"]:
            per_critic[c] = per_critic.get(c, 0) + 1
    log(f"Wikidata: {len(rows)} score rows, usable per critic: {per_critic}")
    return {k: v for k, v in items.items() if v["scores"]}


def fetch_wikidata_games():
    games = collect_scores(sparql(SCORE_QUERY % ("Q7889", "")))
    log(f"Wikidata: {len(games)} games with review scores")
    # Platforms and first release date for scored games plus everything released recently,
    # so new consoles (Switch 2) have their games even before reviews are in.
    since = f"{time.gmtime().tm_year - 2}-01-01T00:00:00Z"
    extra = sparql("""
SELECT ?item ?label ?platform (MIN(?date) AS ?released) WHERE {
  ?item wdt:P31 wd:Q7889 ; wdt:P400 ?p .
  ?p rdfs:label ?platform . FILTER(LANG(?platform) = "en")
  OPTIONAL { ?item wdt:P577 ?date }
  OPTIONAL { ?item rdfs:label ?label . FILTER(LANG(?label) = "en") }
  { ?item p:P444/pq:P447 ?c . ?c rdfs:label ?cl . FILTER(LANG(?cl) = "en" && STR(?cl) IN ("Metacritic", "OpenCritic")) }
  UNION
  { ?item wdt:P577 ?d . FILTER(?d >= "%s"^^xsd:dateTime) }
} GROUP BY ?item ?label ?platform""" % since)
    for r in extra:
        g = games.setdefault(qid(r["item"]), {"label": r.get("label"), "scores": {}, "mcid": None, "imdb": None})
        name = WD_PLATFORMS.get(r["platform"])
        if name:
            g.setdefault("platforms", set()).add(name)
        y = year_of(r.get("released"))
        if y and (not g.get("year") or y < g["year"]):
            g["year"] = y
    # Keep scored games plus recent games on current consoles; drop unlabeled items.
    games = {k: g for k, g in games.items()
             if g.get("label") and (g["scores"] or g.get("platforms", set()) & CURRENT_CONSOLES)}
    log(f"Wikidata: {len(games)} games after adding platforms/release dates")
    return games


FILM_QUERY = """
SELECT ?item ?score ?imdb ?mcid WHERE {
  ?item p:P444 ?st .
  ?st pq:P447 wd:%s ; ps:P444 ?score .
  ?item wdt:P345 ?imdb .
  OPTIONAL { ?item wdt:P1712 ?mcid }
}"""


# Wikidata items for the review sites (used as the "review score by" qualifier).
FILM_CRITICS = {"Metacritic": "Q150248", "Rotten Tomatoes": "Q105584"}


def fetch_wikidata_films():
    """Metacritic and Rotten Tomatoes scores for movies/series, matched by IMDb id.
    One light query per review site keeps each request under the endpoint's time limit."""
    by_imdb = {}
    for critic, item in FILM_CRITICS.items():
        rows = sparql(FILM_QUERY % item)
        for r in rows:
            r["critic"] = critic
        for k, v in collect_scores(rows).items():
            f = by_imdb.setdefault(v["imdb"], {"scores": {}, "mcid": None, "imdb": v["imdb"]})
            f["scores"].update(v["scores"])
            f["mcid"] = f["mcid"] or v["mcid"]
        time.sleep(5)
    if not by_imdb:
        raise RuntimeError("Wikidata returned no film scores")
    log(f"Wikidata: {len(by_imdb)} movies/series with review scores")
    return by_imdb


def set_info(r, key, value):
    lines = [l for l in (r.get("info") or "").split("\n") if l and not l.startswith(key + "\t")]
    if value:
        lines.append(f"{key}\t{value}")
    r["info"] = "\n".join(lines) or None


def carry_over_scores(rows, prev):
    """When Wikidata is unreachable, keep yesterday's scores instead of dropping them."""
    old = {r["id"]: r for r in prev}
    n = 0
    for r in rows:
        p = old.get(r["id"])
        if not p:
            continue
        if r.get("metascore") is None and p.get("metascore") is not None:
            r["metascore"] = p["metascore"]
            n += 1
        for line in (p.get("info") or "").split("\n"):
            key, _, value = line.partition("\t")
            if value and f"\n{key}\t" not in "\n" + (r.get("info") or ""):
                set_info(r, key, value)
    log(f"Kept {n} review scores from the previous build")


def apply_film_scores(imdb_rows, by_imdb):
    n = 0
    for r in imdb_rows:
        f = by_imdb.get(r["externalId"])
        if not f:
            continue
        mc = f["scores"].get("Metacritic")
        if isinstance(mc, int):
            r["metascore"] = mc
        if f["scores"].get("Rotten Tomatoes"):
            set_info(r, "Rotten Tomatoes", f["scores"]["Rotten Tomatoes"])
        if f.get("mcid"):
            set_info(r, "_metacritic", f"https://www.metacritic.com/{f['mcid'].strip('/')}/")
        n += 1
    log(f"Wikidata: scores added to {n} movies/series")
    return n


def apply_game_scores(game_rows, wd_games):
    """Adds Wikidata scores/platforms to matching games; returns rows for games we did not have."""
    by_name = {}
    for k, g in wd_games.items():
        by_name.setdefault(norm(g["label"]), []).append((k, g))
    used, matched = set(), 0
    for r in game_rows:
        cands = by_name.get(norm(r["title"]))
        if not cands:
            continue
        # Same-named games (remakes) are told apart by year; without a year only a unique name matches.
        if r.get("year"):
            pick = [c for c in cands if c[1].get("year") and abs(c[1]["year"] - r["year"]) <= 1]
        else:
            pick = cands if len(cands) == 1 else []
        if not pick:
            continue
        k, g = pick[0]
        used.add(k)
        matched += 1
        apply_game(r, g)
    out = []
    for k, g in wd_games.items():
        if k in used:
            continue
        r = row(id=f"wd:{k}", type="GAME", source="WIKIDATA", externalId=k, title=g["label"],
                year=g.get("year"), url=f"https://www.wikidata.org/wiki/{k}")
        apply_game(r, g)
        out.append(r)
    log(f"Wikidata: {matched} games matched, {len(out)} added")
    return out


def apply_game(r, g):
    mc, oc = g["scores"].get("Metacritic"), g["scores"].get("OpenCritic")
    if isinstance(mc, int):
        r["metascore"] = mc
        set_info(r, "_critic", None)
    elif r.get("metascore") is None and isinstance(oc, int):
        r["metascore"] = oc
        set_info(r, "_critic", "OpenCritic")
    if isinstance(oc, int):
        set_info(r, "OpenCritic", f"{oc}/100")
    if g.get("platforms"):
        r["platforms"] = pipes(split_pipes(r["platforms"]) + sorted(g["platforms"]))
    if not r.get("year") and g.get("year"):
        r["year"] = g["year"]
    if g.get("mcid"):
        set_info(r, "_metacritic", f"https://www.metacritic.com/{g['mcid'].strip('/')}/")


# ---------------------------------------------------------------- IGDB

# IGDB (Twitch) - every game incl. brand-new consoles like Switch 2, with an aggregated
# critic score from professional reviews. Free: needs a Twitch app's client id + secret.
IGDB_ID = os.environ.get("IGDB_CLIENT_ID", "").strip()
IGDB_SECRET = os.environ.get("IGDB_CLIENT_SECRET", "").strip()

IGDB_PLATFORMS = {
    "Nintendo Switch 2": "Nintendo Switch 2", "PlayStation 5": "PlayStation 5",
    "Xbox Series X|S": "Xbox Series S/X", "PC (Microsoft Windows)": "PC",
    "Nintendo Switch": "Nintendo Switch", "PlayStation 4": "PlayStation 4", "Xbox One": "Xbox One",
    "Wii U": "Wii U", "Nintendo 3DS": "Nintendo 3DS", "PlayStation Vita": "PS Vita",
    "PlayStation 3": "PlayStation 3", "Xbox 360": "Xbox 360", "Wii": "Wii", "Nintendo DS": "Nintendo DS",
    "PlayStation Portable": "PSP", "PlayStation 2": "PlayStation 2", "Xbox": "Xbox",
    "Nintendo GameCube": "GameCube", "Game Boy Advance": "Game Boy Advance", "Dreamcast": "Dreamcast",
    "PlayStation": "PlayStation", "Nintendo 64": "Nintendo 64", "Mac": "macOS", "Linux": "Linux",
    "iOS": "iOS", "Android": "Android", "Meta Quest 3": "Meta Quest", "Meta Quest 2": "Meta Quest",
    "Google Stadia": "Stadia", "Super Nintendo Entertainment System": "SNES",
    "Nintendo Entertainment System": "NES", "Sega Mega Drive/Genesis": "Genesis",
}

IGDB_FIELDS = ("fields name,slug,url,first_release_date,aggregated_rating,aggregated_rating_count,"
               "rating,rating_count,total_rating_count,hypes,summary,platforms.name,genres.name,themes.name,"
               "cover.image_id,involved_companies.company.name,involved_companies.developer,"
               "involved_companies.publisher;")


def igdb_token():
    url = ("https://id.twitch.tv/oauth2/token?" + urllib.parse.urlencode(
        {"client_id": IGDB_ID, "client_secret": IGDB_SECRET, "grant_type": "client_credentials"}))
    req = urllib.request.Request(url, data=b"", headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read())["access_token"]


def igdb_query(token, endpoint, body):
    for attempt in range(4):
        req = urllib.request.Request(f"https://api.igdb.com/v4/{endpoint}", data=body.encode(), headers={
            "Client-ID": IGDB_ID, "Authorization": f"Bearer {token}", "Accept": "application/json",
            "User-Agent": UA})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                time.sleep(0.3)  # IGDB allows 4 requests per second
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt == 3:
                raise
            time.sleep(2 ** attempt)


def fetch_igdb(cfg):
    token = igdb_token()
    games = {}

    def collect(where, sort, pages):
        for page in range(pages):
            batch = igdb_query(token, "games", f"{IGDB_FIELDS} where {where}; sort {sort}; "
                                               f"limit 500; offset {page * 500};")
            for g in batch:
                if g.get("name"):
                    games.setdefault(g["id"], g)
            if len(batch) < 500:
                break
        log(f"IGDB: {len(games)} games after '{where[:60]}'")

    base = "parent_game = null & version_parent = null"
    # 1. Every game that professional critics reviewed.
    collect(f"{base} & aggregated_rating_count > 0", "aggregated_rating_count desc", cfg.get("igdb_scored_pages", 60))
    # 2. Released in the last year or coming in the next one, most anticipated/rated first.
    now = int(time.time())
    collect(f"{base} & first_release_date >= {now - 365 * 86400} & first_release_date <= {now + 365 * 86400}"
            f" & (hypes > 2 | total_rating_count > 2)", "hypes desc", cfg.get("igdb_recent_pages", 10))
    # 3. Popular games on current consoles (platform ids looked up by name).
    plats = igdb_query(token, "platforms", "fields id,name; limit 500;")
    consoles = [p for p in plats if IGDB_PLATFORMS.get(p.get("name")) in
                ("Nintendo Switch 2", "PlayStation 5", "Xbox Series S/X", "Nintendo Switch")]
    log("IGDB current consoles: " + ", ".join(f"{p['name']} ({p['id']})" for p in consoles))
    unknown = sorted(p["name"] for p in plats if p.get("name") and p["name"] not in IGDB_PLATFORMS
                     and re.search(r"switch|playstation|xbox", p["name"], re.I))
    if unknown:
        log("IGDB console platforms without a mapping: " + ", ".join(unknown))
    ids = [str(p["id"]) for p in consoles]
    if ids:
        collect(f"{base} & platforms = ({','.join(ids)}) & (total_rating_count > 0 | hypes > 0)",
                "total_rating_count desc", cfg.get("igdb_console_pages", 20))
    if not games:
        raise RuntimeError("IGDB returned no games")
    return list(games.values())


def igdb_row(g):
    companies = g.get("involved_companies") or []
    dev = [c["company"]["name"] for c in companies if c.get("developer") and c.get("company")]
    pub = [c["company"]["name"] for c in companies if c.get("publisher") and c.get("company")]
    ts = g.get("first_release_date")
    cover = (g.get("cover") or {}).get("image_id")
    return {
        "title": g["name"].strip(),
        "year": time.gmtime(ts).tm_year if ts else None,
        "platforms": [IGDB_PLATFORMS[p["name"]] for p in g.get("platforms") or [] if p.get("name") in IGDB_PLATFORMS],
        "genres": [x["name"] for x in g.get("genres") or [] if x.get("name")] +
                  ["Open World" for t in g.get("themes") or [] if (t.get("name") or "").lower() == "open world"],
        "critic": round(g["aggregated_rating"]) if g.get("aggregated_rating") else None,
        "critic_count": g.get("aggregated_rating_count") or 0,
        "user": round(g["rating"] / 10, 2) if g.get("rating") else None,
        "user_count": g.get("rating_count") or 0,
        "image": f"https://images.igdb.com/igdb/image/upload/t_cover_big/{cover}.jpg" if cover else None,
        "summary": g.get("summary"),
        "developer": ", ".join(dev[:2]) or None,
        "publisher": ", ".join(pub[:2]) or None,
        "url": g.get("url") or f"https://www.igdb.com/games/{g.get('slug')}",
        "slug": g.get("slug") or str(g["id"]),
        "id": g["id"],
    }


IGDB_GENRES = {"Role-playing (RPG)": "RPG", "Hack and slash/Beat 'em up": "Action",
               "Turn-based strategy (TBS)": "Strategy", "Real Time Strategy (RTS)": "Strategy",
               "Point-and-click": "Adventure", "Shooter": "Shooter", "Platform": "Platformer"}


def apply_igdb(game_rows, igdb_games):
    """Adds critic scores/platforms to matching games; returns rows for games we did not have."""
    by_name = {}
    for r in game_rows:
        by_name.setdefault(norm(r["title"]), []).append(r)
    matched, out = 0, []
    for g in map(igdb_row, igdb_games):
        cands = by_name.get(norm(g["title"])) or []
        target = None
        if g["year"]:
            target = next((r for r in cands if r.get("year") and abs(r["year"] - g["year"]) <= 1), None)
        if target is None and len(cands) == 1 and not cands[0].get("year"):
            target = cands[0]
        genres = [IGDB_GENRES.get(x, x) for x in g["genres"]]
        if target is None:
            target = row(id=f"igdb:{g['id']}", type="GAME", source="IGDB", externalId=g["slug"],
                         title=g["title"], year=g["year"], genres=pipes(genres),
                         rating=g["user"], votes=g["user_count"], developer=g["developer"],
                         publisher=g["publisher"], imageUrl=g["image"], plot=g["summary"], url=g["url"])
            out.append(target)
            by_name.setdefault(norm(g["title"]), []).append(target)
        else:
            matched += 1
            target["year"] = target.get("year") or g["year"]
            target["plot"] = target.get("plot") or g["summary"]
            target["imageUrl"] = target.get("imageUrl") or g["image"]
            target["developer"] = target.get("developer") or g["developer"]
            target["publisher"] = target.get("publisher") or g["publisher"]
        target["platforms"] = pipes(split_pipes(target["platforms"]) + g["platforms"])
        if g["critic"] is not None:
            set_info(target, "Critic score (IGDB)", f"{g['critic']}/100 from {g['critic_count']} reviews")
            # Metacritic stays the headline score when we have it; otherwise use the critic average.
            if target.get("metascore") is None and g["critic_count"] >= CONFIG.get("igdb_min_reviews", 3):
                target["metascore"] = g["critic"]
                set_info(target, "_critic", "Critics (IGDB)")
    log(f"IGDB: {matched} games matched, {len(out)} added")
    return out


# ---------------------------------------------------------------- OMDb

def merge_omdb(r, poster, plot, info, metascore):
    """Adds OMDb fields without overwriting scores and info that came from Wikidata."""
    r["imageUrl"] = poster or r.get("imageUrl")
    r["plot"] = plot or r.get("plot")
    if r.get("metascore") is None:
        r["metascore"] = metascore
    for line in (info or "").split("\n"):
        key, _, value = line.partition("\t")
        if value and f"\n{key}\t" not in "\n" + (r.get("info") or ""):
            set_info(r, key, value)


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
        ms = v("Metascore")
        merge_omdb(r, v("Poster"), v("Plot"), "\n".join(f"{a}\t{b}" for a, b in info if b),
                   int(ms) if ms and ms.isdigit() else None)
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
        rawg_games = run("RAWG", lambda: fetch_rawg(CONFIG),
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

    if IGDB_ID and IGDB_SECRET:
        igdb_games = run("IGDB", lambda: fetch_igdb(CONFIG), lambda: None)
        if igdb_games is not None:
            games = [r for r in games if r["source"] != "IGDB"]
            games += apply_igdb(games, igdb_games)
        else:
            games += previous("IGDB")
    else:
        status["IGDB"] = {"ok": False, "message": "No IGDB_CLIENT_ID / IGDB_CLIENT_SECRET secrets configured"}

    if CONFIG.get("wikidata", True):
        wd_games = run("Wikidata games", fetch_wikidata_games, lambda: None)
        if wd_games is not None:
            games = [r for r in games if r["source"] != "WIKIDATA"]
            games += apply_game_scores(games, wd_games)
        elif not any(r["source"] == "WIKIDATA" for r in games):
            games += previous("WIKIDATA")
        films = run("Wikidata movies", fetch_wikidata_films, lambda: None)
        if films is not None:
            apply_film_scores(imdb, films)
        if wd_games is None or films is None:
            carry_over_scores(imdb + games, prev)

    # Keep OMDb enrichment from earlier runs so posters accumulate day by day.
    enriched = {r["id"]: r for r in prev if r["source"] == "IMDB" and r.get("enrichedAt")}
    for r in imdb:
        p = enriched.get(r["id"])
        if p and not r.get("enrichedAt"):
            merge_omdb(r, p.get("imageUrl"), p.get("plot"), p.get("info"), p.get("metascore"))
            r["enrichedAt"] = p["enrichedAt"]
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
