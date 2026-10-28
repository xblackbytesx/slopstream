package com.xblackbytesx.slopstream.data.download

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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class RangeFetcherTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val file = ByteArray(25_000) { (it % 251).toByte() }
    private val ranges = CopyOnWriteArrayList<String>()

    /** Paths that answer 403 until [refusals] runs out. */
    private var refusals = 0

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.headers["Range"].orEmpty()
                ranges += range
                if (request.url.encodedPath == "/old" && refusals-- > 0) return MockResponse.Builder().code(403).build()
                val (from, to) = Regex("bytes=(\\d+)-(\\d+)").find(range)!!.destructured.let { (a, b) -> a.toInt() to minOf(b.toInt(), file.size - 1) }
                if (from >= file.size) return MockResponse.Builder().code(416).build()
                return MockResponse.Builder().code(206).body(Buffer().write(file.copyOfRange(from, to + 1))).build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun fetcher() = RangeFetcher(OkHttpClient(), chunkBytes = 10_000)

    @Test
    fun fetchesInRangesAndRenamesWhenComplete() = runTest {
        val target = File(tmp.root, "v.webm")
        val size = fetcher().fetch(target, file.size.toLong(), url = { server.url("/v").toString() })
        assertEquals(file.size.toLong(), size)
        assertArrayEquals(file, target.readBytes())
        assertFalse(File(target.path + ".part").exists())
        assertEquals(listOf("bytes=0-9999", "bytes=10000-19999", "bytes=20000-24999"), ranges)
    }

    @Test
    fun continuesAPartFileWhereItStopped() = runTest {
        val target = File(tmp.root, "v.webm")
        File(target.path + ".part").writeBytes(file.copyOf(12_000))
        fetcher().fetch(target, file.size.toLong(), url = { server.url("/v").toString() })
        assertArrayEquals(file, target.readBytes())
        assertEquals("bytes=12000-21999", ranges.first())
    }

    @Test
    fun withoutASizeItStopsAtTheFirstShortRange() = runTest {
        val target = File(tmp.root, "a.m4a")
        val size = fetcher().fetch(target, expected = null, url = { server.url("/a").toString() })
        assertEquals(file.size.toLong(), size)
        assertArrayEquals(file, target.readBytes())
        assertEquals(3, ranges.size)
    }

    @Test
    fun anExpiredUrlIsRenewedOnce() = runTest {
        refusals = 1
        val renewals = mutableListOf<Boolean>()
        val target = File(tmp.root, "v.webm")
        fetcher().fetch(target, file.size.toLong(), url = { renew ->
            renewals += renew
            server.url(if (renew) "/new" else "/old").toString()
        })
        assertEquals(listOf(false, true), renewals)
        assertArrayEquals(file, target.readBytes())
    }

    @Test
    fun aRenewedUrlThatIsRefusedTooFails() = runTest {
        refusals = 5
        val target = File(tmp.root, "v.webm")
        try {
            fetcher().fetch(target, file.size.toLong(), url = { server.url("/old").toString() })
            fail("expected ExpiredException")
        } catch (e: RangeFetcher.ExpiredException) {
            assertTrue(e.message!!.contains("403"))
        }
        assertFalse(target.exists())
    }
}
