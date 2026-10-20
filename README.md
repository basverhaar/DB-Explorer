# DB Explorer

An Android app for browsing **movies, TV shows and games** in one place, with fast search, filters and sorting. The databases download to your phone and refresh themselves once a day.

## Install

1. On your phone, open the [**latest release**](../../releases/tag/latest) and download `DB-Explorer.apk`.
2. Open the file. If Android asks, allow your browser or file manager to "install unknown apps".
3. Launch **DB Explorer**. The first download starts straight away (on Wi-Fi by default) and takes a few minutes.

Every push to this repository builds a new APK through GitHub Actions. New builds install over the old one and keep your data.

## Features

- **Three tabs:** Movies, TV Shows and Games.
- **Search** by title. Every word you type must appear in the title.
- **Sorting** by popularity, user rating, Metascore, year or title, ascending or descending.
- **Filters:** genres (tap the quick chips or pick several), release year range, minimum user rating, minimum Metascore, minimum votes, and price for games.
- **Detail pages** with poster or artwork, plot, Metascore, user rating, cast or developer, and links to IMDb, Steam, Metacritic, Rotten Tomatoes and YouTube.
- **Daily background refresh** through WorkManager, with a progress notification. You can also refresh by hand in Settings, and choose Wi-Fi-only downloads.

## Data sources

| Source | What it provides | Key needed |
|---|---|---|
| [IMDb datasets](https://developer.imdb.com/non-commercial-datasets/) | Every movie and series above a vote threshold, with ratings, votes, genres and runtime. Refreshed by IMDb every day. | No |
| [SteamSpy](https://steamspy.com/api.php) | The Steam catalogue: reviews, owners, price, developer and genres | No |
| Steam store API | Release dates, descriptions and **Metacritic scores** for the most popular Steam games | No |
| [OMDb](https://www.omdbapi.com/apikey.aspx) | Posters, plots, cast, Rotten Tomatoes scores and **Metascores** for movies and shows | Free key, optional |
| [RAWG](https://rawg.io/apidocs) | **Metacritic's** top-rated games on every platform, including consoles | Free key, optional |

Metacritic has no public API and does not allow scraping. The app therefore gets Metascores through Steam, OMDb and RAWG, which publish them legitimately. Paste the optional keys under **Settings → Optional free API keys**.

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
