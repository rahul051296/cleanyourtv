# CleanYourTV Dashboard

A console-style Home screen for Android TV and Google TV: one row of your favourite apps near the top, a
background picture for each app, and four glanceable cards for the weather, memory, stocks and the calendar.

It is a fork of [CleanYourTV](https://github.com/Gathin23/cleanyourtv), the project that strips the ads and
bloatware from Android TVs and replaces the Home screen with a small, fast, offline launcher. Everything
CleanYourTV does is still here and unchanged; this fork adds the Dashboard on top. CleanYourTV's own guide
(debloating, the launcher, the tools) is in [CLEANYOURTV.md](CLEANYOURTV.md).

![Dashboard on a Sony BRAVIA](themes/dashboard/screenshot.jpg)

## What it adds

| Feature | What you see |
|---|---|
| Console-style row | One row of app banners near the top. Moving right slides the row, so the selected app stays in the first slot |
| Header | Clock and date top left; Inputs, Wi-Fi and Settings in a frosted-glass pill top right |
| Favourites only | Home shows just your favourite apps; everything else is one press away |
| All apps tile | The last tile in the row opens the app library. Hold OK on an app there to add it to Home or remove it |
| Per-app backgrounds | Each favourite has its own soft picture that cross-fades in when it's selected |
| Weather card | Now, today's high and low, the next hours, sunrise, sunset and air quality |
| Memory card | RAM and storage rings, and an **Optimize** button that closes apps idling in the background and says how much memory came back |
| Stocks card | Price and day change for up to four tickers (delayed). Hidden until you add some |
| Calendar card | The month, with today marked and US holidays |
| App opening zoom | The app grows out of its tile when it opens |

## What it needs, and why

- **The launcher** stays offline, as in CleanYourTV: no `INTERNET` permission. It adds one permission,
  `KILL_BACKGROUND_PROCESSES`, used only when you press Optimize. It also names the data add-on in its
  `<queries>` so Android lets it read the add-on's data.
- **The data add-on** ([`addons/dashboard-data`](addons/dashboard-data)) is a separate 13 KB app with
  `INTERNET` and no other permissions. It has no background service, timers or tile: it fetches only when
  Home asks and its reading is old (weather after 30 minutes, stocks after 15). Weather comes from
  [Open-Meteo](https://open-meteo.com/) (no key), with [wttr.in](https://wttr.in/) as a fallback; air
  quality from Open-Meteo; stock prices from Yahoo Finance chart data (delayed). It sends a latitude and
  longitude (yours, or your time zone's main city) to the weather services and your ticker symbols to
  Yahoo, nothing else.
- The launcher APK is about 588 KB, a little above the 576 KB limit CleanYourTV keeps for its own launcher.

## Install

You need the same setup as CleanYourTV: a computer with ADB, and Network or Wireless debugging on the TV
(see [CLEANYOURTV.md](CLEANYOURTV.md) for the full walkthrough, including debloating).

```bash
# 1. Build and install the launcher
cd launcher && ./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk

# 2. Build and install the data add-on
cd ../addons/dashboard-data && ./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk

# 3. Optional: your exact place (underscores for spaces) and up to 4 stock tickers
adb shell am start -n io.github.rahul051296.dashboarddata/.SetupActivity \
  --es lat 40.7128 --es lon -74.0060 --es place New_York --es unit f --es tickers VOO,AAPL,MSFT,KO

# 4. Push the Dashboard theme
cd ../.. && tools/theme.sh <tv ip> themes/dashboard
```

Without step 3 the weather card uses the main city of the TV's time zone (marked "Auto"), in °F in the US
and °C elsewhere, and the stocks card stays hidden until you add tickers. Without the add-on at all, the
weather card says how to get it.

Change the favourites by editing `dock_apps` (and the matching `dock_wallpapers`) in
[`themes/dashboard/config.json`](themes/dashboard/config.json), or from the TV: open All apps and hold OK
on an app. All of CleanYourTV's other themes still work; the Dashboard features stay off unless a theme
turns them on.

## Theme keys

The Dashboard is switched on by these keys in a theme's `config.json`, on top of
[CleanYourTV's own keys](themes/README.md). Each is off, or at its usual size, when a theme leaves it out.

| Key | Value | What it does |
|---|---|---|
| `console_layout` | `0` or `1` | The console-style row, the glass button pill and the four cards |
| `console_row_top` | dp, `0`–`400` | Space above the row of banners |
| `console_button_scale` | percent, `40`–`100` | Size of the buttons in the glass pill |
| `favourites_only` | `0` or `1` | Home holds only `dock_apps` and what you add from All apps |
| `dock_wallpapers` | `{"package": "file"}` | A picture from `wallpapers/` for each app; `_grid` is the All apps picture |
| `weather_line` | package name | The data add-on to read the weather and stocks from |
| `weather_place` | text | A place name to show instead of the add-on's |
| `app_open_zoom` | `0` or `1` | Apps grow out of their tile when they open |
| `hero_compact` | `0` or `1` | The app name and "Last opened" sit closer together |
| `title_scale`, `font_scale` | percent | The big app name, and the clock and date |

## Tested on

- Sony BRAVIA 4K AE2, Google TV 14
- Sony BRAVIA 4K VH21 (X85K), Android TV 12

## Credits

Built on [CleanYourTV](https://github.com/Gathin23/cleanyourtv) by Gathin23 and its contributors (MIT).
The Dashboard theme's photos are public domain; see
[`themes/dashboard/CREDITS.md`](themes/dashboard/CREDITS.md).
