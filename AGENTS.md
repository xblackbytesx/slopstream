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
    - Opened from the player (More, Comments: the Comments tab of the side panel, decision 31)
      or the video menu (`CommentsRoute`).
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
    or with the side panel open; the top and bottom tenth stay with the system bars. The brightness is
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
      plays use it; videos keep picture-in-picture. Leaving the player (Back) hands the play
      to the mini player (decision 27).
    - **The service follows every play, not every load** (fixed 2026-11-09, found on the user's
      phone: "stops a few seconds after the screen turns off; only killing the app helps").
      Android stops the service while a paused app sits in the background, and the session
      outlived it, so a later play on the same player (mini player, reopening the episode) never
      started it again: no foreground service, and the app was frozen with the screen off.
      `BackgroundAudio` now listens to the player and starts the service on every play, and the
      service adds the session again whenever it starts (`BackgroundAudioTest`, which fails on
      the old code). A headset's play button with the app idle in the background still can't
      start it (Android refuses); playing from the app or its notification always can.
    - Audio-only plays hold ExoPlayer's local wake lock while playing (`WAKE_MODE_LOCAL`).
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
    - Sauce+ switches its download files off (live report 2026-11-09: every variant listed
      `enabled: false` with an empty URL, codecs and sizes fine). Where no file can be had,
      the offer is the on-demand stream instead: `HlsFetcher` saves the variant's playlist
      segment by segment, AES-128 decrypted with the key the player uses (`Segmented`: the
      session headers for the site's host, the floatplane.com key host rewritten as in
      playback), joined into one `.ts`. Audio only goes through the same Transformer step.
      Stream id `<attachment>:hls:<variant>`; once a site's files were off, offers skip the
      `download` call. Resumable per segment (`<file>.part.hls`). The error when nothing fits
      still lists what the site offered (codec, size, off, hidden, no link).
      Verified on the phone 2026-11-09: a 1 h 26 min Safety Third episode, audio only, came
      out at 66 MB (about 100 kbps, the AAC track alone), so Transformer reads the joined TS.

27. **Mini player for audio-only plays** (2026-10-29; videos keep picture-in-picture, the
    user's call). `PlayerEngine` owns its scope, so it can outlive its screen. Leaving the
    player during an audio-only play (Back, or the app closing) hands the engine to
    `AudioPlayback` (a singleton) instead of releasing it; the media session and background
    service carry on unchanged. `MiniPlayer` shows it above the bottom bar (phones) or under
    the screens (TV): artwork, title, a progress line, play/pause, close.
    - Selecting it opens `PlayerRoute`, whose view model reclaims the same engine (same
      service and video id): no reload, no restart.
    - Any other play (a video, a Short, another download) stops it first; close saves the
      position like any player does and ends the notification.
28. **Play on a TV** (2026-11-04, the user's call, server 0.4.0; the protocol is in the server's AGENTS.md,
    "Playing on a TV"). With a sync server, any device sends a video to a TV that has
    Slopstream open, then works as its remote. Relayed by the server; chosen over a LAN
    protocol and Google Cast (Play Services, a web receiver that cannot run our player).
    - **Who**: the TV's own person, and everyone in the house once its owner sets it to
      Everyone on the server's Devices page. Anyone who may send may steer what plays.
    - **TV side** (`data/remote/RemoteReceiver`): only on TVs, only in the foreground (v1: an
      app in the background cannot open the player), a long-poll like `LongPoll`. It names
      the services it can play (YouTube plus each signed-in one); the server refuses the
      others by name. Going to the background tells the server at once. A play opens
      `PlayerRoute` with `remote`/`startMs`/`guest`, replacing an open player;
      `RemoteSessionLink` reports state (on change, every 5 s playing) and applies pause,
      resume, seek and stop. No mini-player hand-over for these.
    - **Progress**: own TV, the TV's library as usual. A guest's video: `PlayerEngine`
      `saveProgress = false`, and the server writes the reports into the guest's library.
      Floatplane and Sauce+ play with the TV's own sign-in (never lent between people).
    - **Sender side** (`ui/remote/`): "Play on TV…" in the video menu (not Shorts) and the
      player's More; it starts at the sender's resume position, or the player's position
      (the player pauses and closes once the TV has it). Then `RemoteRoute`: artwork, a seek
      bar moved on locally between reports, back/forward 10 s, play/pause, Stop. A TV that
      plays something offers its remote from the same list.

29. **Screensaver for OLED screens** (2026-11-08, the user's call). After a timeout without input
    (Settings, Screen: 1, 3 (default), 5, 10 minutes or Off), anywhere in the app, a black
    screen with the SLOP logo bouncing diagonally, changing color at each edge and flashing
    white at a corner hit, like the old DVD players.
    - **What holds it off**: only a video that plays (`PlayerSession` active and playing, or
      picture-in-picture). Paused, ended, audio-only and the mini player do not; the count
      starts again when a video stops playing. `IdleTimer` is the pure rule, `Screensaver` the
      app's one instance, ticked each second by `MainActivity` while started.
    - **Input**: `onUserInteraction` and generic motion on the activity's window, plus every
      sender command on a TV played remotely (`RemoteSessionLink` wakes it). Keys pressed in
      an open dialog are not seen (dialogs are windows of their own), an accepted gap.
    - **The window**: a Compose `Dialog`, so it sits above any open menu; system bars hidden on
      its own window, so the player's immersive mode is untouched. The waking key or touch is
      swallowed whole (wake on key up / pointer up) so it never also clicks a tile; volume
      keys pass through.
    - **Two black layers** (fixed 2026-11-08, found on the Shield): the dialog window is
      transparent and its content need not cover every pixel, so a strip of a paused video
      stayed lit at the left edge. Now `Blackout` also blacks out the activity's own window
      (over the player, which covers its video surface too; `BlackoutTest` checks every
      pixel), and the dialog window turns opaque black, gravity fill, once faded in.
    - **No wake lock of its own**: the system's own sleep and screensaver timeouts still apply
      and win if shorter. The player now keeps the screen on only while a video plays,
      resolves or rebuffers (it was on whenever the player was open, so a finished video's
      controls stayed lit forever).
    - **Corners** (`BounceField`): at 45 degrees a corner comes when the travel is a multiple of
      both ranges; ranges are shrunk by up to 8 percent (black on black) so one comes every
      4 to 12 minutes, the first not before a third of that. The logo is drawn to its ink
      bounds (`SlopLogo`), so the corner hit touches the corner.
    - **Logo**: SLOP in Anton (OFL 1.1, Impact is not redistributable), license text in
      `assets/licenses/`. The real DVD Video logo was considered and rejected: it is a
      licensed trademark (DVD Format/Logo Licensing Corporation), not for a public GPL app.

30. **Slopstream's own YouTube extractor, experimental** (2026-11-08, the user's call). Settings,
    Experimental, off by default. Playback (`resolvePlayback`, `switchVideo`) and, since
    decision 33, downloads; search, channels and comments stay on NewPipe. Whatever ours fails on, NewPipe plays (the
    user chose fallback over showing the error), so switching it on never makes playback worse.
    - **Why**: measured 2026-11-08 (`gradle :source-youtube:bench`). NewPipe makes 7 requests
      per play, one after another, every play: three `visitor_id`, two `player` (Android reel,
      visionOS), a web `player` and `/next` for metadata playback never uses. About 1.2 s. And
      the user's "fails today, fine tomorrow" (often on JerryRigEverything) was caught: URLs that
      serve the start of a file answer 403 deeper in (YouTube's PoToken enforcement), so a play
      starts and then dies; the player's one retry asks NewPipe for the same kind of URL.
    - **How** (`youtube/innertube/`): one `player` request as the visionOS client
      (`InnertubeClient`, values from NewPipe 0.26.5) with a visitor id fetched once and reused
      (without one: a bot check). Its URLs are plain: no JavaScript to run. Then a 1 KB range
      three quarters into both streams, in parallel (`served`). A refusal is rescued before the
      player sees it: a fresh visitor and a new response, then the response's HLS stream (also
      to 4K, muxed, served differently), then NewPipe. Live is the HLS manifest. OTF uploads
      and ended streams still processing (DVR) go to NewPipe for now.
    - Checked live 2026-11-08: IOS and ANDROID_VR also give plain URLs but 403 past the start
      without a PoToken, and TVHTML5 gets a bot check, so none is a fallback today.
    - **Measured**: same plays as NewPipe; resolve p50 276 ms (NewPipe 1,247), first media
      byte p50 291 ms (1,336), 3 requests including the check (7).
    - **The record** (`ExtractorLog`): every lookup with it on (a play, or the prefetch when a
      tile is selected; a play after a prefetch uses the cached plan), ours or the fallback, why
      ours failed, its steps; the last 200 in a private file, shown under Settings, Extractor log.
      Ids and timings only, never sent anywhere. This is how ours is judged on the devices.
    - The bench (`-PbenchResolvers=newpipe,own`, `-PbenchChannel=<UC id> -PbenchRounds=n`)
      checks real bytes at the start and at 80 %, never in CI.

31. **Description** (2026-11-08, the user's call): the creator's own text, in the player's side
    panel (`VideoPanel`: a side sheet on TV, the lower part on phones, the video keeps playing,
    Back or Close shuts it), now with two tabs, Description and Comments (the second only with
    comments on). More, Description opens it; there is no setting, since nothing is fetched.
    - **No request of its own**: it comes with the playback response, as
      `PlaybackPlan.details` (`VideoDetails`: plain text and views). Ours reads `videoDetails`
      `shortDescription` (the whole text, despite the name) and `viewCount`; NewPipe's
      `StreamInfo.description` is HTML, Floatplane's post `text` too (per the community spec,
      unverified live), both made plain by `HtmlText` in `:core` (paragraphs, bullets, shortened
      link text replaced by the address; `CommentText` uses it as well).
    - A download saves it beside its files (`<base>.txt`, removed with them), so it reads offline.
    - **Shown** (`DescriptionText`, pure): title, then channel, the tile's date and views, then
      a line at a time, each focusable on TV so the remote scrolls. A line that starts or ends
      with a timestamp within the video's length is a chapter: selecting it seeks there (and
      plays). Web addresses open in the browser on phones; plain text on TV.
    - Not from the video menu yet: that needs a lookup of its own per video.

32. **Recent searches** (2026-11-08, the user's call): earlier searches as a row of chips right
    under the search field; one press runs one again. Built for a remote that misfires:
    - **Kept only when meant**: on submit, or when one of its results is used (played, opened,
      its menu, a channel followed). Typing alone records nothing, so as-you-type library search
      never fills the list with half words. A repeat moves to the front (case and spacing
      ignored); 30 per service; per service like everything else (decision 12).
    - **Shown**: an empty field lists the recent ones; typing narrows them to those containing
      every typed word (6 at most), and the one just run keeps the whole row, so nothing moves
      under the focus. Down from the field lands on the newest; Down again reaches the results.
    - **Scope remembered**: an entry that searched YouTube itself ("Also search YouTube") runs
      that way again and shows a globe. This is the one way the toggle comes on by itself, and
      only from an entry that says so; the toggle stays off on every visit (decision 14).
    - **Removing is behind a long press** (OK held, or the Menu key), so a stray OK only ever runs
      a search: Search again (focused), Remove, Clear all of this service. The menu swallows the
      held key like the video menu does; focus then goes to the newest chip left.
    - `SearchHistory`: a private JSON file on the device, never synced, never sent anywhere.
      `SearchHistoryRules` is the pure part, tested.

33. **Downloads through our own extractor** (2026-11-09, the user's call, after the extractor
    worked on the Shield). With the experimental switch on, `downloadOffer` and `downloadStream`
    go ours first, NewPipe after, through the same `ownFirst` as plays, and land in the
    Extractor log (`download`, `download-renew`).
    - **Why first**: the same player response, and the failure it guards against hurts downloads
      most. A download reads every byte, so URLs that serve the start and 403 deeper in stop it
      partway.
    - **Offer**: every whole file the device decodes, best first, ignoring the cap, with the
      exact `contentLength`; the original audio; title, channel, description. The best video and
      the audio are checked three quarters in first; a refusal gets a new visitor, then NewPipe.
      No HLS rescue: a download reads files, not segments.
    - **Renewal** (an expired URL mid-download): a fresh response, the stream by id, the same
      check. Ids are NewPipe's (`itag`, audio `itag:track`), so either side renews the other's.
    - Live 2026-11-09 (`make smoke`, `ownExtractorDownloadsTheSame`): the 4K test video offers the
      same eight qualities with the same exact sizes as NewPipe; offer 404 ms with a new
      visitor, renewal 73 ms; the first 10 MB and the last 64 KB served.

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
   server thumbnail proxy (server roadmap). Play on a TV (decision 28): device testing;
   later "always ready" (the TV waits in the background, the Display over other apps
   permission granted over adb, as BSfreeTV does its Home switch).
5. Archive on the server (decision 24, phase 2): yt-dlp in the server image (pinned, opt-in
   self-update), files in `/data/vault`, an Archive list on the website, playback from the
   server so a video plays after YouTube deleted it (order: this device, the server, the
   service). The Save sheet then offers both. Later: auto-archive chosen channels.
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
