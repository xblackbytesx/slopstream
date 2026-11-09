package com.xblackbytesx.slopstream.data.download

import com.xblackbytesx.slopstream.core.Segmented
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HlsFetcherTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    private val key = ByteArray(16) { (it * 7 + 3).toByte() }
    private val explicitIv = ByteArray(16) { (0xA0 + it).toByte() }
    private val plain = listOf(ByteArray(3_000) { (it % 199).toByte() }, ByteArray(1_234) { (it % 13).toByte() }, ByteArray(77) { 5 })

    /** Segment paths that answer 403 while their count lasts. */
    private val refusals = HashMap<String, Int>()

    /** The playlist served at /chunk.m3u8. */
    private var playlist = ""

    private fun encrypt(data: ByteArray, iv: ByteArray) = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        doFinal(data)
    }

    /** Segment 0 has an explicit IV; 1 and 2 use their media sequence numbers (10, 11) under a second key line. */
    private fun encryptedPlaylist(token: String) = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:6
        #EXT-X-MEDIA-SEQUENCE:9
        #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example/api/key?id=1",IV=0x${explicitIv.joinToString("") { "%02x".format(it) }}
        #EXTINF:6.0,
        seg0.ts?token=$token
        #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example/api/key?id=1"
        #EXTINF:6.0,
        seg1.ts?token=$token
        #EXTINF:2.0,
        seg2.ts?token=$token
        #EXT-X-ENDLIST
    """.trimIndent()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val path = request.url.encodedPath
                refusals[path]?.let { left ->
                    if (left > 0) {
                        refusals[path] = left - 1
                        return MockResponse.Builder().code(403).build()
                    }
                }
                return when (path) {
                    "/chunk.m3u8" -> MockResponse.Builder().code(200).body(playlist).build()
                    "/api/key" -> MockResponse.Builder().code(200).body(Buffer().write(key)).build()
                    "/seg0.ts" -> MockResponse.Builder().code(200).body(Buffer().write(encrypt(plain[0], explicitIv))).build()
                    "/seg1.ts" -> MockResponse.Builder().code(200).body(Buffer().write(encrypt(plain[1], HlsFetcher.sequenceIv(10)))).build()
                    "/seg2.ts" -> MockResponse.Builder().code(200).body(Buffer().write(encrypt(plain[2], HlsFetcher.sequenceIv(11)))).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
        // The key host is rewritten to the mock server, as Sauce+ rewrites floatplane.com's.
        playlist = encryptedPlaylist("T1").replace("https://keys.example", server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() = server.close()

    private val access get() = Segmented(authHosts = setOf("keys.example"), authHeaders = mapOf("Cookie" to "sess=1"))

    private val whole get() = plain.reduce { a, b -> a + b }

    @Test
    fun savesEverySegmentDecryptedInOrder() = runTest {
        val target = File(tmp.root, "v.ts")
        val progress = ArrayList<Long>()
        val size = HlsFetcher(OkHttpClient()).fetch(target, access, url = { server.url("/chunk.m3u8").toString() }) { progress += it }
        assertArrayEquals(whole, target.readBytes())
        assertEquals(whole.size.toLong(), size)
        assertFalse(File(target.path + ".part").exists())
        assertFalse(File(target.path + ".part.hls").exists())
        assertEquals(whole.size.toLong(), progress.last())
        // One key request for the three segments; the tokens stay on the segments.
        assertEquals(1, seen.count { it.url.encodedPath == "/api/key" })
        assertEquals("T1", seen.first { it.url.encodedPath == "/seg1.ts" }.url.queryParameter("token"))
    }

    @Test
    fun credentialsGoToTheAuthHostOnlyAfterTheRewrite() {
        val a = Segmented(authHosts = setOf("www.sauceplus.com"), authHeaders = mapOf("Cookie" to "s=1"), hostRewrites = mapOf("www.floatplane.com" to "www.sauceplus.com"))
        val key = HlsFetcher.request("https://www.floatplane.com/api/v3/delivery/key?id=1", a)
        assertEquals("www.sauceplus.com", key.url.host)
        assertEquals("s=1", key.header("Cookie"))
        val segment = HlsFetcher.request("https://edge.example/seg0.ts?token=T", a)
        assertNull(segment.header("Cookie"))
        // Never over plain http.
        assertNull(HlsFetcher.request("http://www.sauceplus.com/k", a).header("Cookie"))
    }

    @Test
    fun goesOnAfterTheLastWholeSegment() = runTest {
        val target = File(tmp.root, "v.ts")
        // An earlier run wrote segment 0 and part of segment 1, then stopped.
        File(target.path + ".part").writeBytes(plain[0] + plain[1].copyOf(500))
        File(target.path + ".part.hls").writeText("1 ${plain[0].size}")
        HlsFetcher(OkHttpClient()).fetch(target, access, url = { server.url("/chunk.m3u8").toString() })
        assertArrayEquals(whole, target.readBytes())
        assertEquals(0, seen.count { it.url.encodedPath == "/seg0.ts" })
    }

    @Test
    fun anExpiredTokenIsRenewedOnceThenGivesUp() = runTest {
        val target = File(tmp.root, "v.ts")
        refusals["/seg1.ts"] = 1
        val renewals = ArrayList<Boolean>()
        HlsFetcher(OkHttpClient()).fetch(target, access, url = { renew -> renewals += renew; server.url("/chunk.m3u8").toString() })
        assertArrayEquals(whole, target.readBytes())
        assertEquals(listOf(false, true), renewals)

        val again = File(tmp.root, "w.ts")
        refusals["/seg2.ts"] = 5
        try {
            HlsFetcher(OkHttpClient()).fetch(again, access, url = { server.url("/chunk.m3u8").toString() })
            fail("kept refusing")
        } catch (e: RangeFetcher.ExpiredException) {
            assertTrue(File(again.path + ".part").exists())
        }
    }

    @Test
    fun aMasterPlaylistIsFollowedToItsBestVariant() {
        val base = "https://cdn.example/v/master.m3u8".toHttpUrl()
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.64001e,mp4a.40.2"
            low/chunk.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            high/chunk.m3u8
        """.trimIndent()
        assertEquals(HlsPlaylist.Master("https://cdn.example/v/high/chunk.m3u8".toHttpUrl()), HlsPlaylist.parse(master, base))
    }

    @Test
    fun aMapComesFirstAndUnencryptedSegmentsStayAsTheyAre() {
        val base = "https://cdn.example/v/chunk.m3u8?token=T".toHttpUrl()
        val media = HlsPlaylist.parse(
            """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4,
            a.m4s
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4,
            https://other.example/b.m4s
            """.trimIndent(),
            base,
        ) as HlsPlaylist.Media
        assertEquals(listOf("https://cdn.example/v/init.mp4", "https://cdn.example/v/a.m4s", "https://other.example/b.m4s"), media.parts.map { it.url })
        assertTrue(media.parts.all { it.key == null })
        assertEquals(listOf(-1L, 0L, 1L), media.parts.map { it.sequence })
    }

    @Test
    fun sampleAesIsRefusedWithAReason() {
        try {
            HlsPlaylist.parse("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"k\"\n#EXTINF:4,\na.ts", "https://cdn.example/x.m3u8".toHttpUrl())
            fail("not AES-128")
        } catch (e: java.io.IOException) {
            assertEquals("The stream is encrypted with SAMPLE-AES, which can't be saved", e.message)
        }
    }
}
