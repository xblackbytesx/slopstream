package com.xblackbytesx.slopstream.youtube

import com.xblackbytesx.slopstream.core.SourceBlockedException
import com.xblackbytesx.slopstream.core.SourceException
import com.xblackbytesx.slopstream.core.SourceUnavailableException
import com.xblackbytesx.slopstream.core.VideoKind
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class RssFeedFetcherTest {
    private val channel = "UCaaaaaaaaaaaaaaaaaaaaaa"
    private val server = MockWebServer()
    private val routes = HashMap<String, MockResponse>()

    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()
    private fun ok(name: String) = MockResponse.Builder().code(200).body(fixture(name)).build()
    private fun status(code: Int) = MockResponse.Builder().code(code).build()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val q = request.url.query.orEmpty()
                return routes.entries.firstOrNull { q.endsWith(it.key) }?.value ?: status(404)
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun fetcher() = RssFeedFetcher(OkHttpClient(), server.url("/").toString().trimEnd('/'))

    @Test
    fun splitsKindsByPlaylistAndSortsNewestFirst() {
        routes["playlist_id=UULF${channel.substring(2)}"] = ok("uulf.xml")
        routes["playlist_id=UUSH${channel.substring(2)}"] = ok("uush.xml")
        // UULV is missing: a 404 means "no streams", not an error.

        val feed = fetcher().fetch(channel)

        assertEquals("Fixture Channel", feed.channelName)
        assertEquals(listOf("shtAAAAAAA1", "vidAAAAAAA1", "vidAAAAAAA2"), feed.items.map { it.remoteId })
        val byId = feed.items.associateBy { it.remoteId }
        assertEquals(VideoKind.SHORT, byId.getValue("shtAAAAAAA1").kind)
        assertEquals(VideoKind.VIDEO, byId.getValue("vidAAAAAAA1").kind)
        assertEquals("Long video one & more", byId.getValue("vidAAAAAAA1").title)
        assertEquals(Instant.parse("2026-10-20T15:00:05Z"), byId.getValue("vidAAAAAAA1").publishedAt)
    }

    @Test
    fun anIdInBothShortsAndVideosStaysAShort() {
        routes["playlist_id=UULF${channel.substring(2)}"] = ok("uulf.xml")
        routes["playlist_id=UUSH${channel.substring(2)}"] = ok("uush.xml")

        val dup = fetcher().fetch(channel).items.single { it.remoteId == "vidAAAAAAA2" }
        assertEquals(VideoKind.SHORT, dup.kind)
    }

    @Test
    fun withoutSplitPlaylistsTheChannelFeedIsClassifiedByLink() {
        routes["channel_id=$channel"] = ok("channel.xml")

        val feed = fetcher().fetch(channel)
        assertEquals(VideoKind.SHORT, feed.items.single { it.remoteId == "shtAAAAAAA1" }.kind)
        assertEquals(VideoKind.VIDEO, feed.items.single { it.remoteId == "vidAAAAAAA1" }.kind)
    }

    @Test
    fun aChannelWithNoFeedAtAllIsUnavailable() {
        assertThrows(SourceUnavailableException::class.java) { fetcher().fetch(channel) }
    }

    @Test
    fun serverErrorsThrowSoTheLastGoodCopyIsKept() {
        routes["playlist_id=UULF${channel.substring(2)}"] = status(500)
        routes["playlist_id=UUSH${channel.substring(2)}"] = ok("uush.xml")
        val e = assertThrows(SourceException::class.java) { fetcher().fetch(channel) }
        assertTrue(e !is SourceUnavailableException)
    }

    @Test
    fun rateLimitIsBlocked() {
        routes["playlist_id=UULF${channel.substring(2)}"] = status(429)
        assertThrows(SourceBlockedException::class.java) { fetcher().fetch(channel) }
    }

    @Test
    fun malformedXmlIsAnErrorNotAnEmptyFeed() {
        routes["playlist_id=UULF${channel.substring(2)}"] = MockResponse.Builder().code(200).body("<html>oops").build()
        assertThrows(SourceException::class.java) { fetcher().fetch(channel) }
    }
}
