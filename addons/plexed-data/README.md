# Plexed data

Everything the Plexed theme needs: its hero carousel, its "Who's watching?" profiles and its weather line (see
[the main README](../../README.md)). It has no tile: the launcher reads it through two read-only content
providers, and only when a theme's `recent_hero`, `recent_shelf` or `weather_line` names this package. Its one
screen is the Plex link code.

- **Plex:** after you link your account (a code entered at [plex.tv/link](https://plex.tv/link)), your Plex Home
  profiles from plex.tv; your Plex server, found through plex.tv; and from that server Continue Watching, films and
  shows added or released in the last 30 days that the profile hasn't started, and their artwork.
- **Watch Next:** other apps' continue-watching rows, read from the TV itself once `READ_TV_LISTINGS` is granted.
- **Weather:** now and today's high and low, from [Open-Meteo](https://open-meteo.com/) (no account or key), with
  [wttr.in](https://wttr.in/) as a fallback; the same as Dashboard data's, without the stocks. Refreshed after 30
  minutes.
- **When it fetches:** only when Home asks and the last fetch is over 5 minutes old, or right after a profile
  switch or linking. When the server doesn't answer (the TV in standby, the server off), the lists saved last time
  stay and it tries again 2 minutes later. No background service, no alarms, no start at boot.
- **What leaves the TV:** a latitude and longitude (yours, or your time zone's main city) go to the weather
  services; the link code, then your account's key, go to plex.tv (to read your profiles, switch
  between them and list your servers); that key, or the selected profile's own, goes to your Plex server with each
  request. Nothing else, and nothing about the TV. The keys stay in the add-on's private storage; the launcher only
  sees profile first names, their pictures, titles and artwork.
- **Storage:** the pictures of the items in the carousel, up to about 4 MB for each profile you use; a picture is
  deleted once no profile's list shows it.

## How the server is found

plex.tv lists the servers your account can reach. The add-on tries your own servers before shared ones and, for
each, an address on your home network before one over the internet, and Plex's relay last (for a server that lets
no one in from outside: slow, but enough for titles and pictures). It keeps the first that answers. When that address stops answering (a new one after a router restart), it looks again on the next refresh.

## Permissions

- `INTERNET`, for plex.tv, your Plex server and the weather.
- `READ_TV_LISTINGS`, to read Watch Next. Optional; granted over ADB (see below).
- Plain `http` is allowed (`usesCleartextTraffic`) because a Plex server on your home network answers on it;
  plex.tv and the weather services are always reached over `https`.

## Setup

Install it, push the Plexed theme, then on Home go up to the **Who's watching?** capsule, press OK and choose
**Link Plex account**. Enter the code shown on the TV at [plex.tv/link](https://plex.tv/link), signed in to your
Plex account. Nothing is typed on the TV, and the TV never sees your password.

Optional, once over ADB:

```bash
# Your exact place for the weather (otherwise your time zone's main city), underscores for spaces; unit c or f
adb shell am start -n io.github.rahul051296.plexeddata/.SetupActivity \
  --es lat 51.5074 --es lon -0.1278 --es place London --es unit c

# Other apps' continue-watching rows beside Plex's own
adb shell pm grant io.github.rahul051296.plexeddata android.permission.READ_TV_LISTINGS

# A server address to use instead of the one found (one that lets your home network in without a key);
# an empty value goes back to the found one
adb shell am start -n io.github.rahul051296.plexeddata/.SetupActivity --es plex http://192.168.1.20:32400
```

To unlink, clear the add-on's data (Settings, Apps, Plexed data, Clear data): its keys, saved lists and pictures
go with it.

## Build

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Plex is a trademark of Plex, Inc.; this add-on is not affiliated with Plex.
