package com.xblackbytesx.slopstream.youtube.innertube

import com.xblackbytesx.slopstream.core.SourceBlockedException
import com.xblackbytesx.slopstream.core.SourceUnavailableException
import com.xblackbytesx.slopstream.core.playback.DecoderCaps
import com.xblackbytesx.slopstream.core.playback.DeviceProfile
import com.xblackbytesx.slopstream.core.playback.ManifestType
import com.xblackbytesx.slopstream.core.playback.PlaybackPlan
import com.xblackbytesx.slopstream.core.playback.PlaybackPrefs
import com.xblackbytesx.slopstream.core.playback.QualityCap
import com.xblackbytesx.slopstream.core.playback.VideoCodec
import com.xblackbytesx.slopstream.core.playback.VideoDetails
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Decision 30 offline: a fake YouTube that can refuse deep ranges per visitor, bot-check a
 * visitor, and serve or refuse HLS, so every rescue path runs without the network.
 */
class InnertubeResolverTest {
    private val server = MockWebServer()
    private val base get() = server.url("/").toString().trimEnd('/')
    private val http = OkHttpClient()
    private val prefs = PlaybackPrefs(QualityCap.BEST)
    private val profile = DeviceProfile(
        decoders = listOf(
            DecoderCaps(VideoCodec.VP9, true, listOf(720, 1080, 2160).associateWith { 60 }),
            DecoderCaps(VideoCodec.H264, true, listOf(720, 1080).associateWith { 60 }),
        ),
        hdrDisplay = false, displayLines = 2160, throughputKbps = null, matchFrameRate = false,
    )

    /** What the fake does; each field can be changed per test. */
    private var playerStatus: (visitor: String) -> String = { "OK" }
    private var deepServed: (visitor: String) -> Boolean = { true }
    private var segmentServed = true
    private var live = false
    private var visitors = 0
    private val log = mutableListOf<String>()

    private fun resolver() = InnertubeResolver(Innertube(http, baseUrl = base, apiUrl = base), http)

    private fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
                log += "${request.method} ${request.url.encodedPath}"
                return when {
                    path.startsWith("/youtubei/v1/visitor_id") -> {
                        visitors++
                        json("""{"responseContext":{"visitorData":"V$visitors"}}""")
                    }
                    path.startsWith("/youtubei/v1/player") -> {
                        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                        val visitor = body["context"]!!.jsonObject["client"]!!.jsonObject["visitorData"]!!.jsonPrimitive.content
                        json(player(visitor, playerStatus(visitor)))
                    }
                    path.startsWith("/videoplayback") -> {
                        val visitor = Regex("[?&]vis=([^&]+)").find(path)!!.groupValues[1]
                        val range = Regex("[?&]range=(\\d+)").find(path)!!.groupValues[1].toLong()
                        // The start is always served; past it, only to visitors the test allows.
                        if (range == 0L || deepServed(visitor)) body("x".repeat(16)) else code(403)
                    }
                    path == "/hls/master.m3u8" -> body(
                        "#EXTM3U\n" +
                            "#EXT-X-STREAM-INF:BANDWIDTH=900000,CODECS=\"avc1.4d401f,mp4a.40.2\",RESOLUTION=1280x720,FRAME-RATE=30\n$base/hls/720.m3u8\n" +
                            "#EXT-X-STREAM-INF:BANDWIDTH=20000000,CODECS=\"vp09.00.51.08,mp4a.40.2\",RESOLUTION=3840x2160,FRAME-RATE=60\n$base/hls/2160.m3u8\n",
                    )
                    path.endsWith(".m3u8") -> body(
                        "#EXTM3U\n" + (0 until 8).joinToString("") { "#EXTINF:5.0,\n$base/hls/seg/$it.ts\n" } + "#EXT-X-ENDLIST\n",
                    )
                    path.startsWith("/hls/seg/") -> if (segmentServed) body("ts") else code(403)
                    else -> code(404)
                }
            }
        }
        server.start()
    }

    private fun json(s: String) = MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(s).build()
    private fun body(s: String) = MockResponse.Builder().code(200).body(s).build()
    private fun code(c: Int) = MockResponse.Builder().code(c).build()

    private fun player(visitor: String, status: String): String {
        if (status != "OK") {
            val reason = if (status == "LOGIN_REQUIRED") "Sign in to confirm you're not a bot" else "This video is unavailable"
            return """{"playabilityStatus":{"status":"$status","reason":"$reason"}}"""
        }
        fun url(itag: Int) = "$base/videoplayback?itag=$itag&clen=1000000&c=VISIONOS&vis=$visitor"
        fun fmt(itag: Int, mime: String, extra: String) =
            """{"itag":$itag,"url":"${url(itag)}","mimeType":"$mime","bitrate":1000,"contentLength":"1000000",""" +
                """"initRange":{"start":"0","end":"219"},"indexRange":{"start":"220","end":"569"}$extra}"""
        val formats = listOf(
            fmt(315, """video/webm; codecs=\"vp9\"""", ""","width":3840,"height":2160,"fps":60"""),
            fmt(247, """video/webm; codecs=\"vp9\"""", ""","width":1280,"height":720,"fps":30"""),
            fmt(251, """audio/webm; codecs=\"opus\"""", ""","audioChannels":2,"audioSampleRate":"48000","xtags":"${xtags("original")}","audioTrack":{"id":"en.4","audioIsDefault":true}"""),
            fmt(251, """audio/webm; codecs=\"opus\"""", ""","audioChannels":2,"audioSampleRate":"48000","xtags":"${xtags("dubbed-auto")}","audioTrack":{"id":"de.3","audioIsDefault":false}"""),
        ).joinToString(",")
        return """{"playabilityStatus":{"status":"OK"},""" +
            """"videoDetails":{"title":"Test video","lengthSeconds":"600","isLive":$live,"shortDescription":"Line one\n\n0:30 A chapter","viewCount":"1234","channelId":"UCtest","author":"Test channel"},""" +
            """"streamingData":{"adaptiveFormats":[${if (live) "" else formats}],"hlsManifestUrl":"$base/hls/master.m3u8"}}"""
    }

    @After fun stop() = server.close()

    @Test
    fun servedOnTheFirstTryCostsOneVisitorOnePlayerTwoChecks() = runTest {
        start()
        val r = resolver().resolve("abcdefghijk", profile, prefs)
        assertEquals("dash", r.path)
        val plan = r.plan as PlaybackPlan.Adaptive
        assertEquals("315", plan.chosen.id)
        assertEquals("the original track, not the auto-dub", "yt:abcdefghijk:251:en.4", plan.audio.cacheKey)
        assertEquals(VideoDetails("Line one\n\n0:30 A chapter", 1234), plan.details)
        assertEquals(listOf("POST /youtubei/v1/visitor_id", "POST /youtubei/v1/player", "POST /videoplayback", "POST /videoplayback"), log)
    }

    @Test
    fun theVisitorIsReusedAcrossPlays() = runTest {
        start()
        val resolver = resolver()
        resolver.resolve("abcdefghijk", profile, prefs)
        resolver.resolve("bcdefghijkl", profile, prefs)
        assertEquals(1, visitors)
    }

    @Test
    fun aDeepRefusalIsRescuedWithANewVisitor() = runTest {
        deepServed = { it != "V1" }
        start()
        val r = resolver().resolve("abcdefghijk", profile, prefs)
        assertEquals("dash-new-visitor", r.path)
        assertTrue((r.plan as PlaybackPlan.Adaptive).video.url.contains("vis=V2"))
    }

    @Test
    fun refusedForEveryVisitorFallsBackToHls() = runTest {
        deepServed = { false }
        start()
        val r = resolver().resolve("abcdefghijk", profile, prefs)
        assertEquals(r.steps.joinToString("\n"), "hls", r.path)
        val plan = r.plan as PlaybackPlan.Manifest
        assertEquals(ManifestType.HLS, plan.type)
        assertTrue(!plan.live)
        assertEquals(2160, plan.chosen?.lines)
        assertTrue(plan.url.endsWith("/hls/2160.m3u8"))
        assertEquals(listOf(2160, 720), plan.variants.map { it.candidate.lines })
    }

    @Test
    fun refusedEverywhereFailsWithTheStepsTaken() = runTest {
        deepServed = { false }
        segmentServed = false
        start()
        try {
            resolver().resolve("abcdefghijk", profile, prefs)
            fail("should fail")
        } catch (e: InnertubeResolver.Failed) {
            assertTrue(e.steps.joinToString("\n"), e.steps.any { "deep check video 403" in it })
            assertTrue(e.steps.any { "deep segment 403" in it })
        }
    }

    @Test
    fun aBotCheckGetsOneNewVisitor() = runTest {
        playerStatus = { if (it == "V1") "LOGIN_REQUIRED" else "OK" }
        start()
        val r = resolver().resolve("abcdefghijk", profile, prefs)
        assertEquals("dash", r.path)
        assertEquals(2, visitors)
    }

    @Test(expected = SourceBlockedException::class)
    fun aBotCheckForEveryVisitorIsBlocked() = runTest {
        playerStatus = { "LOGIN_REQUIRED" }
        start()
        resolver().resolve("abcdefghijk", profile, prefs)
    }

    @Test(expected = SourceUnavailableException::class)
    fun aRemovedVideoIsUnavailable() = runTest {
        playerStatus = { "ERROR" }
        start()
        resolver().resolve("abcdefghijk", profile, prefs)
    }

    @Test
    fun liveIsTheHlsManifest() = runTest {
        live = true
        start()
        val r = resolver().resolve("abcdefghijk", profile, prefs)
        assertEquals("hls-live", r.path)
        assertTrue((r.plan as PlaybackPlan.Manifest).live)
    }

    @Test
    fun aQualitySwitchTakesThatStreamAndKeepsTheMenu() = runTest {
        start()
        val resolver = resolver()
        val first = resolver.resolve("abcdefghijk", profile, prefs).plan as PlaybackPlan.Adaptive
        val switched = resolver.switch(first, "247").plan as PlaybackPlan.Adaptive
        assertEquals("247", switched.chosen.id)
        assertEquals(first.videoOptions, switched.videoOptions)
    }

    @Test
    fun theManifestIsValidXmlWithTheRanges() = runTest {
        start()
        val plan = resolver().resolve("abcdefghijk", profile, prefs).plan as PlaybackPlan.Adaptive
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(plan.video.dashManifest!!.byteInputStream())
        assertEquals(plan.video.url, doc.getElementsByTagName("BaseURL").item(0).textContent)
        assertEquals("220-569", doc.getElementsByTagName("SegmentBase").item(0).attributes.getNamedItem("indexRange").nodeValue)
        assertEquals("0-219", doc.getElementsByTagName("Initialization").item(0).attributes.getNamedItem("range").nodeValue)
        assertEquals("PT600S", doc.documentElement.getAttribute("mediaPresentationDuration"))
    }

    @Test
    fun aDownloadOffersEveryWholeFileBestFirstWithExactSizes() = runTest {
        start()
        val r = resolver().downloadOffer("abcdefghijk", profile, PlaybackPrefs(QualityCap.P1080))
        assertEquals("files", r.path)
        val offer = r.offer
        // The cap is for streaming: a download is chosen per video.
        assertEquals(listOf("315", "247"), offer.videos.map { it.id })
        assertEquals(listOf("webm", "webm"), offer.videos.map { it.extension })
        assertTrue(offer.videos.all { it.sizeBytes == 1_000_000L && it.sizeExact })
        assertEquals("251:en.4", offer.audio?.id)
        assertEquals("Test channel", offer.channelName)
        assertEquals("UCtest", offer.channelId)
        assertEquals("Line one\n\n0:30 A chapter", offer.details?.description)
        // One answer, and the best pair checked deep, in parallel.
        assertEquals(listOf("POST /youtubei/v1/visitor_id", "POST /youtubei/v1/player", "POST /videoplayback", "POST /videoplayback"), log)
    }

    @Test
    fun aDownloadRefusedDeepGetsANewVisitor() = runTest {
        deepServed = { it != "V1" }
        start()
        val r = resolver().downloadOffer("abcdefghijk", profile, prefs)
        assertEquals("files-new-visitor", r.path)
        assertTrue(r.offer.videos.all { "vis=V2" in it.url } && "vis=V2" in r.offer.audio!!.url)
    }

    @Test
    fun aDownloadRefusedForEveryVisitorFails() = runTest {
        deepServed = { false }
        start()
        try {
            resolver().downloadOffer("abcdefghijk", profile, prefs)
            fail("should fail, so NewPipe gets asked")
        } catch (e: InnertubeResolver.Failed) {
            assertEquals("files refused past the start", e.message)
            // No HLS rescue for a download: it reads files, not segments.
            assertTrue(log.none { "/hls/" in it })
        }
    }

    @Test
    fun aLivestreamIsNoDownload() = runTest {
        live = true
        start()
        try {
            resolver().downloadOffer("abcdefghijk", profile, prefs)
            fail("should fail")
        } catch (e: InnertubeResolver.Failed) {
            assertEquals("a livestream can't be downloaded", e.message)
        }
    }

    @Test
    fun aStreamIsRenewedByTheIdsNewPipeUsesToo() = runTest {
        deepServed = { it != "V1" }
        start()
        val resolver = resolver()
        val video = resolver.downloadStream("abcdefghijk", "247")
        assertEquals("files-new-visitor", video.path)
        assertEquals("247", video.stream.id)
        assertEquals(720, video.stream.video?.lines)
        assertTrue("vis=V2" in video.stream.url)
        val audio = resolver.downloadStream("abcdefghijk", "251:en.4").stream
        assertEquals(null, audio.video)
        assertEquals("webm", audio.extension)
        try {
            resolver.downloadStream("abcdefghijk", "999")
            fail("should fail")
        } catch (e: InnertubeResolver.Failed) {
            assertEquals("stream 999 is no longer offered", e.message)
        }
    }

    @Test
    fun xtagsAudioContent() {
        assertEquals("original", PlayerResponse.audioContent(xtags("original")))
        assertEquals("dubbed-auto", PlayerResponse.audioContent(xtags("dubbed-auto")))
        // A real one without acont (DRC audio, seen 2026-11-08).
        assertEquals(null, PlayerResponse.audioContent("CggKA2RyYxIBMQ"))
    }

    /** YouTube's encoding: XTags { repeated KeyValuePair xtags = 1 }, KeyValuePair { key = 1; value = 2 }. */
    private fun xtags(acont: String): String {
        val kv = byteArrayOf(0x0a, 5) + "acont".toByteArray() + byteArrayOf(0x12, acont.length.toByte()) + acont.toByteArray()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(0x0a, kv.size.toByte()) + kv)
    }
}
