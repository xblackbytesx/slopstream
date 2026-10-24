package com.xblackbytesx.slopstream.data

import android.app.Application
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xblackbytesx.slopstream.FakeSource
import com.xblackbytesx.slopstream.MemorySecretStore
import com.xblackbytesx.slopstream.core.Channel
import com.xblackbytesx.slopstream.core.SourceAuthException
import com.xblackbytesx.slopstream.core.SourceBlockedException
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.core.VideoKind
import com.xblackbytesx.slopstream.data.db.AppDatabase
import com.xblackbytesx.slopstream.data.db.SubscriptionEntity
import com.xblackbytesx.slopstream.data.db.VideoRow
import com.xblackbytesx.slopstream.data.sync.MemorySyncStore
import com.xblackbytesx.slopstream.data.sync.ServerFeed
import com.xblackbytesx.slopstream.fakeFloatplane
import com.xblackbytesx.slopstream.feed
import com.xblackbytesx.slopstream.item
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The feed rules from AGENTS.md, against a real (in-memory) Room database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FeedRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var source: FakeSource
    private lateinit var floatplane: FakeSource
    private lateinit var sources: Sources
    private lateinit var accounts: Accounts
    private lateinit var repo: FeedRepository
    private val clock = object : Clock() { override fun now() = 1_800_000_000_000L }

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        source = FakeSource()
        floatplane = fakeFloatplane()
        sources = Sources(source, floatplane)
        accounts = Accounts(MemorySecretStore(SourceKey.FLOATPLANE))
        // No sync server: every channel is fetched here, as before decision 19.
        val noServer = ServerFeed(db, MemorySyncStore(), { _, _ -> error("not connected") }, clock)
        repo = FeedRepository(db.feed(), db.subscriptions(), sources, subscriptions(), accounts, Settings(ctx), clock, noServer)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun subscribe(vararg ids: String) = db.subscriptions().upsert(
        ids.map { SubscriptionEntity("yt", it, "Channel $it", null, 0, 0, null, "dev") },
    )

    private suspend fun page(kinds: List<VideoKind>, source: SourceKey = SourceKey.YOUTUBE): List<VideoRow> {
        val result = db.feed().page(source.id, kinds.map { it.name }).load(PagingSource.LoadParams.Refresh(null, 500, false))
        return (result as PagingSource.LoadResult.Page).data
    }

    @Test
    fun videosAreNewestFirstAcrossChannelsAndShortsStayOut() = runTest {
        subscribe("A", "B")
        source.feeds["A"] = { feed("A", item("a1", "A", "2026-10-20T10:00:00Z"), item("as", "A", "2026-10-21T10:00:00Z", VideoKind.SHORT)) }
        source.feeds["B"] = { feed("B", item("b1", "B", "2026-10-21T09:00:00Z"), item("bl", "B", "2026-10-19T09:00:00Z", VideoKind.LIVE)) }

        repo.refreshAll()

        assertEquals(listOf("b1", "a1"), page(listOf(VideoKind.VIDEO)).map { it.remoteId })
        assertEquals(listOf("b1", "a1", "bl"), page(listOf(VideoKind.VIDEO, VideoKind.LIVE, VideoKind.UPCOMING)).map { it.remoteId })
        assertEquals(listOf("as"), page(listOf(VideoKind.SHORT)).map { it.remoteId })
    }

    @Test
    fun aFailedRefreshKeepsTheLastGoodCopy() = runTest {
        subscribe("A")
        source.feeds["A"] = { feed("A", item("a1", "A", "2026-10-20T10:00:00Z")) }
        repo.refreshAll(only = db.subscriptions().active())

        source.feeds["A"] = { error("network down") }
        repo.refreshAll(only = db.subscriptions().active())

        assertEquals(listOf("a1"), page(listOf(VideoKind.VIDEO)).map { it.remoteId })
        assertNotNull(db.subscriptions().get("yt", "A")?.lastError)
        assertEquals(1, repo.state.value.failedChannels)
    }

    @Test
    fun olderItemsSurviveAFeedThatOnlyReturnsTheLatest() = runTest {
        subscribe("A")
        source.feeds["A"] = { feed("A", item("old", "A", "2026-09-01T10:00:00Z", duration = 600)) }
        repo.refreshAll(only = db.subscriptions().active())
        source.feeds["A"] = { feed("A", item("new", "A", "2026-10-20T10:00:00Z")) }
        repo.refreshAll(only = db.subscriptions().active())

        assertEquals(listOf("new", "old"), page(listOf(VideoKind.VIDEO)).map { it.remoteId })
    }

    @Test
    fun aKnownDurationIsNotWipedByAFeedWithout() = runTest {
        subscribe("A")
        source.feeds["A"] = { feed("A", item("a1", "A", "2026-10-20T10:00:00Z")) }
        repo.refreshAll(only = db.subscriptions().active())
        repo.rememberDuration("yt", "a1", 754)
        repo.refreshAll(only = db.subscriptions().active())

        assertEquals(754L, page(listOf(VideoKind.VIDEO)).single().durationSec)
    }

    @Test
    fun unsubscribingHidesTheChannelWithATombstone() = runTest {
        subscribe("A", "B")
        source.feeds["A"] = { feed("A", item("a1", "A", "2026-10-20T10:00:00Z")) }
        source.feeds["B"] = { feed("B", item("b1", "B", "2026-10-21T10:00:00Z")) }
        repo.refreshAll()

        subscriptions().unsubscribe("yt", "B")

        assertEquals(listOf("a1"), page(listOf(VideoKind.VIDEO)).map { it.remoteId })
        val tomb = db.subscriptions().get("yt", "B")
        assertEquals(clock.now(), tomb?.deletedAt)
        assertEquals(clock.now(), tomb?.updatedAt)
    }

    @Test
    fun aBlockStopsTheRunAndIsReported() = runTest {
        val ids = (1..20).map { "C$it" }.toTypedArray()
        subscribe(*ids)
        ids.forEach { id -> source.feeds[id] = { throw SourceBlockedException("rate limited") } }

        repo.refreshAll()

        assertEquals("rate limited", repo.state.value.blocked)
        assertTrue("kept fetching after the block: ${source.fetched.size}", source.fetched.size < ids.size)
        assertNull(repo.state.value.takeIf { it.running })
    }

    @Test
    fun pruneKeepsTheNewestPerKind() = runTest {
        subscribe("A")
        val many = (1..(FeedRepository.KEEP_PER_KIND + 5)).map { i -> item("v$i", "A", "2026-01-01T00:00:00Z".let { java.time.Instant.parse(it).plusSeconds(i * 60L).toString() }) }
        source.feeds["A"] = { feed("A", *many.toTypedArray()) }
        repo.refreshAll()

        val kept = page(listOf(VideoKind.VIDEO))
        assertEquals(FeedRepository.KEEP_PER_KIND, kept.size)
        assertEquals("v${FeedRepository.KEEP_PER_KIND + 5}", kept.first().remoteId)
    }

    private fun subscriptions() =
        SubscriptionRepository(db.subscriptions(), Settings(ApplicationProvider.getApplicationContext()), Sources(source), clock)

    private fun creator(id: String) = Channel(SourceKey.FLOATPLANE, id, "Creator $id", "https://www.floatplane.com/channel/$id/home")

    private fun fpItem(id: String, creator: String, at: String, kind: VideoKind = VideoKind.VIDEO) =
        item(id, creator, at, kind, source = SourceKey.FLOATPLANE)

    @Test
    fun favoritesAreFetchedFirstAndTheFlagIsSynced() = runTest {
        val ids = (1..10).map { "C$it" }.toTypedArray()
        subscribe(*ids)
        ids.forEach { id -> source.feeds[id] = { feed(id) } }
        subscriptions().setFavorite("yt", "C9", true)

        repo.refreshAll()

        assertEquals("C9", source.fetched.first())
        val row = db.subscriptions().get("yt", "C9")!!
        assertTrue(row.favorite)
        assertEquals(clock.now(), row.updatedAt)
    }

    @Test
    fun librarySearchNeedsEveryWordAndOnlyCoversTheLibrary() = runTest {
        subscribe("A", "B")
        source.feeds["A"] = {
            feed(
                "A",
                item("a1", "A", "2026-10-20T10:00:00Z").copy(title = "Building a home server"),
                item("a2", "A", "2026-10-21T10:00:00Z").copy(title = "Server rack tour"),
                item("a3", "A", "2026-10-22T10:00:00Z").copy(title = "100% CPU_load explained"),
            )
        }
        source.feeds["B"] = { feed("B", item("b1", "B", "2026-10-22T09:00:00Z").copy(title = "Home cooking", channelName = "Wolfgang's Kitchen")) }
        repo.refreshAll()

        assertEquals(listOf("a1"), repo.searchLibrary(SourceKey.YOUTUBE, "home SERVER").map { it.remoteId })
        assertEquals(listOf("a2", "a1"), repo.searchLibrary(SourceKey.YOUTUBE, "server").map { it.remoteId })
        // Channel names match too.
        assertEquals(listOf("b1"), repo.searchLibrary(SourceKey.YOUTUBE, "wolfgang").map { it.remoteId })
        // % and _ are literal characters, not wildcards.
        assertEquals(listOf("a3"), repo.searchLibrary(SourceKey.YOUTUBE, "100%").map { it.remoteId })
        assertEquals(listOf("a3"), repo.searchLibrary(SourceKey.YOUTUBE, "CPU_").map { it.remoteId })
        assertEquals(emptyList<String>(), repo.searchLibrary(SourceKey.YOUTUBE, "r_ck").map { it.remoteId })

        // Unsubscribing hides the feed items, but Watch later keeps what was saved.
        val watch = WatchRepository(db.watch(), Settings(ApplicationProvider.getApplicationContext()), clock)
        watch.toggleWatchLater(repo.searchLibrary(SourceKey.YOUTUBE, "cooking").single())
        subscriptions().unsubscribe("yt", "B")
        repo.searchLibrary(SourceKey.YOUTUBE, "home").let { rows ->
            assertEquals(listOf("b1", "a1"), rows.map { it.remoteId })
            assertTrue(rows.first().inWatchLater)
        }
    }
    @Test
    fun accountSubscriptionsMirrorTheAccount() = runTest {
        floatplane.account = { listOf(creator("LTT"), creator("GN")) }
        floatplane.feeds["LTT"] = { feed("LTT", fpItem("p1", "LTT", "2026-10-22T10:00:00Z")) }
        floatplane.feeds["GN"] = { feed("GN", fpItem("p2", "GN", "2026-10-21T10:00:00Z")) }

        repo.refreshAll()

        assertEquals(listOf("GN", "LTT"), db.subscriptions().activeIn("fp").map { it.remoteId }.sorted())
        assertEquals(listOf("p1", "p2"), page(listOf(VideoKind.VIDEO), SourceKey.FLOATPLANE).map { it.remoteId })

        // The plan for GN lapsed: its row is tombstoned, the favorite flag kept for its return.
        subscriptions().setFavorite("fp", "GN", true)
        floatplane.account = { listOf(creator("LTT")) }
        repo.refreshAll()
        assertEquals(listOf("LTT"), db.subscriptions().activeIn("fp").map { it.remoteId })
        assertEquals(listOf("p1"), page(listOf(VideoKind.VIDEO), SourceKey.FLOATPLANE).map { it.remoteId })
        floatplane.account = { listOf(creator("LTT"), creator("GN")) }
        repo.refreshAll()
        assertTrue(db.subscriptions().get("fp", "GN")!!.let { it.deletedAt == null && it.favorite })
    }

    @Test
    fun servicesNeverMix() = runTest {
        subscribe("A")
        source.feeds["A"] = { feed("A", item("y1", "A", "2026-10-20T10:00:00Z").copy(title = "Server build")) }
        floatplane.account = { listOf(creator("LTT")) }
        floatplane.feeds["LTT"] = { feed("LTT", fpItem("p1", "LTT", "2026-10-22T10:00:00Z").copy(title = "Server tour")) }

        repo.refreshAll()

        assertEquals(listOf("y1"), page(listOf(VideoKind.VIDEO)).map { it.remoteId })
        assertEquals(listOf("p1"), page(listOf(VideoKind.VIDEO), SourceKey.FLOATPLANE).map { it.remoteId })
        assertEquals(listOf("y1"), repo.searchLibrary(SourceKey.YOUTUBE, "server").map { it.remoteId })
        assertEquals(listOf("p1"), repo.searchLibrary(SourceKey.FLOATPLANE, "server").map { it.remoteId })
    }

    @Test
    fun aRejectedSessionExpiresTheAccountAndSparesTheOtherServices() = runTest {
        subscribe("A")
        source.feeds["A"] = { feed("A", item("y1", "A", "2026-10-20T10:00:00Z")) }
        floatplane.account = { listOf(creator("LTT"), creator("GN")) }
        floatplane.feeds["LTT"] = { feed("LTT", fpItem("p1", "LTT", "2026-10-22T10:00:00Z")) }
        floatplane.feeds["GN"] = { feed("GN", fpItem("p2", "GN", "2026-10-21T10:00:00Z")) }
        repo.refreshAll()

        floatplane.account = { throw SourceAuthException("signed out") }
        floatplane.fetched.clear()
        source.fetched.clear()
        repo.refreshAll()

        assertEquals(AccountStatus.EXPIRED, accounts.account(SourceKey.FLOATPLANE).status)
        // No Floatplane requests while expired; YouTube refreshes as usual; the cache stays.
        assertEquals(emptyList<String>(), floatplane.fetched)
        assertEquals(listOf("A"), source.fetched)
        assertEquals(listOf("p1", "p2"), page(listOf(VideoKind.VIDEO), SourceKey.FLOATPLANE).map { it.remoteId })
        assertEquals(2, db.subscriptions().activeIn("fp").size)
    }

    @Test
    fun aSignedOutServiceIsLeftAlone() = runTest {
        accounts.clear(SourceKey.FLOATPLANE)
        floatplane.account = { error("must not be asked") }
        db.subscriptions().upsert(listOf(SubscriptionEntity("fp", "LTT", "LTT", null, 0, 0, null, "dev")))

        repo.refreshAll()

        assertEquals(emptyList<String>(), floatplane.fetched)
        assertNull(db.subscriptions().get("fp", "LTT")!!.lastError)
    }

    @Test
    fun anEndedLivestreamLeavesTheFeed() = runTest {
        floatplane.account = { listOf(creator("LTT")) }
        floatplane.feeds["LTT"] = {
            feed("LTT", fpItem("live:LTT", "LTT", "2026-10-23T02:00:00Z", VideoKind.LIVE), fpItem("p1", "LTT", "2026-10-22T10:00:00Z"))
                .copy(completeKinds = setOf(VideoKind.LIVE))
        }
        repo.refreshAll()
        assertEquals(listOf("live:LTT", "p1"), page(listOf(VideoKind.VIDEO, VideoKind.LIVE), SourceKey.FLOATPLANE).map { it.remoteId })

        // Live check unanswered: the item stays.
        floatplane.feeds["LTT"] = { feed("LTT", fpItem("p1", "LTT", "2026-10-22T10:00:00Z")) }
        repo.refreshAll()
        assertEquals(listOf("live:LTT", "p1"), page(listOf(VideoKind.VIDEO, VideoKind.LIVE), SourceKey.FLOATPLANE).map { it.remoteId })

        // Offline for sure: it goes.
        floatplane.feeds["LTT"] = { feed("LTT", fpItem("p1", "LTT", "2026-10-22T10:00:00Z")).copy(completeKinds = setOf(VideoKind.LIVE)) }
        repo.refreshAll()
        assertEquals(listOf("p1"), page(listOf(VideoKind.VIDEO, VideoKind.LIVE), SourceKey.FLOATPLANE).map { it.remoteId })
    }
}
