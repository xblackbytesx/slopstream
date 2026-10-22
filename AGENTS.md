# Slopstream: agent guide and decision register

One Android app for phones, tablets and TVs that plays the channels you subscribe to,
newest first, at the best quality the device can decode. No algorithm, no recommendations,
no account. YouTube first; Floatplane and Sauce+ follow (with their real logins and
subscriptions, never bypassing payment), then an optional sync server.

This is the only agent instruction file. Keep it current when decisions change, and keep it
honest about what is built versus planned.

## Status (2026-10-23)

Built: scaffold, `:core`, `:source-youtube`, app with Videos / Shorts / Watch later /
Subscriptions / Channel / Settings / Device check / Player / Shorts player, imports, LAN
import page, PoToken provider, background refresh, PiP, frame-rate matching.
Verified: JVM + Robolectric tests, `make smoke` against live YouTube from the sandbox
(Shorts split, search, handle resolve, 4K VP9 plan, stream bytes served), debug and R8
release builds. First device test by the user 2026-10-22: works well. Channel search crashed on
Android 11 until desugaring was enabled (see Invariants).

Floatplane (2026-10-23): wired into the app (decision 16). Live report run twice against the
user's account (findings in decision 13). Sign-in, feeds, playback and live are built and
covered offline; **not yet tested on a device**.

## Build and verify

Docker only (`make`), see the Makefile header. Agents without Docker use a scratchpad
toolchain mirroring the image (Temurin 21, android-37.0, build-tools 37.0.0, Gradle 9.8.0)
with caches outside the repo and no committed `local.properties`.

- `make test`: `:core:test`, `:source-youtube:test` (offline; fixtures are synthetic) and
  `:app:testDebugUnitTest` (Robolectric, in-memory Room).
- `make smoke`: live checks against YouTube. Run it first whenever playback or feeds break.
- `make smoke-floatplane`: read-only report from the user's own Floatplane account. Needs
  `FP_CLIENT_ID` (device login, tokens cached in the git-ignored `.secrets/`), or for the
  spike only `FP_BEARER` / `FP_SESSION` from their own browser session. Never writes.
- `make screenshots`: phone (`w411dp-h891dp-port-xxhdpi`) and TV
  (`w960dp-h540dp-land-television-xhdpi`) renders. Look at them after UI changes.
- Kotlin warnings stay at zero.

## Modules

| Module | Kind | Role |
|---|---|---|
| `:core` | JVM | Domain model keyed by `(SourceKey, remoteId)`, `ContentSource` contract, `StreamSelector`, `DeviceProfile`, `PlaybackPlan`. No Android, no network. |
| `:source-youtube` | JVM | NewPipeExtractor wrapper (`YoutubeSource`), `RssFeedFetcher`, `StreamPlanner` (itag to DASH manifest), `StreamRequests` (googlevideo request shape), import parsers. |
| `:source-floatplane` | JVM | `FloatplaneAuth` (OAuth device flow, for when a client id exists), `FloatplaneApi` (REST, `SessionAuthorizer`/`TokenAuthorizer`), `FloatplaneMapping` (pure), `FloatplaneSource`. |
| `:app` | Android | Compose UI, Hilt, Room, DataStore, Media3, WorkManager, PoToken WebView, device check, LAN import server. |

A new service is a new JVM module implementing `ContentSource`, registered in
`data/Sources.kt`. Nothing in the app may branch on "is this YouTube" outside that module and
`StreamRequests` (the data source shaping is googlevideo specific and checks the URL).

## Decisions

1. **NewPipeExtractor** (GPL-3.0) for YouTube, so the app is **GPL-3.0**. Grayjay was rejected
   as a base: its licence is source-available, not free software.
2. **JitPack is allowed for `com.github.TeamNewPipe` only** (`exclusiveContent` in
   `settings.gradle.kts`), and those artifacts are pinned by sha256 in
   `gradle/verification-metadata.xml`. Everything else is trusted from Google/Central.
3. **No Google account.** Subscriptions are local rows. Feeds come from RSS of the uploads
   playlists: `UULF` (videos), `UUSH` (Shorts), `UULV` (streams), derived from the `UC` id.
   That split is YouTube's own, so we never guess what a Short is. Verified live
   2026-10-22. A 404 means "none of that kind". If all three 404, the channel feed is used
   with `/shorts/` links as the Short marker; if RSS errors, the extractor's channel tabs.
4. **RSS has no durations.** Durations fill in when a video is resolved for playback or
   prefetch, and `FeedDao.merge` never overwrites a known one with null.
5. **Playback**: one video-only itag + one audio itag, each as an offline-built
   one-representation DASH manifest, merged (`MergingMediaSource`). Requests are reshaped
   like NewPipe's `YoutubeHttpDataSource`: `&range=` and `&rn=` in the URL, POST body
   `x\0`, client user agent. Live uses HLS with the cap applied in the track selector.
6. **Stream choice** (`StreamSelector`): highest resolution the device decodes in hardware,
   within the cap, then fps, then HDR, then AV1 > VP9 > HEVC > H.264, then bitrate. The cap
   compares the short side, so a 1080x1920 Short is 1080p. Audio: original track only,
   never auto-dubs or descriptions.
7. **Device check** on first start and from Settings: hardware decoders per codec and
   ladder rung, display modes and HDR, a 5 s throughput test on a real 4K stream. The first
   run applies the recommended cap (stricter of display and network); later runs only show
   it.
8. **One APK, two UIs.** TV = `UiModeManager` television or leanback feature: left rail
   (expands on focus), 10-foot focus lift, D-pad player. Phone: bottom bar, pull to refresh,
   gestures, PiP. tv-material was not needed; plain Material3 with our own focus styling.
9. **PoToken**: NewPipe's BotGuard WebView, ported to coroutines (`potoken/`). On
   2026-10-22 the extractor served working streams via its visionOS client without one, so
   the provider is insurance for the web client. Devices without WebView return null.
10. **TV import** without a file picker: `LanImportServer` serves one page on port
    8765-8767 while the Import screen is open. Upload only, 50 MB cap, nothing else served.
11. **Frame-rate matching** via `preferredDisplayModeId` (works on Android 11 / Shield):
    exact rate first, else the fewest-repeat multiple, closest fit. Reset when the player
    closes.

12. **Services are hard-split** (decided 2026-10-22). Each service has its own feeds,
    subscriptions, Watch later and channel screens; no screen ever mixes items from two
    services, and nothing matches or merges "the same video" across them. Shared: the UI
    components (tiles, grids, chrome, focus), the player and stream selection, settings and
    the device check. Screens are written once against `SourceKey` plus the source's
    capabilities (has Shorts, has live, needs login, account subscriptions); a screen never
    branches on which service it shows. Every list query takes a `source`. `Services.current`
    picks the service; the chips (`ServiceBar`) appear only when two or more are listed.

13. **Floatplane** (spike, findings so far):
    - Sign-in is OAuth 2.0 device authorization against Keycloak
      (`auth.floatplane.com/realms/floatplane`), the same flow Floatplane's own TV app uses
      (client `fp-tv-app`, approval page `floatplane.com/link`). Unregistered client ids get
      `invalid_client`, so Slopstream needs **its own registered client id**; never borrow
      `fp-tv-app` or `floatplane-downloader`. API calls take `Authorization: Bearer`.
    - Signed out and not-in-plan are both HTTP 403; the body's `errors[].name`
      (`notLoggedInError`, `notSubscribedError`) tells them apart.
    - Identity: a subscription is a **creator** (what Floatplane sells), so the creator id is
      our channel id and one request lists all its sub-channels' posts. The sub-channel
      (TechLinked, ShortCircuit, ...) only names the tile. A feed item is a **post**; playback
      resolves its first ready video attachment in `attachmentOrder`.
    - Feed: video posts the plan includes only (`isAccessible`); text and picture posts are
      left out. Durations come with the list (`metadata.videoDuration`).
    - Playback: `delivery/info` returns one **muxed HLS playlist per quality**, ranked by
      `StreamSelector`, so the plan is `PlaybackPlan.Manifest` for now. The delivery
      endpoint is rate limited: call it once per play, never to prefetch.
    - Live reports on the user's account (2026-10-22, plan "LTT Supporter (Alpha)"): the
      `sails.sid` session works; list posts carry the channel as an object; H.264 only,
      360p to 1080p at 29.97 fps (`hls.fmp4` offers the same codecs); the 4K variant is
      `enabled=false` with an empty URL (not in that plan), and the mapping skips it.
      Playlist and segments need only their URL tokens. Segments are **AES-128 HLS**
      (standard HLS encryption) and the key (`www.floatplane.com/api/video/watchKey`) is
      **403 without the account**, 16 bytes with it; it decrypts to valid MPEG-TS. The live
      delivery returns a URL even when offline, but that **playlist is 404 while offline**
      (`Can not find channel`). Progress is a percent per post (spec: 0 to 100; seen 6, 31,
      66), absent for unwatched posts.
    - Open: sub-channel filtering on the creator page; showing Floatplane's own progress.

14. **Search** (2026-10-22): one Search screen (top of the TV rail; a header icon on phones,
    whose bottom bar stays at five). The library is searched as you type: followed channels by
    name (case and accent insensitive) and the titles and channel names of cached feed items
    and Watch later (`LibrarySearch`, every word must match, `%`/`_` literal). "Also search
    YouTube" is off on every visit and never saved; when on, YouTube's channel and video search
    runs only on submit, never per keystroke. YouTube results play in the regular player
    (never the Shorts pager, which only pages the Shorts feed) and offer no channel page,
    which only knows followed channels.
15. **Favorite channels**: `subscription.favorite` (schema 2, synced like the rest of the row:
    toggling bumps `updatedAt`). Favorites are pinned in their own section at the top of
    Subscriptions and refreshed first. The Subscriptions page has a name filter. Schema
    changes are Room `AutoMigration`s against the exported `app/schemas/`; never bump the
    version without one, the app is installed on real devices.

16. **Floatplane in the app** (2026-10-23):
    - **Sign-in, stopgap**: the website session (`sails.sid`), from Floatplane's own login
      page in a web view, or pasted (LAN page on TV, a field on phones). Not another app's
      client id. Replaced by the device-code login (`FloatplaneAuth`, `TokenAuthorizer`)
      once Floatplane registers a client id for Slopstream. `FloatplaneAccount` checks the
      session (`user/self`) before storing it; sign-out calls `auth/logout` first.
    - **Credentials** live in `Accounts`: AES-GCM with an Android Keystore key, in
      `noBackupFilesDir`, in memory otherwise. The web view's copy is removed when the
      sign-in screen closes. A rejected session marks the account `EXPIRED`: that service's
      refresh stops, its cache stays, and a "sign in again" prompt shows.
    - **Subscriptions** come from the account (`accountSubscriptions`), mirrored on every
      full refresh: new creators added, lapsed ones tombstoned (favorite kept). No adding,
      importing or unsubscribing in the app. Signing out tombstones them.
    - **Playback**: `PlaybackPlan.Manifest` with `variants` (the quality menu switches
      playlists locally, no new delivery call) and `authHeaders` sent only to `authHosts`
      (the key host), never the CDN. No prefetch on focus (`prefetchPlayback = false`).
    - **Live**: on each refresh, a creator with a stream gets a `LIVE` item `live:<creatorId>`
      when its playlist answers 200. `ChannelFeed.completeKinds` drops it once offline is
      certain (404); an unanswered check leaves it.

## Invariants

- **No algorithmic content.** Feeds hold only subscribed channels; ordering is publish date
  only. The one exception is opt-in: Search's "Also search YouTube" (decision 14).
- **Shorts never appear in the Videos feed.** They have their own screen and player.
- **Last good copy.** A failed or blocked refresh never deletes cached items. A
  `SourceBlockedException` (429/403/bot check) stops the whole run.
- **Sync-ready rows.** `subscription`, `watch_state`, `watch_later` carry `updatedAt`,
  `deletedAt` (tombstone, never hard delete) and `deviceId`. `feed_item` is cache.
- **Imports only add.** An export never unsubscribes anything. (Account subscriptions mirror
  the account instead, decision 16.)
- **Service credentials** never leave `Accounts` and the requests that need them: never logged,
  printed, synced or put in a URL; headers go only to the hosts a plan names.
- **Core library desugaring stays on** (`desugar_jdk_libs_nio`). NewPipeExtractor calls Java
  10+ APIs such as `URLEncoder.encode(String, Charset)` that Android lacks below API 33;
  without it, channel search crashes with `NoSuchMethodError` on the Shield (Android 11).
  JVM tests can't catch this.
- Screens split ViewModel wiring from stateless pieces where tests need them
  (`AppChrome`, `VideoGrid`, `VideoTile`, `ProfileSummary`).

## Bumping NewPipeExtractor

When `make smoke` fails on playback: bump `newpipeExtractor` in
`gradle/libs.versions.toml` to NewPipe's current tag, delete the two TeamNewPipe
`<component>` blocks from `gradle/verification-metadata.xml`, run
`gradle --write-verification-metadata sha256 :source-youtube:compileKotlin` in the container,
keep only the TeamNewPipe entries it adds, then `make smoke`. Check NewPipe's
`PoTokenWebView.kt` and `po_token.html` for changes at the same time.

## Roadmap

1. Device testing on the Shield and a phone; fix what that finds (YouTube first, until stable).
2. Channel-tab enrichment for durations and upcoming premieres; channel avatars on import.
3. Floatplane: device testing; request Slopstream's own client id from Floatplane and switch
   to the device-code login; sub-channel filter; Floatplane's own progress on tiles.
4. Sync server (Rust/axum/Postgres like quantifyme-v2): subscriptions, watch state, watch
   later, last writer wins on `updatedAt`. NewPipe history import.
5. Sauce+ (check whether it shares Floatplane's backend first).
