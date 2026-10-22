# Slopstream

Your subscriptions, newest first, at the best quality your device can play. One Android app
for phones, tablets and TVs (Android 11+, including the NVIDIA Shield and Google TV).

- Only the channels you follow. No recommendations, no algorithm, no account.
- Shorts live on their own screen and never mix into your videos.
- Picks the best stream your hardware decodes (AV1, VP9, HEVC, H.264, HDR), with an
  optional cap at 2160p, 1440p, 1080p or 720p.
- A device check on first start measures decoders, display and connection speed.
- Resume where you left off, watched marks, Watch later, picture-in-picture on phones,
  refresh-rate matching on TVs.
- Import from NewPipe (subscriptions JSON or database zip) or Google Takeout
  (`subscriptions.csv`). On a TV, the import screen shows an address to open from your
  phone.

YouTube is the first service. Floatplane and Sauce+ come next.

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
