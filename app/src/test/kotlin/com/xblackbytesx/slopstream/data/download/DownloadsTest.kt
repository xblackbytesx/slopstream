package com.xblackbytesx.slopstream.data.download

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xblackbytesx.slopstream.FakeSource
import com.xblackbytesx.slopstream.core.DownloadOffer
import com.xblackbytesx.slopstream.core.DownloadStream
import com.xblackbytesx.slopstream.core.SourceCapabilities
import com.xblackbytesx.slopstream.core.playback.VideoCandidate
import com.xblackbytesx.slopstream.data.Clock
import com.xblackbytesx.slopstream.data.Settings
import com.xblackbytesx.slopstream.data.Sources
import com.xblackbytesx.slopstream.data.db.AppDatabase
import com.xblackbytesx.slopstream.data.db.DownloadState
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The download queue (decision 24) against a real (in-memory) Room database and a test server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DownloadsTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private val server = MockWebServer()
    private val files = mapOf("/video" to ByteArray(30_000) { it.toByte() }, "/audio" to ByteArray(7_000) { (it * 3).toByte() })
    private val source = FakeSource(capabilities = SourceCapabilities(needsLogin = false, hasShorts = true, hasLive = true, canSearchChannels = true, canDownload = true))
    private val clock = object : Clock() { override fun now() = 1_800_000_000_000L }
    private var kicks = 0

    /** Keeps the first half of the file, standing in for Transformer's audio extraction. */
    private var extractor = AudioExtractor { input, output -> output.writeBytes(input.readBytes().copyOf(input.length().toInt() / 2)) }
    private val requested = java.util.concurrent.CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requested += request.url.encodedPath
                val body = files[request.url.encodedPath] ?: return MockResponse.Builder().code(404).build()
                val (from, to) = Regex("bytes=(\\d+)-(\\d+)").find(request.headers["Range"].orEmpty())!!.destructured
                val end = minOf(to.toInt(), body.size - 1)
                return MockResponse.Builder().code(206).body(Buffer().write(body.copyOfRange(from.toInt(), end + 1))).build()
            }
        }
        server.start()
        source.offer = offer(server.url("/video").toString(), server.url("/audio").toString())
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private fun offer(video: String, audio: String) = DownloadOffer(
        videoId = "v1", title = "A video", channelId = "C", channelName = "Channel", durationSec = 60, thumbnailUrl = null,
        videos = listOf(
            DownloadStream("248", video, "webm", 30_000, sizeExact = true, video = VideoCandidate("248", "vp9", 1920, 1080, 30, 0, false)),
        ),
        audio = DownloadStream("251", audio, "webm", 7_000, sizeExact = true),
    )

    private fun downloads(dir: File = tmp.root) = Downloads(
        dir, db.downloads(), Sources(source), Settings(ApplicationProvider.getApplicationContext()), clock, OkHttpClient(),
        DownloadScheduler { kicks++ }, { input, output -> extractor.extract(input, output) }, RangeFetcher(OkHttpClient(), chunkBytes = 8_000),
    )

    private suspend fun queued(d: Downloads) {
        val offer = source.offer!!
        d.start("yt", offer, offer.videos.single())
    }

    @Test
    fun aQueuedDownloadFetchesBothFilesAndPlaysFromThem() = runTest {
        val d = downloads()
        queued(d)
        assertEquals(1, kicks)
        assertEquals(DownloadState.QUEUED.name, db.downloads().get("yt", "v1")!!.state)

        assertTrue(d.runQueue())

        val row = db.downloads().get("yt", "v1")!!
        assertEquals(DownloadState.DONE.name, row.state)
        assertEquals(37_000L, row.sizeBytes)
        assertEquals(37_000L, row.doneBytes)
        assertEquals(clock.now(), row.finishedAt)
        val plan = d.localPlan("yt", "v1")
        assertNotNull(plan)
        assertArrayEquals(files["/video"], File(plan!!.videoPath!!).readBytes())
        assertArrayEquals(files["/audio"], File(plan.audioPath!!).readBytes())
        assertEquals("1080p · VP9", plan.quality!!.label)
    }

    @Test
    fun audioOnlyFetchesTheAudioAloneAndPlaysWithoutVideo() = runTest {
        val d = downloads()
        d.start("yt", source.offer!!, video = null)
        assertTrue(d.runQueue())

        val row = db.downloads().get("yt", "v1")!!
        assertEquals(DownloadState.DONE.name, row.state)
        assertNull(row.videoFile)
        assertEquals(7_000L, row.sizeBytes)
        assertTrue(requested.none { it == "/video" })
        val plan = d.localPlan("yt", "v1")!!
        assertTrue(plan.audioOnly)
        assertNull(plan.quality)
        assertEquals("Channel", plan.channelName)
        assertArrayEquals(files["/audio"], File(plan.audioPath!!).readBytes())
    }

    @Test
    fun aNewQualityReplacesTheDownloadThatWasThere() = runTest {
        val d = downloads()
        queued(d)
        d.runQueue()
        d.start("yt", source.offer!!, video = null)
        d.runQueue()
        // Only the audio file (and nothing of the video one) is left.
        assertEquals(listOf("yt_v1.a.webm"), tmp.root.list()!!.sorted())
        assertTrue(d.localPlan("yt", "v1")!!.audioOnly)
    }

    /** Floatplane's shape: every file has picture and sound, no separate audio. */
    private fun muxed() = offer(server.url("/video").toString(), "").copy(
        audio = null,
        videos = listOf(
            DownloadStream("att:1080", server.url("/video").toString(), "mp4", 30_000, sizeExact = false, video = VideoCandidate("1080", "avc1", 1920, 1080, 30, 0, false), audioSizeBytes = 3_000),
            DownloadStream("att:360", server.url("/audio").toString(), "mp4", 7_000, sizeExact = false, video = VideoCandidate("360", "avc1", 640, 360, 30, 0, false), audioSizeBytes = 1_000),
        ),
    )

    @Test
    fun aOneFileDownloadPlaysThatFile() = runTest {
        source.offer = muxed()
        val d = downloads()
        d.start("yt", source.offer!!, source.offer!!.videos.first())
        assertTrue(d.runQueue())
        val plan = d.localPlan("yt", "v1")!!
        assertFalse(plan.audioOnly)
        assertNull(plan.audioPath)
        assertArrayEquals(files["/video"], File(plan.videoPath!!).readBytes())
        assertEquals(listOf("/video"), requested.distinct())
    }

    @Test
    fun audioOnlyFromAOneFileDownloadKeepsTheSoundOfTheSmallestFile() = runTest {
        source.offer = muxed()
        val d = downloads()
        d.start("yt", source.offer!!, video = null)
        assertEquals(1_000L, db.downloads().get("yt", "v1")!!.sizeBytes)
        assertTrue(d.runQueue())

        val plan = d.localPlan("yt", "v1")!!
        assertTrue(plan.audioOnly)
        assertArrayEquals(files["/audio"]!!.copyOf(3_500), File(plan.audioPath!!).readBytes())
        assertTrue(plan.audioPath!!.endsWith(".m4a"))
        assertEquals(listOf("yt_v1.a.m4a"), tmp.root.list()!!.sorted())
        assertEquals(3_500L, db.downloads().get("yt", "v1")!!.sizeBytes)
    }

    @Test
    fun whenTheSoundCannotBeTakenOutTheWholeFileStays() = runTest {
        source.offer = muxed()
        extractor = AudioExtractor { _, _ -> throw IllegalStateException("no muxer") }
        val d = downloads()
        d.start("yt", source.offer!!, video = null)
        assertTrue(d.runQueue())
        val plan = d.localPlan("yt", "v1")!!
        assertTrue(plan.audioOnly)
        assertArrayEquals(files["/audio"], File(plan.audioPath!!).readBytes())
        assertEquals(listOf("yt_v1.a.m4a"), tmp.root.list()!!.sorted())
    }

    @Test
    fun removingDeletesTheFilesAndTheRow() = runTest {
        val d = downloads()
        queued(d)
        d.runQueue()
        d.remove("yt", "v1")
        assertNull(db.downloads().get("yt", "v1"))
        assertTrue(tmp.root.listFiles()!!.isEmpty())
        assertNull(d.localPlan("yt", "v1"))
    }

    @Test
    fun filesThatAreGoneMakeItFailedNotAPlan() = runTest {
        val d = downloads()
        queued(d)
        d.runQueue()
        tmp.root.listFiles()!!.forEach { it.delete() }
        assertNull(d.localPlan("yt", "v1"))
        assertEquals(DownloadState.FAILED.name, db.downloads().get("yt", "v1")!!.state)
    }

    @Test
    fun aLostNetworkKeepsItQueuedAndTheNextRunFinishes() = runTest {
        val gone = MockWebServer().apply { start() }
        val deadUrl = gone.url("/video").toString()
        gone.close()
        source.offer = offer(deadUrl, server.url("/audio").toString())
        val d = downloads()
        queued(d)

        assertFalse(d.runQueue())
        val waiting = db.downloads().get("yt", "v1")!!
        assertEquals(DownloadState.QUEUED.name, waiting.state)
        assertEquals("Waiting for the network", waiting.error)

        source.offer = offer(server.url("/video").toString(), server.url("/audio").toString())
        assertTrue(d.runQueue())
        assertEquals(DownloadState.DONE.name, db.downloads().get("yt", "v1")!!.state)
    }

    @Test
    fun aDownloadBiggerThanTheFreeSpaceIsRefused() = runTest {
        val d = downloads()
        val offer = source.offer!!
        val huge = offer.videos.single().copy(sizeBytes = d.freeBytes())
        try {
            d.start("yt", offer, huge)
            fail("expected NotEnoughSpaceException")
        } catch (e: NotEnoughSpaceException) {
            assertTrue(e.needed > e.free - Downloads.SPARE_BYTES)
        }
        assertNull(db.downloads().get("yt", "v1"))
        assertEquals(0, kicks)
    }
}
