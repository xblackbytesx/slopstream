package com.xblackbytesx.slopstream.youtube

import com.xblackbytesx.slopstream.core.VideoKind
import com.xblackbytesx.slopstream.core.playback.DecoderCaps
import com.xblackbytesx.slopstream.core.playback.DeviceProfile
import com.xblackbytesx.slopstream.core.playback.PlaybackPlan
import com.xblackbytesx.slopstream.core.playback.PlaybackPrefs
import com.xblackbytesx.slopstream.core.playback.QualityCap
import com.xblackbytesx.slopstream.core.playback.VideoCodec
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.time.Duration.Companion.minutes

/**
 * Live checks against YouTube (`make smoke`). They confirm the assumptions the feed and player
 * are built on, and print what they saw so a failure explains itself. Not part of `make test`.
 */
class YoutubeSmokeTest {
    private val client = OkHttpClient()
    private val source = YoutubeSource(client, Locale.UK)

    /** Linus Tech Tips: long uploads, Shorts and livestreams, for years. */
    private val channel = "UCXuqSBlHAE6Xw-yeJA0Tunw"

    @Test
    fun uploadsPlaylistsSplitShortsFromVideos() {
        val fetcher = RssFeedFetcher(client)
        val feed = fetcher.fetch(channel)
        val byKind = feed.items.groupBy { it.kind }
        println("feed '${feed.channelName}': " + byKind.mapValues { it.value.size })
        byKind.forEach { (k, v) -> v.take(3).forEach { println("  $k ${it.publishedAt} ${it.remoteId} ${it.title}") } }

        assertTrue("UULF returned no regular videos", byKind[VideoKind.VIDEO].orEmpty().isNotEmpty())
        assertTrue("UUSH returned no Shorts", byKind[VideoKind.SHORT].orEmpty().isNotEmpty())
        val shorts = byKind[VideoKind.SHORT].orEmpty().map { it.remoteId }.toSet()
        assertFalse("a Short leaked into the videos feed", byKind[VideoKind.VIDEO].orEmpty().any { it.remoteId in shorts })
    }

    @Test
    fun channelFeedShortsLinkHint() {
        // Informational: tells us whether the plain channel feed marks Shorts by link, which
        // the fallback path relies on.
        val req = okhttp3.Request.Builder().url(YoutubeIds.channelFeedUrl(channel)).build()
        val feed = client.newCall(req).execute().use { RssParser.parse(it.body.byteStream()) }
        val shortLinks = feed.entries.count { "/shorts/" in it.link }
        println("channel feed: ${feed.entries.size} entries, $shortLinks with /shorts/ links")
    }

    @Test
    fun searchAndResolveChannel() = runTest(timeout = 2.minutes) {
        val found = source.searchChannels("Linus Tech Tips")
        println("search: " + found.take(3).map { "${it.name} ${it.remoteId}" })
        assertTrue(found.any { it.remoteId == channel })
        val resolved = source.resolveChannel("@LinusTechTips")
        println("resolved: ${resolved.name} ${resolved.remoteId} avatar=${resolved.avatarUrl != null}")
        assertTrue(resolved.remoteId == channel)
    }

    /**
     * Comments (decision 20): the first page of a recent upload, a second page, and one
     * comment's replies. If this fails while playback works, YouTube wants more for comments.
     */
    @Test
    fun commentsPageAndReplies() = runTest(timeout = 2.minutes) {
        val video = RssFeedFetcher(client).fetch(channel).items.first { it.kind == VideoKind.VIDEO }.remoteId
        val first = source.comments(video)
        println("comments on $video: ${first.comments.size}, disabled=${first.disabled}, next=${first.next != null}")
        first.comments.take(3).forEach { println("  ${it.author} (${it.published}, ${it.likes} likes, ${it.replyCount} replies): ${it.text.take(60)}") }
        assertTrue("no comments", first.comments.isNotEmpty())
        val next = first.next?.let { source.comments(video, it) }
        println("second page: ${next?.comments?.size}")
        val withReplies = first.comments.firstOrNull { it.replies != null }
        val replies = withReplies?.replies?.let { source.comments(video, it) }
        println("replies to ${withReplies?.author}: ${replies?.comments?.size} of ${withReplies?.replyCount}")
        assertNotNull("no second page", next)
    }

    @Test
    fun videoSearchReturnsChannelIds() = runTest(timeout = 2.minutes) {
        val found = source.searchVideos("linus tech tips water cooling")
        println("video search: ${found.size} results; " + found.take(5).map { "${it.kind} ${it.remoteId} [${it.channelId}] ${it.channelName} | ${it.title.take(40)} | ${it.durationSec}s" })
        assertTrue("no results", found.isNotEmpty())
        assertTrue("results without a UC channel id", found.all { YoutubeIds.isChannelId(it.channelId) })
        assertTrue("LTT not among the uploaders", found.any { it.channelId == channel })
    }

    /**
     * NewPipe #13824: streams 403 after ~60 s of playback. Fetches ranges deep into both
     * tracks, past the first minute, and prints what each returns.
     */
    @Test
    fun rangesPastTheFirstMinuteAreServed() = runTest(timeout = 3.minutes) {
        val ladder = listOf(480, 720, 1080, 1440, 2160).associateWith { 60 }
        val profile = DeviceProfile(listOf(DecoderCaps(VideoCodec.VP9, true, ladder)), false, 2160, null, false)
        // A 25-minute upload, so "past a minute" is far from the end.
        val plan = source.resolvePlayback(System.getProperty("smoke.video") ?: "aV81SqB6HQg", profile, PlaybackPrefs()) as PlaybackPlan.Adaptive
        println("deep: '${plan.title}' ${plan.durationSec}s chosen ${plan.chosen.label}")
        val failures = mutableListOf<String>()
        for ((name, track) in listOf("video" to plan.video, "audio" to plan.audio)) {
            val clen = Regex("[?&]clen=(\\d+)").find(track.url)?.groupValues?.get(1)?.toLong() ?: continue
            for (fraction in listOf(0.0, 0.1, 0.3, 0.6, 0.9)) {
                val pos = (clen * fraction).toLong()
                val shaped = StreamRequests.shape(track.url, pos, 65_536, (fraction * 10).toLong())
                val req = okhttp3.Request.Builder().url(shaped.url)
                    .apply { shaped.headers.forEach { (k, v) -> header(k, v) } }
                    .post(shaped.body.toRequestBody()).build()
                val code = client.newCall(req).execute().use { it.code }
                println("  $name @${(fraction * 100).toInt()}% (~${(plan.durationSec * fraction).toInt()}s): HTTP $code")
                if (code !in 200..299) failures += "$name@$fraction=$code"
            }
        }
        assertTrue("deep ranges refused: $failures", failures.isEmpty())

        // The reports describe a refusal about a minute into playback: try again once the URL
        // has been in use for longer than that, like a player that kept buffering.
        Thread.sleep(75_000)
        for ((name, track) in listOf("video" to plan.video, "audio" to plan.audio)) {
            val clen = Regex("[?&]clen=(\\d+)").find(track.url)?.groupValues?.get(1)?.toLong() ?: continue
            val shaped = StreamRequests.shape(track.url, clen / 2, 65_536, 99)
            val req = okhttp3.Request.Builder().url(shaped.url)
                .apply { shaped.headers.forEach { (k, v) -> header(k, v) } }
                .post(shaped.body.toRequestBody()).build()
            val code = client.newCall(req).execute().use { it.code }
            println("  $name after 75 s: HTTP $code")
            if (code !in 200..299) failures += "$name@75s=$code"
        }
        assertTrue("refused after a minute: $failures", failures.isEmpty())
    }

    @Test
    fun streamPlanPicksVp9At4kForAShieldProfile() = runTest(timeout = 2.minutes) {
        val ladder = listOf(480, 720, 1080, 1440, 2160).associateWith { 60 }
        val shield = DeviceProfile(
            decoders = listOf(
                DecoderCaps(VideoCodec.VP9, true, ladder, hdr = true),
                DecoderCaps(VideoCodec.H264, true, ladder),
            ),
            hdrDisplay = false,
            displayLines = 2160,
            throughputKbps = null,
            matchFrameRate = true,
        )
        // "Costa Rica in 4K 60fps HDR": a long-lived 4K upload.
        val plan = source.resolvePlayback("LXb3EKWsInQ", shield, PlaybackPrefs(QualityCap.BEST))
        println("plan: ${plan::class.simpleName} '${plan.title}' ${plan.durationSec}s")
        assertTrue(plan is PlaybackPlan.Adaptive)
        plan as PlaybackPlan.Adaptive
        println("chosen: ${plan.chosen.label} itag=${plan.chosen.id} audio=${plan.audio.codecs}")
        println("options: " + plan.videoOptions.joinToString { it.label })
        assertTrue(plan.chosen.lines >= 2160 && plan.chosen.codec == VideoCodec.VP9)
        assertNotNull(plan.video.dashManifest)

        val capped = source.resolvePlayback("LXb3EKWsInQ", shield, PlaybackPrefs(QualityCap.P1080)) as PlaybackPlan.Adaptive
        println("capped: ${capped.chosen.label}")
        assertTrue(capped.chosen.lines == 1080)

        // The URLs must actually serve media, shaped the way the player's data source does.
        // A 403 here means YouTube wants a PoToken for this client (the app supplies one).
        for ((name, track) in listOf("video" to plan.video, "audio" to plan.audio)) {
            val shaped = StreamRequests.shape(track.url, position = 0, length = 65_536, requestNumber = 0)
            val req = okhttp3.Request.Builder().url(shaped.url)
                .apply { shaped.headers.forEach { (k, v) -> header(k, v) } }
                .post(shaped.body.toRequestBody())
                .build()
            val (code, bytes) = client.newCall(req).execute().use { it.code to (it.body.bytes().size) }
            val clientParam = Regex("[?&]c=([A-Z_]+)").find(track.url)?.groupValues?.get(1)
            println("$name fetch: HTTP $code, $bytes bytes, client=$clientParam")
            assertTrue("$name stream returned HTTP $code", code in 200..299 && bytes == 65_536)
        }
    }
}
