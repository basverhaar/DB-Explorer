# DB Explorer

An Android app for browsing **movies, TV shows and games** in one place, with fast search, filters and sorting. The databases download to your phone and refresh themselves once a day.

## Install

1. On your phone, open the [**latest release**](../../releases/tag/latest) and download `DB-Explorer.apk`.
2. Open the file. If Android asks, allow your browser or file manager to "install unknown apps".
3. Launch **DB Explorer**. It downloads the latest database straight away.

Every push to this repository builds a new APK through GitHub Actions. New builds install over the old one and keep your data.

## How it works

```
GitHub Actions (daily, 04:17 UTC)            Phone
┌──────────────────────────────┐   ┌──────────────────────────────┐
│ server/build_db.py           │   │ checks manifest.json twice   │
│  IMDb·Steam·RAWG·Wikidata    │──▶│ a day; downloads the new DB  │
│  → catalogue.db.gz           │   │ only when there is one       │
│  → "data" release            │   │ played/watched marks stay    │
└──────────────────────────────┘   └──────────────────────────────┘
```

The database is built in one place, the [**Build database**](../../actions/workflows/database.yml) workflow, and published to the [`data` release](../../releases/tag/data). The phone never contacts the sources itself. It only downloads the finished file, which is about 6 MB compressed.

### One-time setup: the RAWG key

The workflow reads the RAWG API key from a repository secret:

1. Open **Settings → Secrets and variables → Actions → New repository secret**.
2. Set the name to `RAWG_KEY` and paste your key as the value.
3. Open **Actions → Build database → Run workflow** to build the first database right away.

**Recommended:** add `IGDB_CLIENT_ID` and `IGDB_CLIENT_SECRET`. [IGDB](https://www.igdb.com) (owned by Twitch) covers the newest games, including Switch 2, with an average critic score from professional reviews and cover art:

1. Sign in at [dev.twitch.tv/console](https://dev.twitch.tv/console). Twitch requires two-factor authentication on the account.
2. Choose **Register Your Application**. Use any name, set the OAuth redirect URL to `http://localhost`, choose any category, and set the client type to **Confidential**.
3. Open the app's **Manage** page. Copy the **Client ID**, then click **New Secret**.
4. Add both values as repository secrets.

Optional: add an `OMDB_KEY` secret (free from [omdbapi.com](https://www.omdbapi.com/apikey.aspx)). With it, each daily run adds posters, plots and Metascores for about 900 more movies and shows.

## Features

- **Three tabs:** Movies, TV Shows and Games. You can search by title, and every word you type must appear in it.
- All filters sit behind the **Filters** button, so the list stays clean.
  - **Show results** and **Reset filters** stay pinned at the bottom of the filter screen. The button shows the live number of results.
  - Next to each genre and platform is the number of results you'd get by picking it. Options that would give nothing are greyed out. Long lists are collapsed behind **Show all**.
- The list loads 30 rows at a time as you scroll.
- **Sorting** by popularity, user rating, Metascore, year or title, ascending or descending.
- **Filters:**
  - **Platforms**, grouped by generation with the newest first: Switch 2, PS5, Xbox Series S/X and PC, then Switch, PS4, Xbox One, and so on. A game matches if it is on any of the selected platforms.
  - **Genres**, including **Open World** for games. A title must have every selected genre.
  - **Played / Not played** for games (**Watched / Not watched** for movies and shows).
  - Release year range, minimum user rating, minimum Metascore, minimum votes, and Steam price.
- **Played tracking:** tap the circle on any row, or the button on a detail page, to mark a game as played. The marks stay on your phone and survive database updates.
- **Detail pages** with artwork, platforms, scores, developer, publisher, ESRB rating and links to IMDb, Steam, RAWG, Metacritic, Rotten Tomatoes and YouTube.

## Data sources

| Source | Provides | Key |
|---|---|---|
| [IMDb datasets](https://developer.imdb.com/non-commercial-datasets/) | Movies and series with at least 1,000 votes: ratings, genres, runtime | None |
| [SteamSpy](https://steamspy.com/api.php) | Steam games with at least 200 reviews: review score, price, owners, developer | None |
| [RAWG](https://rawg.io/apidocs) | Popular, top-rated and recently released games on every platform, plus the most popular games per console, including Switch 2 once RAWG lists it | `RAWG_KEY` secret |
| [IGDB](https://api-docs.igdb.com) | Every game reviewed by critics, plus recent and upcoming games and current-console games (Switch 2, PS5, Xbox Series, Switch): **critic score**, platforms, covers, summary, developer | `IGDB_CLIENT_ID` + `IGDB_CLIENT_SECRET` secrets |
| [Wikidata](https://www.wikidata.org) | Current **Metacritic** and **OpenCritic** scores for games, including brand-new releases and Switch 2 games; Metacritic and Rotten Tomatoes scores for movies and shows (matched by IMDb ID) | None |
| [OMDb](https://www.omdbapi.com) | Posters, plots and Metascores for movies and shows | `OMDB_KEY` secret (optional) |

RAWG games are merged with Steam games of the same name, so a Steam game also gets its console platforms and its Metascore. You can tune the thresholds in `server/config.json`.

IMDb data is licensed for personal, non-commercial use only.

## Build it yourself

You need JDK 17 and the Android SDK (API 35).

```
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

The release build is signed with `app/keystore/release.jks`, a key committed for convenience so that updates install cleanly. To sign with your own key, set `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.

## Tech

Kotlin, Jetpack Compose (Material 3), Room with Paging 3, WorkManager, OkHttp, Coil and DataStore.
