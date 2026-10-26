# Slopstream

Your subscriptions, newest first, at the best quality your device can play. One Android app
for phones, tablets and TVs (Android 11+, including the NVIDIA Shield and Google TV).

- Only the channels you follow. No recommendations, no algorithm, no Google account.
- YouTube, Floatplane and Sauce+, each on its own (switch between them; they never mix).
  Floatplane and Sauce+ play what your own membership includes.
- Shorts live on their own screen and never mix into your videos.
- Picks the best stream your hardware decodes (AV1, VP9, HEVC, H.264, HDR), with an
  optional cap at 2160p, 1440p, 1080p or 720p.
- A device check on first start measures decoders, display and connection speed.
- Resume where you left off, watched marks, Watch later and History, picture-in-picture on
  phones, refresh-rate matching on TVs.
- Search your library, or YouTube itself when you ask for it.
- Comments, if you turn them on: loaded only when you open them, never stored.
- Import from NewPipe (subscriptions JSON or database zip) or Google Takeout
  (`subscriptions.csv`). On a TV, the import screen shows an address to open from your
  phone.

## Sync server (optional)

With [Slopstream server](../slopstream-server) running at home, every person in the house
gets one library across their devices: subscriptions and favorites, Watch later, progress and
history, and their own Floatplane and Sauce+ sign-ins (sign in once, on any device). The
server also fetches the video lists all day, so a TV shows a fresh grid the moment it is
switched on, and changes reach an open app within seconds. Connect in Settings, Sync server:
the TV shows a code to type on the server's website. Without a server the app works on its
own, as before.

## Build

Everything runs in Docker:

```
make test         # offline tests
make smoke        # live checks against YouTube
make smoke-floatplane  # read-only report from your Floatplane account (see AGENTS.md)
make build        # build-output/slopstream-debug.apk
make install      # adb install the debug APK
make release      # R8 release APK (signed with keystore.properties)
make keystore     # create a release signing key once
make screenshots  # phone and TV renders in build-output/screenshots/
```

## Install on a TV

Enable developer options and network debugging on the TV, then
`adb connect <tv-ip>:5555` and `make install`. Slopstream appears in the TV's app row.

## Licence

GPL-3.0. YouTube support is built on
[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) by the NewPipe team;
the PoToken code is ported from NewPipe. Not affiliated with YouTube or Google.
