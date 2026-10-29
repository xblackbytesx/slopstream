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

Floatplane (2026-10-23): wired into the app (decision 16) and working on the user's Shield,
Sauce+ too (decision 17).

Sync server (2026-10-23, decision 18): the app side is built and covered offline against a fake
server; the server (`/workspace/slopstream-server`) has its own Postgres integration tests.
**Not yet run together on a device.**

## Build and verify

Docker only (`make`), see the Makefile header. Agents without Docker use a scratchpad
toolchain mirroring the image (Temurin 21, android-37.0, build-tools 37.0.0, Gradle 9.8.0)
with caches outside the repo and no committed `local.properties`.

- `make test`: `:core:test`, `:source-youtube:test` (offline; fixtures are synthetic) and
  `:app:testDebugUnitTest` (Robolectric, in-memory Room).
- `make smoke`: live checks against YouTube. Run it first whenever playback or feeds break.
- `make smoke-floatplane`: read-only report from the user's own Floatplane account. Needs
  `FP_CLIENT_ID` (device login, tokens cached in the git-ignored `.secrets/`), or for the
  spike only `FP_BEARER` / `FP_SESSION` from their own browser session; `SP_SESSION`
  (`__Host-sp-sess`) runs it against Sauce+. Never writes.
- `make screenshots`: phone (`w411dp-h891dp-port-xxhdpi`) and TV
  (`w960dp-h540dp-land-television-xhdpi`) renders. Look at them after UI changes.
- Kotlin warnings stay at zero.
- CI (GitHub Actions, primary since 2026-10-27; it was Gitea before): `.github/workflows/ci.yml`
  runs the `make test` suites plus a debug build on every push. `release.yml` on a `vX.Y.Z`
  tag: tests, then a signed release APK attached to the GitHub release (Obtainium); secrets
  listed in its header. The smoke tests never run in CI.

## Modules

| Module | Kind | Role |
|---|---|---|
| `:core` | JVM | Domain model keyed by `(SourceKey, remoteId)`, `ContentSource` contract, `StreamSelector`, `DeviceProfile`, `PlaybackPlan`. No Android, no network. |
| `:source-youtube` | JVM | NewPipeExtractor wrapper (`YoutubeSource`), `RssFeedFetcher`, `StreamPlanner` (itag to DASH manifest), `StreamRequests` (googlevideo request shape), import parsers. |
| `:source-floatplane` | JVM | Floatplane's platform, Floatplane and Sauce+ (`FloatplaneSite`): `FloatplaneAuth` (OAuth device flow, for when a client id exists), `FloatplaneApi` (REST, `SessionAuthorizer`/`TokenAuthorizer`), `FloatplaneMapping` (pure), `FloatplaneSource`. |
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
    - Live reports on the user's account (2026-10-22, a supporter plan): the
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

17. **Sauce+** (2026-10-23) is a second `FloatplaneSite` on the same module: one
    `FloatplaneSource` per site (`FloatplaneSites`), the same sign-in screen and storage.
    - Its one membership covers every creator, so the account lists no subscriptions. The
      user decided: **pick creators from the catalog** (`creator/list`), with an "Add all"
      button, followed locally like YouTube channels (capability `catalog`). Signing out
      keeps those picks for the next sign-in, since they are the user's own choice.
    - Session cookie `__Host-sp-sess`; no live (`hasLive = false`), no imports.
    - Playback: `PlaybackPlan.Manifest.hostRewrites` sends the playlist's
      www.floatplane.com key requests to www.sauceplus.com, with the session.

18. **Sync server** (2026-10-23; the contract is `/workspace/slopstream-server/AGENTS.md`):
    optional, one library per person, the server leads.
    - **What syncs**: `subscription`, `watch_later`, `watch_state` (progress, and history
      through its display fields and `deletedAt`, schema 3) and the person's own Floatplane
      and Sauce+ sessions. Feed items and settings do not.
    - **Bookkeeping** (schema 3): `rev` (the server's, null until synced), `localVersion`
      and `syncedVersion`. Every repository write goes through the DAO's `upsert*`, which
      keeps the bookkeeping and bumps `localVersion`; server rows are written through
      `SyncDao` with both versions equal, so applying them never marks anything unsynced.
    - **A sync** (`SyncEngine`): send the unsynced rows (500 per kind) with their rev as
      `baseRev`, apply the answer as is, store the cursor. A row edited while the request was
      out keeps the edit; if the server took what was sent, the edit's base becomes that rev
      (`rebase*`), else the next round is a conflict. `reset` (the server is behind the
      cursor) resends everything.
    - **When** (`SyncScheduler`): coming to the front, every minute while in front, 5 s after
      a local change, after a sign-in or sign-out. Never in the background.
    - **Connecting**: the address must be https (plain http only to a LAN address, with a
      warning). Pair with a code shown on the device, or paste a token (phones). Merge
      (default: everything here goes up with base rev 0) or replace (the one hard delete,
      chosen by the user). Disconnecting keeps the library and the sign-ins.
    - **Sessions**: a WebView sign-in queues a PUT (`SyncConfig.pending`) so a failed upload
      never makes the next sync forget the fresh sign-in; sign-out queues a DELETE and means
      everywhere. The server's list replaces this device's: a value arrives only when the
      fingerprint differs, a missing service is signed out here (account follows tombstoned).
      On the first sync after connecting, local sessions the server lacks go up instead.
      A service refusal on this device is reported with the fingerprint (`Accounts.expired`).
    - The History screen (from Watch later) lists `watch_state` of the current service.

19. **The server fetches the video lists** (2026-10-24, server 0.2.0; its AGENTS.md
    "Feeds"). Lists only: playback always resolves here (IP-bound googlevideo URLs,
    PoToken, Floatplane's rate-limited delivery).
    - `ServerFeed.pull` sends every followed channel of a signed-in service with its
      `subscription.feedRev` (schema 4, cache), applies the answer through `FeedDao.merge`
      (durations kept), deletes tombstoned items, renames channels and sets
      `lastRefreshAt` from the server's `lastOkAt`.
    - `FeedRepository.refreshAll` pulls first and fetches here only what the server lacks
      fresh (no status, or none in 2 h). No server, or an unreachable one, means everything
      is fetched here as before. `refreshNow` (pull-to-refresh, the TV button) asks the
      server to fetch first; the 1-minute tick while the app is open pulls only.
    - `feed_item` stays a cache, filled from either side; the last good copy rule holds.

20. **Comments** (2026-10-24): on demand, on the device, off by default (Settings, "Comments").
    Not the server's job: it has no extractor, and a store of every video's threads would be
    big, stale and mostly unread.
    - `ContentSource.comments(videoId, cursor)` with `SourceCapabilities.hasComments`; YouTube
      through NewPipeExtractor's `CommentsInfo` (pages, replies, pinned, hearted), the text made
      plain by `CommentText`. YouTube's own order: it ranks within one video, never the feeds.
    - Opened from the player (a Comments button; a side sheet on TV, the lower part on phones,
      the video keeps playing; Back closes it) or the video menu (`CommentsRoute`).
    - `CommentsLoader` holds pages in memory only, loads the first page when opened and the
      rest when asked; nothing is written anywhere.
    - `make smoke` checks a first page, a second page and replies.
    - Floatplane and Sauce+ (2026-10-28), from the community spec, unverified live until
      `make smoke-floatplane` reports on them: `/api/v3/comment` 20 at a time after the last
      comment id (`fetchAfter`); a comment arrives with its first few replies, shown first
      without a request, then `/api/v3/comment/replies` after the last one shown (`rid`) until
      `totalReplies`. The first page reads the post for its creator's account, whose comments
      are marked as the uploader's; pinned is `pinDate`. Plain text with a timestamp
      (`Comment.postedAt`, worded on the device). Livestreams have chat instead: none here.
    - `CommentsLoader` drops comments it already shows and ends a list whose page adds nothing,
      so a service that repeats a page can neither crash the list nor loop.
    - History stays where it is (the user's call, 2026-10-24): a button on Watch later.

21. **Long-poll and durations** (2026-10-24, server 0.3.0; its AGENTS.md has the protocol).
    - While the app is in front, `LongPoll` waits on `/api/v1/changes` and syncs and pulls the
      feed when it says something changed, and at least every 5 minutes. Without a server, or
      while it can't be reached, it falls back to a refresh every 60 s (30 s when connected).
    - `Durations` works duration jobs in the background: the server's `durationJobs`, or,
      without a server, the channels a local refresh found new uploads in. One channel at a
      time, 2 s apart, never while a player is open; `ContentSource.durations` (YouTube: the
      Videos and Live tabs). Results go into the cache and up to the server.
    - Premieres are parked (roadmap).

22. **Swipe for brightness and volume** (2026-10-25, phones in landscape, as NewPipe and
    Grayjay): a vertical swipe on the left half sets the window's brightness, on the right half
    the media volume (`SwipeAdjust`). Not on TV, in Shorts (the pager swipes vertically), in PiP
    or with comments open; the top and bottom tenth stay with the system bars. The brightness is
    reset when the player closes and reused by the next player in the same process, never saved.

23. **Older videos on a channel page** (2026-10-28): a "Load older videos" button at the end of
    each channel tab (TV and phone alike; no scroll-velocity trick) loads the next page from
    the service (`ContentSource.channelPage`, `SourceCapabilities.archiveKinds`): YouTube's
    Videos, Shorts and Live tabs by continuation (about 30, Shorts 48), Floatplane and Sauce+
    posts by `fetchAfter` offset (20). On demand only, one page per press, never by a refresh
    or the server: background archive fetching for every subscription is what gets an IP
    bot-blocked.
    - Pages go into `feed_item` as `archived` rows (schema 5): shown on that channel page and in
      library search only, never in the main feeds or the Shorts player, never pruned or
      dropped by a refresh or a server pull (`merge` keeps the flag).
    - `ChannelArchive` keeps the next-page cursor for the life of the process; after a restart
      the next press walks past pages whose items are all cached (25 pages per press at most).
    - YouTube's Shorts tab dates nothing: those items are `dateUnknown` (schema 5), placed a
      minute apart below the oldest cached Short in page order, and their tiles show no date.
    - `make smoke` pages three times back on the Videos and Shorts tabs.

24. **Download and Archive** (2026-10-29, the user's call): two features, one entry. Download
    keeps a copy on this device for offline viewing (disposable, per device, never synced);
    Archive (next, server side) keeps a permanent copy on the sync server so a video survives
    its deletion. Code names `download` and `vault`, so neither clashes with `ChannelArchive`.
    Download is built for YouTube; Floatplane and Sauce+ followed (decision 26).
    - `ContentSource.downloadOffer` / `downloadStream`, `SourceCapabilities.canDownload`. Only
      whole-file (progressive) adaptive streams, each with its exact size (`clen`). The sheet
      lists every resolution the device decodes, best first and preselected, ignoring the
      quality cap (that is for streaming), each with its size and the free space.
    - Video and audio are kept as the two files YouTube serves, never muxed, and play merged
      like streaming does (`PlaybackPlan.Local`): no ffmpeg, nothing to break. Files go in the
      app's own external files dir (`downloads/`); no permission, removed on uninstall.
    - `Downloads` is the queue (the `download` table, schema 6); `DownloadWorker` works it one
      video at a time in a foreground worker (dataSync, a progress notification; phones ask
      for notifications on the first download). `RangeFetcher` asks googlevideo.com for
      10 MB ranges shaped like the player's (`StreamRequests`), resumes a `.part` file, renews
      an expired URL once per position by stream id. A lost network leaves the row queued
      and the worker retries; anything else marks it failed, and selecting it retries.
    - Playback prefers the files: `PlaybackRepository.resolve` returns the local plan before
      asking the service, and prefetch skips downloaded videos. Files that are gone mark the
      row failed instead of playing.
    - Places: the video menu ("Download…", "Cancel download", "Remove download"); the player's
      new **More** button, which now also holds Comments (one button fewer in the control
      row); a badge on the tile; the Downloads screen, a button on Watch later next to
      History (no new nav item: the phone's bottom bar is full), with sizes, used and free
      space, delete one or all.
    - Refuses a download that would leave under 200 MB free.
    - `make smoke` checks the offer (exact sizes), a 10 MB range, the last range and a renewed
      URL against a live 4K upload.

25. **Audio only, played like a podcast** (2026-10-29): the Download sheet's last choice is
    "Audio only" (the best original audio track; `download.videoFile` null, schema 7).
    - It plays from the device as `PlaybackPlan.Local` without a video path: the thumbnail
      as artwork, controls that stay up, the screen allowed to sleep, no picture-in-picture.
      Progress and resume are the usual watch state, so they sync like any video.
    - Background playback: `BackgroundAudio` puts a Media3 `MediaSession` on the player
      screen's own ExoPlayer and `PlaybackService` (a `MediaSessionService`, type
      mediaPlayback) publishes it; Media3 shows the media notification and lock-screen
      controls and keeps the service in the foreground while it plays. Only audio-only
      plays use it; videos keep picture-in-picture. Leaving the player (Back) ends it, as
      with any video: there is no mini player.
    - More, "Watch the video" streams the video from the same position; a retry or 403
      recovery keeps streaming instead of falling back to the download.

26. **Floatplane and Sauce+ downloads** (2026-10-29): not the encrypted HLS the player
    streams, but the website's own Download button: `delivery/info` with
    `scenario=download` lists one plain MP4 per quality (picture and sound in one file, URL
    token only, per the community spec), so `RangeFetcher` fetches it with Range headers
    like any file. Unverified live until `make smoke-floatplane` reports on it (the report
    probes the first and last 64 KB of the smallest file, its type and its `ftyp` box).
    - `DownloadOffer.audio` null means each file carries its sound; a download is then one
      file (`download.audioFile` null, schema 8) and plays as is. Sizes are estimates from the
      bitrates. A stream id is `<attachment>:<variant>`: renewing a URL is one delivery call,
      and an offer is two (post, delivery), within the endpoint's rate limit.
    - Audio only there: the smallest file, then `TransformerAudioExtractor` (Media3
      Transformer with the video removed) copies its AAC track into an `.m4a` without
      re-encoding, and the MP4 goes. If that fails the whole file is kept under that name and
      plays with the video track disabled (`PlayerEngine` turns video off for every
      audio-only plan). `DownloadEntity.extractsAudio` marks such a row.

## Invariants

- **No algorithmic content.** Feeds hold only subscribed channels; ordering is publish date
  only. The one exception is opt-in: Search's "Also search YouTube" (decision 14).
- **Shorts never appear in the Videos feed.** They have their own screen and player.
- **Last good copy.** A failed or blocked refresh never deletes cached items. A
  `SourceBlockedException` (429/403/bot check) stops the whole run.
- **Sync-ready rows.** `subscription`, `watch_state`, `watch_later` carry `updatedAt`,
  `deletedAt` (tombstone, never hard delete) and `deviceId`. `feed_item` is cache, filled by
  this device or from the sync server's lists (decision 19); `subscription.feedRev` too.
- **Imports only add.** An export never unsubscribes anything. (Account subscriptions mirror
  the account instead, decision 16.)
- **Service credentials** never leave `Accounts` and the requests that need them, except to the
  user's own sync server (decision 18, https or a LAN address the user accepted): never
  logged, printed or put in a URL; headers go only to the hosts a plan names. The sync token
  lives in the same Keystore-encrypted store.
- **The server leads.** What a sync returns is applied as is, except rows edited here while
  the request was out (`SyncEngineTest`). Never write synced tables around the DAO's
  `upsert*` (the change would never be sent) or put server rows through it (they would be
  sent back).
- **Core library desugaring stays on** (`desugar_jdk_libs_nio`). NewPipeExtractor calls Java
  10+ APIs such as `URLEncoder.encode(String, Charset)` that Android lacks below API 33;
  without it, channel search crashes with `NoSuchMethodError` on the Shield (Android 11).
  JVM tests can't catch this.
- **Back lands where you were (TV).** List screens hold their grid's scroll state themselves
  (the grid leaves composition for a frame while a list reloads) and remember the tile last
  played or opened; `VideoGrid(restoreFocusKey)` scrolls to it and focuses it on return, so
  focus never falls to the rail (`GridFocusTest`). Search does the same for its tiles.
- **One place in the tree for the NavHost.** `AppChrome(showNav)` hides the rail or bottom
  bar for the player but never moves the content: composing the NavHost elsewhere rebuilt
  every screen and lost the tile to focus on Back (found on the Shield 2026-10-24;
  `TvNavigationTest`). Restoring focus retries for a few frames until the tile is attached.
- **Text fields on TV let go of Up and Down** (`SearchBar`): Compose keeps them for the cursor
  otherwise, and the remote could not reach the results.
- **Local network permission on Android 17+** (`ACCESS_LOCAL_NETWORK`, "Nearby devices") is
  asked when the user starts something that needs it (Connect, a LAN page), never at launch
  (`rememberLocalNetworkRequest`).
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
4. Sync server: built 2026-10-23 (decision 18), server-fetched lists 2026-10-24 (decision 19,
   0.2.0), long-poll and durations 2026-10-24 (decision 21, 0.3.0). Next: premieres (an
   "upcoming" state with its start time on both sides); NewPipe history import; optional
   server thumbnail proxy (server roadmap).
5. Archive on the server (decision 24, phase 2): yt-dlp in the server image (pinned, opt-in
   self-update), files in `/data/vault`, an Archive list on the website, playback from the
   server so a video plays after YouTube deleted it (order: this device, the server, the
   service). The Save sheet then offers both. Later: auto-archive chosen channels.
   Next for downloads: a mini player for audio-only plays, so Back keeps them going.
6. Sauce+. Checked 2026-10-23: www.sauceplus.com serves the same frontend build as
   Floatplane with a branding config, so the same API and website sign-in apply; the
   session cookie is named `__Host-sp-sess` there instead of `sails.sid`. Differences in that config: its own accounts (Keycloak realm `sauceplus`,
   company Open Sauce Inc.), `usesGlobalSubscriptions: true` (one subscription for every
   creator), `isLiveStreamEnabled: false`. Cloudflare challenged plain requests from the
   sandbox, but not the user's home network.
   Live report 2026-10-23 (`SP_SESSION`): `user/subscriptions` is **empty**, `creator/list`
   lists all 28 creators, every post `isAccessible`; one sub-channel per creator; H.264
   360p to 1080p at 23.98 fps on Floatplane's CDN (`/SP-Videos/`); progress works (86,
   100). The AES key URL points at **www.floatplane.com** `watchKey`, which refuses the
   Sauce+ session (403 `notLoggedInError`, also with the Sauce+ Origin); the **same path on
   www.sauceplus.com** with the session returns the 16-byte key, which decrypts to valid
   MPEG-TS. Built 2026-10-23 as decision 17; works on the user's Shield the same day.
