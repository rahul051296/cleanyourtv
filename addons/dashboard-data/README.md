# Dashboard data

The data source for the Dashboard theme's weather and stocks cards (see [the main README](../../README.md)). It has no
tile and nothing to open: the launcher reads it through a read-only content provider, and only when the
theme's `weather_line` names this package.

- **Weather:** now, today's high and low, the next eight hours, sunrise and sunset from
  [Open-Meteo](https://open-meteo.com/) (no account or key), falling back to [wttr.in](https://wttr.in/) when
  Open-Meteo can't be reached. US air quality index from Open-Meteo's air-quality API.
- **Stocks:** price and day change for up to four tickers, from Yahoo Finance chart data. Delayed, for
  information only.
- **When it fetches:** only when Home asks and the last reading is old (weather after 30 minutes, stocks after
  15, retried 5 minutes after a failure). No background service, no alarms, no start at boot.
- **What leaves the TV:** the place's latitude and longitude (yours, or your time zone's main city) go to the weather services; your ticker symbols
  go to Yahoo. Nothing else, and nothing about you or the TV.

## Permissions

`INTERNET` only, to fetch the weather and the stock prices. No background service, no start at boot.

## Setup

It works without setup: the weather is for the main city of the TV's time zone (looked up in a table on
the TV, nothing is sent to find it), in °F in the US and °C elsewhere, and no stocks are fetched. To set your
exact place and some tickers, once over ADB:

```bash
adb shell am start -n io.github.rahul051296.dashboarddata/.SetupActivity \
  --es lat 51.5074 --es lon -0.1278 --es place London --es unit c --es tickers VOO,AAPL,MSFT,KO
```

`place` is the name shown on the card (use `_` for spaces, `New_York`); `unit` is `c` or `f`. Send
`--es tickers ...` on its own later to change the stocks.

## Build

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```
