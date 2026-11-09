package com.xblackbytesx.slopstream.youtube.innertube

import com.xblackbytesx.slopstream.core.DownloadOffer
import com.xblackbytesx.slopstream.core.DownloadStream
import com.xblackbytesx.slopstream.core.SourceBlockedException
import com.xblackbytesx.slopstream.core.SourceException
import com.xblackbytesx.slopstream.core.SourceUnavailableException
import com.xblackbytesx.slopstream.core.VideoKind
import com.xblackbytesx.slopstream.core.playback.AudioCandidate
import com.xblackbytesx.slopstream.core.playback.AudioTrackRole
import com.xblackbytesx.slopstream.core.playback.DeviceProfile
import com.xblackbytesx.slopstream.core.playback.ManifestType
import com.xblackbytesx.slopstream.core.playback.ManifestVariant
import com.xblackbytesx.slopstream.core.playback.PlaybackPlan
import com.xblackbytesx.slopstream.core.playback.PlaybackPrefs
import com.xblackbytesx.slopstream.core.playback.StreamSelector
import com.xblackbytesx.slopstream.core.playback.Track
import com.xblackbytesx.slopstream.core.playback.VideoCandidate
import com.xblackbytesx.slopstream.core.playback.VideoCodec
import com.xblackbytesx.slopstream.core.playback.VideoDetails
import com.xblackbytesx.slopstream.youtube.StreamRequests
import com.xblackbytesx.slopstream.youtube.YoutubeIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Slopstream's own YouTube playback resolver (decision 30, experimental). One player request
 * with a reused visitor id, then a check that the chosen streams are served deep into the file,
 * where YouTube's PoToken enforcement shows up: the start plays, a later range answers 403.
 *
 * A failed check is rescued before the player sees the URLs:
 * 1. a fresh visitor id and a new player request (the refusal is per visitor, it seems);
 * 2. the response's HLS stream, served differently, also up to 4K.
 * Anything else throws, and the caller falls back to NewPipe.
 */
class InnertubeResolver(
    private val innertube: Innertube,
    private val http: OkHttpClient,
    private val client: InnertubeClient = InnertubeClient.VISIONOS,
) {
    /** How a play was found: shown in the app's extractor log and the bench. */
    data class Resolved(val plan: PlaybackPlan, val path: String, val steps: List<String>)

    /** A download's choices (decision 33), the best pair checked like a play. */
    data class Offered(val offer: DownloadOffer, val path: String, val steps: List<String>)

    /** One stream of an offer again with a fresh URL, for a download that hit an expired one. */
    data class Renewed(val stream: DownloadStream, val path: String, val steps: List<String>)

    /** Thrown with the steps taken, so a fallback still says where ours failed. */
    class Failed(message: String, val steps: List<String>, cause: Throwable? = null) : SourceException(message, cause)

    @Volatile private var visitor: String? = null

    suspend fun resolve(videoId: String, profile: DeviceProfile, prefs: PlaybackPrefs): Resolved =
        withContext(Dispatchers.IO) { Attempt(videoId).run { resolve(profile, prefs) } }

    /** The quality menu picked [candidateId]: a fresh response, that stream, checked the same way. */
    suspend fun switch(plan: PlaybackPlan.Adaptive, candidateId: String): Resolved =
        withContext(Dispatchers.IO) { Attempt(plan.videoId).run { switch(plan, candidateId) } }

    /**
     * What [videoId] can be saved as: every whole file the device plays, best first, ignoring the
     * cap (a download is chosen per video). The best video and the audio are checked three
     * quarters in first, since a download reads every byte: a refusal there would stop it partway.
     */
    suspend fun downloadOffer(videoId: String, profile: DeviceProfile, prefs: PlaybackPrefs): Offered =
        withContext(Dispatchers.IO) { Attempt(videoId).run { offer(profile, prefs) } }

    /** [streamId] (a video itag, or audio `itag:track`, as NewPipe names them) with a fresh URL. */
    suspend fun downloadStream(videoId: String, streamId: String): Renewed =
        withContext(Dispatchers.IO) { Attempt(videoId).run { renew(streamId) } }

    private inner class Attempt(val videoId: String) {
        val steps = ArrayList<String>()
        private val started = System.nanoTime()

        fun step(s: String) { steps += "${(System.nanoTime() - started) / 1_000_000}ms $s" }

        suspend fun resolve(profile: DeviceProfile, prefs: PlaybackPrefs): Resolved {
            val first = player(fresh = false)
            if (first.live) return Resolved(live(first), "hls-live", steps)
            if (first.postLive) fail("an ended stream still processing (DVR): not handled yet")

            val plan = adaptive(first, profile, prefs, forced = null)
            if (plan != null && served(plan)) return Resolved(plan, "dash", steps)

            val second = player(fresh = true)
            val again = adaptive(second, profile, prefs, forced = null)
            if (again != null && served(again)) return Resolved(again, "dash-new-visitor", steps)

            val hls = hls(second, profile, prefs)
            if (hls != null) return Resolved(hls, "hls", steps)
            fail("streams refused past the start, and no HLS fallback")
        }

        suspend fun switch(plan: PlaybackPlan.Adaptive, candidateId: String): Resolved {
            for (fresh in listOf(false, true)) {
                val response = player(fresh)
                val switched = adaptive(response, null, null, forced = candidateId)?.let {
                    it.copy(videoOptions = plan.videoOptions)
                } ?: fail("quality $candidateId is no longer offered")
                if (served(switched)) return Resolved(switched, if (fresh) "dash-new-visitor" else "dash", steps)
            }
            fail("quality $candidateId refused past the start")
        }

        suspend fun offer(profile: DeviceProfile, prefs: PlaybackPrefs): Offered {
            for (fresh in listOf(false, true)) {
                val r = player(fresh)
                if (r.live || r.postLive) fail("a livestream can't be downloaded")
                val files = wholeFiles(r)
                val videos = files.filter { it.isVideo }.associateBy { videoCandidate(it) }
                val audios = files.filter { it.isAudio }.associateBy { audioCandidate(it) }
                val options = StreamSelector.playable(videos.keys.toList(), profile, prefs)
                val audio = StreamSelector.selectAudio(audios.keys.toList())
                if (options.isEmpty() || audio == null) {
                    fail("no whole file this device plays (${videos.size} video, ${audios.size} audio)")
                }
                val best = videos.getValue(options.first())
                val sound = audios.getValue(audio)
                if (!served(listOf("video" to best.url, "audio" to sound.url))) continue
                val offer = DownloadOffer(
                    videoId = videoId,
                    title = r.title,
                    channelId = r.channelId,
                    channelName = r.author,
                    durationSec = r.durationSec,
                    thumbnailUrl = YoutubeIds.thumbnailFor(videoId, VideoKind.VIDEO),
                    videos = options.map { c -> download(videos.getValue(c), c) },
                    audio = download(sound, null, audio.id),
                    details = details(r),
                )
                return Offered(offer, if (fresh) "files-new-visitor" else "files", steps)
            }
            fail("files refused past the start")
        }

        suspend fun renew(streamId: String): Renewed {
            for (fresh in listOf(false, true)) {
                val files = wholeFiles(player(fresh))
                val video = files.filter { it.isVideo }.map { videoCandidate(it) to it }.firstOrNull { it.first.id == streamId }
                val audio = files.filter { it.isAudio }.firstOrNull { audioCandidate(it).id == streamId }
                val stream = when {
                    video != null -> download(video.second, video.first)
                    audio != null -> download(audio, null, streamId)
                    else -> fail("stream $streamId is no longer offered")
                }
                if (served(listOf(streamId to stream.url))) return Renewed(stream, if (fresh) "files-new-visitor" else "files", steps)
            }
            fail("stream $streamId refused past the start")
        }

        private fun download(f: PlayerResponse.Format, video: VideoCandidate?, audioId: String? = null) = DownloadStream(
            id = video?.id ?: requireNotNull(audioId),
            url = f.url,
            extension = when {
                f.container == "audio/mp4" -> "m4a"
                f.container.endsWith("/webm") -> "webm"
                else -> "mp4"
            },
            sizeBytes = f.contentLength,
            sizeExact = f.contentLength != null,
            video = video,
        )

        /** A player response; a bot check gets one retry with a new visitor. */
        private fun player(fresh: Boolean): PlayerResponse {
            try {
                var response = innertube.player(client, videoId, visitor(fresh))
                step("player ${response.status}${if (fresh) " (new visitor)" else ""}")
                if (response.botCheck && !fresh) {
                    response = innertube.player(client, videoId, visitor(fresh = true))
                    step("player ${response.status} (new visitor after bot check)")
                }
                if (response.botCheck) throw SourceBlockedException("YouTube wants a sign-in to prove this is not a bot")
                if (!response.ok) throw SourceUnavailableException(response.reason ?: "Not playable (${response.status})")
                return response
            } catch (e: InnertubeBlockedException) {
                throw SourceBlockedException(e.message ?: "YouTube is rate limiting", e)
            } catch (e: IOException) {
                fail("player request failed: ${e.message}", e)
            }
        }

        private fun visitor(fresh: Boolean): String {
            if (!fresh) visitor?.let { return it }
            return innertube.visitorId(client).also {
                visitor = it
                step("visitor id")
            }
        }

        /** The device's best stream and the original audio, each with its own DASH manifest. */
        /** Whole files with a known layout: what a byte-range player or download reads. */
        private fun wholeFiles(r: PlayerResponse) =
            r.formats.filter { !it.otf && !it.drc && it.initRange != null && it.indexRange != null && it.contentLength != null }

        fun adaptive(r: PlayerResponse, profile: DeviceProfile?, prefs: PlaybackPrefs?, forced: String?): PlaybackPlan.Adaptive? {
            val files = wholeFiles(r)
            val videos = files.filter { it.isVideo }.associateBy { videoCandidate(it) }
            val audios = files.filter { it.isAudio }.associateBy { audioCandidate(it) }
            val candidates = videos.keys.toList()
            val chosen = if (forced != null) candidates.firstOrNull { it.id == forced }
            else StreamSelector.selectVideo(candidates, requireNotNull(profile), requireNotNull(prefs))
            val audio = StreamSelector.selectAudio(audios.keys.toList())
            if (chosen == null || audio == null) {
                step("no adaptive pair (${videos.size} video, ${audios.size} audio files)")
                return null
            }
            val options = if (profile != null && prefs != null) StreamSelector.playable(candidates, profile, prefs).ifEmpty { listOf(chosen) } else emptyList()
            return PlaybackPlan.Adaptive(
                videoId = videoId,
                title = r.title,
                durationSec = r.durationSec,
                video = track(videos.getValue(chosen), r.durationSec, null),
                audio = audios.getValue(audio).let { track(it, r.durationSec, audio.language) },
                chosen = chosen,
                videoOptions = options,
                details = details(r),
            )
        }

        private fun track(f: PlayerResponse.Format, durationSec: Long, language: String?) = Track(
            url = f.url,
            mimeType = f.container,
            codecs = f.codecs,
            dashManifest = DashManifest.progressive(f, durationSec, language),
            cacheKey = "yt:$videoId:${f.itag}${f.audioTrackId?.let { ":$it" }.orEmpty()}",
        )

        /** Both streams answer a small range three quarters in, shaped like the player's requests. */
        suspend fun served(plan: PlaybackPlan.Adaptive): Boolean = served(listOf("video" to plan.video.url, "audio" to plan.audio.url))

        suspend fun served(urls: List<Pair<String, String>>): Boolean = coroutineScope {
            val checks = urls.map { (name, url) ->
                async(Dispatchers.IO) { name to probe(url) }
            }.awaitAll()
            step("deep check " + checks.joinToString(" ") { (n, code) -> "$n $code" })
            checks.all { (_, code) -> code in 200..299 }
        }

        private fun probe(url: String): Int {
            val length = Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: return -1
            val s = StreamRequests.shape(url, position = length * 3 / 4, length = PROBE_BYTES, requestNumber = 0)
            val request = Request.Builder().url(s.url).apply { s.headers.forEach { (k, v) -> header(k, v) } }
                .post(s.body.toRequestBody()).build()
            return runCatching { http.newCall(request).execute().use { it.code } }.getOrDefault(-1)
        }

        /** The HLS stream, its variants as the quality menu; checked on a segment near the end. */
        suspend fun hls(r: PlayerResponse, profile: DeviceProfile, prefs: PlaybackPrefs): PlaybackPlan.Manifest? {
            val master = r.hlsManifestUrl ?: run { step("no HLS in the response"); return null }
            val variants = runCatching { get(master)?.let(HlsMaster::parse) }.getOrNull().orEmpty()
            val candidates = variants.map { it.candidate }
            val chosen = StreamSelector.selectVideo(candidates, profile, prefs) ?: run { step("HLS: nothing this device plays"); return null }
            val playlist = variants.first { it.candidate == chosen }.url
            val segments = get(playlist)?.lines()?.filter { it.isNotBlank() && !it.startsWith("#") }.orEmpty()
            val code = segments.getOrNull(segments.size * 3 / 4)?.let(::head) ?: -1
            step("HLS ${chosen.label}, ${segments.size} segments, deep segment $code")
            if (code !in 200..299) return null
            // Best first, like every other quality menu.
            val playable = StreamSelector.playable(candidates, profile, prefs)
            return PlaybackPlan.Manifest(
                videoId = videoId,
                title = r.title,
                durationSec = r.durationSec,
                url = playlist,
                type = ManifestType.HLS,
                live = false,
                variants = playable.map { c -> ManifestVariant(c, variants.first { it.candidate == c }.url) },
                chosen = chosen,
                details = details(r),
            )
        }

        fun live(r: PlayerResponse): PlaybackPlan.Manifest {
            val url = r.hlsManifestUrl ?: fail("live, but no HLS manifest")
            return PlaybackPlan.Manifest(videoId, r.title, r.durationSec, url, ManifestType.HLS, live = true, details = details(r))
        }

        private fun get(url: String): String? = runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body.string() else null }
        }.getOrNull()

        private fun head(url: String): Int = runCatching {
            http.newCall(Request.Builder().url(url).header("Range", "bytes=0-${PROBE_BYTES - 1}").build()).execute().use { it.code }
        }.getOrDefault(-1)

        fun fail(message: String, cause: Throwable? = null): Nothing {
            step("failed: $message")
            throw Failed(message, steps.toList(), cause)
        }
    }

    private fun details(r: PlayerResponse) = VideoDetails(r.description.trim(), r.views)

    private fun videoCandidate(f: PlayerResponse.Format) = VideoCandidate(
        id = f.itag.toString(),
        codecs = f.codecs,
        width = f.width,
        height = f.height,
        fps = f.fps.takeIf { it > 0 } ?: 30,
        bitrate = f.bitrate,
        hdr = f.hdr || VideoCodec.isHdr(f.codecs),
    )

    /** Ids match NewPipe's (itag, then the track id), so a plan from either switches the same way. */
    private fun audioCandidate(f: PlayerResponse.Format) = AudioCandidate(
        id = listOfNotNull(f.itag.toString(), f.audioTrackId).joinToString(":"),
        codecs = f.codecs,
        bitrate = f.bitrate,
        role = when (f.audioContent) {
            "original" -> AudioTrackRole.ORIGINAL
            "dubbed", "dubbed-auto" -> AudioTrackRole.DUBBED
            "descriptive" -> AudioTrackRole.DESCRIPTIVE
            "secondary" -> AudioTrackRole.SECONDARY
            else -> if (f.audioTrackId != null && f.audioDefault) AudioTrackRole.ORIGINAL else AudioTrackRole.UNKNOWN
        },
        language = f.audioTrackId?.substringBefore('.')?.substringBefore('-'),
    )

    private companion object {
        const val PROBE_BYTES = 1_024L
    }
}
