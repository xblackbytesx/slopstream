# Slopstream

Your subscriptions, newest first, at the best quality your device can play. One Android app
for phones, tablets and TVs (Android 11+, including the NVIDIA Shield and Google TV).

[![CI](https://github.com/xblackbytesx/slopstream/actions/workflows/ci.yml/badge.svg)](https://github.com/xblackbytesx/slopstream/actions/workflows/ci.yml)
[![Release](https://github.com/xblackbytesx/slopstream/actions/workflows/release.yml/badge.svg)](https://github.com/xblackbytesx/slopstream/actions/workflows/release.yml)
[![Latest release](https://img.shields.io/github/v/release/xblackbytesx/slopstream?sort=semver)](https://github.com/xblackbytesx/slopstream/releases/latest)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Android 11+](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white)](#install)
[![Android TV 11+](https://img.shields.io/badge/Android%20TV-11%2B-3DDC84?logo=android&logoColor=white)](#install)

- Only the channels you follow. No recommendations, no algorithm, no Google account.
- YouTube, Floatplane and Sauce+, each on its own (switch between them; they never mix).
  Floatplane and Sauce+ appear only once you sign in with your own membership.
- Shorts live on their own screen and never mix into your videos.
- Picks the best stream your hardware decodes (AV1, VP9, HEVC, H.264, HDR), with an
  optional cap at 2160p, 1440p, 1080p or 720p.
- A device check on first start measures decoders, display and connection speed.
- Resume where you left off, watched marks, Watch later and History.
- TV: a full D-pad player (hold left or right to seek faster) and refresh-rate matching.
- Phone: double-tap the sides to seek, swipe the left half for brightness and the right half
  for volume (landscape), picture-in-picture.
- Search your library, or YouTube itself when you ask for it.
- Comments, if you turn them on: loaded only when you open them, never stored.
- Import from NewPipe (subscriptions JSON or database zip) or Google Takeout
  (`subscriptions.csv`). On a TV, the import screen shows an address to open from your
  phone.
- Optional [sync server](#sync-server-optional) for one library per person across devices.

## Install

Get the APK from [Releases](https://github.com/xblackbytesx/slopstream/releases/latest), or
add this repository to [Obtainium](https://github.com/ImranR98/Obtainium) for updates.
Every release is signed with the same key; the release log prints its SHA-256 certificate
digest.

On a TV without a browser: enable developer options and network debugging, then
`adb connect <tv-ip>:5555` and `adb install slopstream-vX.Y.Z.apk`. Slopstream appears in the
TV's app row.

## Services

| Service | Account | Notes |
|---|---|---|
| YouTube | none | Subscriptions are kept on your device (or your sync server). Feeds come from YouTube's public RSS, playback through NewPipeExtractor. |
| Floatplane | your own paid membership | Subscriptions follow your account. Live streams show while they are on. |
| Sauce+ | your own paid membership | Pick creators from the catalog, or add them all. |

Floatplane and Sauce+ sign in through their own website login in a web view (on a TV: paste
the session from your phone). This is a stopgap until Floatplane registers a client for the
app. Sessions are encrypted with an Android Keystore key and sent only to those services and
to your own sync server. Nothing here bypasses a paywall: you see what your plan includes.

## Sync server (optional)

With [Slopstream server](https://github.com/xblackbytesx/slopstream-server) running at home,
every person in the house gets one library across their devices: subscriptions and
favorites, Watch later, progress and history, and their own Floatplane and Sauce+ sign-ins
(sign in once, on any device). The server also fetches the video lists all day, so a TV shows
a fresh grid the moment it is switched on, and changes reach an open app within seconds.
Connect in Settings, Sync server: the TV shows a code to type on the server's website.
Without a server the app works on its own.

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

Design decisions and invariants: [AGENTS.md](AGENTS.md).

## Licence

Slopstream, a subscriptions-only video player for Android phones and TVs.
Copyright (C) 2026 xblackbytesx

This program is free software: you can redistribute it and/or modify it under the terms of
the GNU General Public License as published by the Free Software Foundation, either version 3
of the License, or (at your option) any later version. See [LICENSE](LICENSE).

Built on:

- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0-or-later) by
  the NewPipe team, with jsoup (MIT), nanojson (MIT/Apache-2.0), Rhino (MPL-2.0) and
  protobuf-javalite (BSD-3-Clause).
- The PoToken code in `app/.../potoken/` and `po_token.html`, ported from
  [NewPipe](https://github.com/TeamNewPipe/NewPipe) (GPL-3.0-or-later).
- AndroidX, Jetpack Compose, Media3 (ExoPlayer), Room, Hilt, OkHttp, Coil and kotlinx
  (Apache-2.0).

Not affiliated with YouTube, Google, Floatplane Media or Sauce+.
