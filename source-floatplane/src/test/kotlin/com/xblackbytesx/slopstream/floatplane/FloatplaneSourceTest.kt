package com.xblackbytesx.slopstream.floatplane

import com.xblackbytesx.slopstream.core.SourceAuthException
import com.xblackbytesx.slopstream.core.SourceBlockedException
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.core.SourceUnavailableException
import com.xblackbytesx.slopstream.core.VideoKind
import com.xblackbytesx.slopstream.core.playback.DecoderCaps
import com.xblackbytesx.slopstream.core.playback.DeviceProfile
import com.xblackbytesx.slopstream.core.playback.PlaybackPlan
import com.xblackbytesx.slopstream.core.playback.PlaybackPrefs
import com.xblackbytesx.slopstream.core.playback.QualityCap
import com.xblackbytesx.slopstream.core.playback.VideoCodec
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class FloatplaneSourceTest {
    private val server = MockWebServer()
    private val routes = HashMap<String, MockResponse>()

    /** Routes that answer by query, checked before [routes]. */
    private val handlers = HashMap<String, (RecordedRequest) -> MockResponse>()
    private val seen = CopyOnWriteArrayList<RecordedRequest>()

    private fun fixture(name: String) = javaClass.getResource("/fixtures/floatplane/$name")!!.readText()
    private fun ok(name: String) = MockResponse.Builder().code(200).body(fixture(name)).build()

    /** A fixture whose stream origin is the mock server, for requests the source makes to it. */
    private fun okLocal(name: String) =
        MockResponse.Builder().code(200).body(fixture(name).replace("https://live.example", server.url("/").toString().trimEnd('/'))).build()
    private fun status(code: Int) = MockResponse.Builder().code(code).build()

    private val shield = DeviceProfile(
        decoders = listOf(
            DecoderCaps(VideoCodec.VP9, true, listOf(480, 720, 1080, 1440, 2160).associateWith { 60 }),
            DecoderCaps(VideoCodec.H264, true, listOf(480, 720, 1080, 1440, 2160).associateWith { 60 }),
        ),
        hdrDisplay = false, displayLines = 2160, throughputKbps = null, matchFrameRate = true,
    )

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                return handlers[request.url.encodedPath]?.invoke(request) ?: routes[request.url.encodedPath] ?: status(404)
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun source() = FloatplaneSource(
        FloatplaneApi(OkHttpClient(), { it.header("Authorization", "Bearer test-token") }, server.url("/").toString().trimEnd('/')),
    )

    @Test
    fun subscriptionsAreCreatorsOncePerCreator() = runTest {
        routes["/api/v3/user/subscriptions"] = ok("subscriptions.json")
        routes["/api/v3/creator/info"] = ok("creator.json")

        val subs = source().accountSubscriptions()

        assertEquals(listOf("creatorA"), subs.map { it.remoteId })
        assertEquals("Creator A", subs.single().name)
        assertEquals("https://pbs.example/icon_250.jpeg", subs.single().avatarUrl)
        assertEquals("https://www.floatplane.com/channel/creatora/home", subs.single().url)
        assertTrue(seen.all { it.headers["Authorization"] == "Bearer test-token" })
    }

    @Test
    fun feedKeepsPlayableVideoPostsNamedBySubChannel() = runTest {
        routes["/api/v3/creator/info"] = ok("creator.json")
        routes["/api/v3/content/creator"] = ok("posts.json")
        routes["/api/v3/creator/channels/list"] = ok("channels.json")

        val feed = source().fetchChannelFeed("creatorA")

        // The locked post and the text post are left out; newest first.
        assertEquals(listOf("postSubChan", "postSingle1"), feed.items.map { it.remoteId })
        val sub = feed.items[0]
        assertEquals("Sub Channel", sub.channelName)
        assertEquals("creatorA", sub.channelId)
        assertEquals(null, sub.thumbnailUrl)
        val single = feed.items[1]
        assertEquals("Main Channel", single.channelName)
        assertEquals(755L, single.durationSec)
        assertEquals("https://pbs.example/thumb_1280.jpeg", single.thumbnailUrl)
        assertEquals(Instant.parse("2026-10-20T16:00:00Z"), single.publishedAt)
        val postsQuery = seen.first { it.url.encodedPath == "/api/v3/content/creator" }.url
        assertEquals("creatorA", postsQuery.queryParameter("id"))
        assertEquals("true", postsQuery.queryParameter("hasVideo"))
    }

    @Test
    fun playbackPicksTheBestVariantAndHonoursTheCap() = runTest {
        routes["/api/v3/content/post"] = ok("post_multi.json")
        routes["/api/v3/delivery/info"] = ok("delivery_ondemand.json")

        val best = source().resolvePlayback("postSubChan", shield, PlaybackPrefs()) as PlaybackPlan.Manifest
        assertEquals(best.url, best.variants.first().url)
        assertEquals(best.chosen, best.variants.first().candidate)
        // The key server is the API host and wants the account; the CDN never sees it.
        assertEquals(setOf(server.url("/").host), best.authHosts)
        assertEquals("Bearer test-token", best.authHeaders["Authorization"])
        // A test server stands in for www.floatplane.com here, so keys are rewritten to it;
        // against the real host the map is empty (FloatplaneSite.FLOATPLANE).
        assertEquals(mapOf("www.floatplane.com" to server.url("/").host), best.hostRewrites)
        // Part one is still processing, so the post starts with part two.
        assertEquals("vidM1", seen.last().url.queryParameter("entityId"))
        assertEquals("onDemand", seen.last().url.queryParameter("scenario"))
        assertEquals("https://cdn.example/Videos/vidS1/2160.mp4/chunk.m3u8?token=TOKEN", best.url)
        assertEquals(500L, best.durationSec)
        assertEquals(false, best.live)

        // 1440 is hidden and 720 has no URL: a 1440 cap lands on 1080.
        val capped = source().resolvePlayback("postSubChan", shield, PlaybackPrefs(QualityCap.P1440)) as PlaybackPlan.Manifest
        assertTrue(capped.url, capped.url.contains("/1080.mp4/"))
    }

    @Test
    fun liveUsesTheCreatorsStream() = runTest {
        routes["/api/v3/creator/info"] = ok("creator.json")
        routes["/api/v3/delivery/info"] = okLocal("delivery_live.json")
        routes["/api/video/v1/channel.m3u8"] = MockResponse.Builder().code(200).body("#EXTM3U").build()

        val live = source().resolvePlayback(FloatplaneMapping.liveId("creatorA"), shield, PlaybackPrefs()) as PlaybackPlan.Manifest

        assertEquals(server.url("/api/video/v1/channel.m3u8?allow_source=false&token=TOKEN").toString(), live.url)
        assertTrue(live.live)
        assertEquals("liveA", seen.first { it.url.encodedPath == "/api/v3/delivery/info" }.url.queryParameter("entityId"))
        // The stream URL carries its own token: no account credentials on it.
        assertEquals(null, seen.last().headers["Authorization"])
    }

    @Test
    fun anOnAirStreamLeadsTheFeedAndAnOfflineOneEndsIt() = runTest {
        routes["/api/v3/creator/info"] = ok("creator.json")
        routes["/api/v3/content/creator"] = ok("posts.json")
        routes["/api/v3/creator/channels/list"] = ok("channels.json")
        routes["/api/v3/delivery/info"] = okLocal("delivery_live.json")
        routes["/api/video/v1/channel.m3u8"] = MockResponse.Builder().code(200).body("#EXTM3U").build()

        val on = source().fetchChannelFeed("creatorA")
        assertEquals(listOf("live:creatorA", "postSubChan", "postSingle1"), on.items.map { it.remoteId })
        assertEquals(VideoKind.LIVE, on.items.first().kind)
        assertEquals("Weekly show", on.items.first().title)
        assertEquals(setOf(VideoKind.LIVE), on.completeKinds)

        // Offline, the playlist is 404: no live item, and the feed says so (ends the old one).
        routes["/api/video/v1/channel.m3u8"] = status(404)
        val off = source().fetchChannelFeed("creatorA")
        assertEquals(listOf("postSubChan", "postSingle1"), off.items.map { it.remoteId })
        assertEquals(setOf(VideoKind.LIVE), off.completeKinds)

        // A rate limit on the live check keeps the videos and leaves the old live item alone.
        routes["/api/v3/delivery/info"] = status(429)
        val unknown = source().fetchChannelFeed("creatorA")
        assertEquals(listOf("postSubChan", "postSingle1"), unknown.items.map { it.remoteId })
        assertEquals(emptySet<VideoKind>(), unknown.completeKinds)
    }

    @Test
    fun sauceplusPicksFromTheCatalogAndNeverProbesLive() = runTest {
        routes["/api/v3/creator/list"] = MockResponse.Builder().code(200).body("[${fixture("creator.json")}]").build()
        routes["/api/v3/creator/info"] = ok("creator.json")
        routes["/api/v3/content/creator"] = ok("posts.json")
        routes["/api/v3/creator/channels/list"] = ok("channels.json")
        val sauce = FloatplaneSource(
            FloatplaneApi(OkHttpClient(), SessionAuthorizer(FloatplaneSite.SAUCEPLUS.sessionCookie) { "sid" }, server.url("/").toString().trimEnd('/')),
            FloatplaneSite.SAUCEPLUS,
        )

        assertTrue(sauce.capabilities.catalog && !sauce.capabilities.accountSubscriptions && !sauce.capabilities.hasLive)
        // One membership covers everyone: the account lists nothing, the catalog lists all.
        assertEquals(emptyList<String>(), sauce.accountSubscriptions().map { it.remoteId })
        val catalog = sauce.catalog()
        assertEquals(listOf("creatorA"), catalog.map { it.remoteId })
        assertEquals(SourceKey.SAUCEPLUS, catalog.single().source)
        assertEquals("https://www.sauceplus.com/channel/creatora/home", catalog.single().url)
        assertEquals("", seen.first { it.url.encodedPath == "/api/v3/creator/list" }.url.queryParameter("search"))
        assertEquals("__Host-sp-sess=sid", seen.first().headers["Cookie"])

        val feed = sauce.fetchChannelFeed("creatorA")
        assertEquals(listOf("postSubChan", "postSingle1"), feed.items.map { it.remoteId })
        assertTrue(feed.items.all { it.source == SourceKey.SAUCEPLUS })
        // The creator fixture has a livestream, but live is off on Sauce+: no delivery call.
        assertTrue(seen.none { it.url.encodedPath == "/api/v3/delivery/info" })

        // Its playlists name Floatplane's key server; keys are fetched from the site instead.
        routes["/api/v3/content/post"] = ok("post_multi.json")
        routes["/api/v3/delivery/info"] = ok("delivery_ondemand.json")
        val plan = sauce.resolvePlayback("postSubChan", shield, PlaybackPrefs()) as PlaybackPlan.Manifest
        assertEquals(mapOf("www.floatplane.com" to server.url("/").host), plan.hostRewrites)
        assertEquals(setOf(server.url("/").host), plan.authHosts)
        assertEquals("__Host-sp-sess=sid", plan.authHeaders["Cookie"])
    }

    @Test
    fun commentsMarkTheCreatorAndRepliesStartWithTheInlineOnes() = runTest {
        routes["/api/v3/content/post"] = ok("post_comments.json")
        routes["/api/v3/comment"] = ok("comments.json")
        routes["/api/v3/comment/replies"] = ok("comment_replies.json")
        val s = source()

        val page = s.comments("postC")
        assertEquals(listOf("c1", "c2"), page.comments.map { it.id })
        // Two comments, under a full page: nothing more to load.
        assertNull(page.next)
        val pinned = page.comments[0]
        assertTrue(pinned.pinned && pinned.byUploader)
        assertEquals("Pinned by the creator.", pinned.text)
        assertEquals(40, pinned.likes)
        assertEquals(4, pinned.replyCount)
        assertEquals("https://pbs.example/u1_100.png", pinned.avatarUrl)
        assertEquals(Instant.parse("2026-10-21T09:00:00Z"), pinned.postedAt)
        assertFalse(page.comments[1].byUploader || page.comments[1].pinned)
        assertNull(page.comments[1].replies)
        val commentRequest = seen.single { it.url.encodedPath == "/api/v3/comment" }.url
        assertEquals("postC", commentRequest.queryParameter("blogPost"))
        assertNull(commentRequest.queryParameter("fetchAfter"))

        // The replies that came along are the first page and cost no request.
        val inline = s.comments("postC", pinned.replies)
        assertEquals(listOf("r1", "r2"), inline.comments.map { it.id })
        assertTrue(inline.comments[1].byUploader)
        assertTrue(seen.none { it.url.encodedPath == "/api/v3/comment/replies" })

        val more = s.comments("postC", inline.next)
        assertEquals(listOf("r3", "r4"), more.comments.map { it.id })
        val replyRequest = seen.single { it.url.encodedPath == "/api/v3/comment/replies" }.url
        assertEquals("c1", replyRequest.queryParameter("comment"))
        assertEquals("r2", replyRequest.queryParameter("rid"))
        // All four of the comment's replies are in.
        assertNull(more.next)
    }

    @Test
    fun aFullPageOfCommentsGoesOnAfterItsLastComment() = runTest {
        routes["/api/v3/content/post"] = ok("post_comments.json")
        fun comment(id: String) = """{"id":"$id","user":{"id":"u","username":"u"},"text":"t","postDate":"2026-10-21T09:00:00.000Z","totalReplies":0,"replies":[]}"""
        handlers["/api/v3/comment"] = { r ->
            val ids = if (r.url.queryParameter("fetchAfter") == null) (1..20).map { "a$it" } else listOf("b1")
            MockResponse.Builder().code(200).body(ids.joinToString(",", "[", "]") { comment(it) }).build()
        }
        val s = source()

        val first = s.comments("postC")
        assertEquals(20, first.comments.size)
        assertNotNull(first.next)
        val second = s.comments("postC", first.next)
        assertEquals(listOf("b1"), second.comments.map { it.id })
        assertNull(second.next)
        assertEquals("a20", seen.last { it.url.encodedPath == "/api/v3/comment" }.url.queryParameter("fetchAfter"))
        // The creator is looked up once, on the first page.
        assertEquals(1, seen.count { it.url.encodedPath == "/api/v3/content/post" })
    }

    @Test
    fun downloadsAreTheMp4sOfTheDownloadScenarioBestFirst() = runTest {
        routes["/api/v3/content/post"] = ok("post_download.json")
        routes["/api/v3/delivery/info"] = ok("delivery_download.json")
        val s = source()

        val offer = s.downloadOffer("postD", shield, PlaybackPrefs(QualityCap.P720))
        val delivery = seen.single { it.url.encodedPath == "/api/v3/delivery/info" }.url
        assertEquals("download", delivery.queryParameter("scenario"))
        assertEquals("vidD", delivery.queryParameter("entityId"))
        // The cap is for streaming; the 4K file is not in the plan (disabled), so it is not offered.
        assertEquals(listOf("vidD:1080-avc1", "vidD:360-avc1"), offer.videos.map { it.id })
        val best = offer.videos.first()
        assertEquals("https://edge01.example/Videos/vidD/1080.mp4?token=TOKEN", best.url)
        assertEquals("mp4", best.extension)
        assertFalse(best.sizeExact)
        assertEquals((4_000_000L + 96_000) * 600 / 8, best.sizeBytes)
        // Sound is in every file: no separate audio, and audio only comes out of the smallest.
        assertNull(offer.audio)
        assertEquals("vidD:360-avc1", offer.audioSource!!.id)
        assertEquals(96_000L * 600 / 8, offer.audioOnlySize)
        assertEquals("Creator A", offer.channelName)
        assertEquals("creatorA", offer.channelId)
        assertEquals("https://pbs.example/thumb_640.jpeg", offer.thumbnailUrl)
        assertEquals(600L, offer.durationSec)

        val again = s.downloadStream("postD", "vidD:360-avc1")
        assertEquals("https://edge01.example/Videos/vidD/360.mp4?token=TOKEN", again.url)
        assertEquals("vidD", seen.last { it.url.encodedPath == "/api/v3/delivery/info" }.url.queryParameter("entityId"))
    }

    @Test
    fun aLivestreamHasNoComments() = runTest {
        val page = source().comments(FloatplaneMapping.liveId("creatorA"))
        assertTrue(page.disabled && page.comments.isEmpty())
        assertTrue(seen.isEmpty())
    }

    @Test
    fun errorsMapToWhatTheUserShouldDo() = runTest {
        // Both refusals are 403; only the error names tell signed-out from not-in-plan.
        routes["/api/v3/user/subscriptions"] = MockResponse.Builder().code(403)
            .body("""{"id":"a","errors":[{"id":"a","name":"notLoggedInError","message":"You must be logged-in to access this resource."}],"message":"x"}""").build()
        routes["/api/v3/user/self"] = status(401)
        routes["/api/v3/creator/info"] = status(429)
        routes["/api/v3/content/post"] = MockResponse.Builder().code(403)
            .body("""{"id":"b","errors":[{"id":"b","name":"notSubscribedError","message":"You do not have the necessary subscription to access this item."}],"message":"x"}""").build()
        val s = source()
        assertThrowsSuspend<SourceAuthException> { s.accountSubscriptions() }
        assertThrowsSuspend<SourceAuthException> { FloatplaneApi(OkHttpClient(), {}, server.url("/").toString().trimEnd('/')).self() }
        assertThrowsSuspend<SourceBlockedException> { s.fetchChannelFeed("creatorA") }
        assertThrowsSuspend<SourceUnavailableException> { s.resolvePlayback("x", shield, PlaybackPrefs()) }
    }
}

internal suspend inline fun <reified T : Throwable> assertThrowsSuspend(block: () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return
        throw AssertionError("expected ${T::class.simpleName}, got $e", e)
    }
    throw AssertionError("expected ${T::class.simpleName}, nothing was thrown")
}
