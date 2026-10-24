package com.xblackbytesx.slopstream.data.sync

import android.app.Application
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xblackbytesx.slopstream.FakeSource
import com.xblackbytesx.slopstream.MemorySecretStore
import com.xblackbytesx.slopstream.core.VideoKind
import com.xblackbytesx.slopstream.data.Accounts
import com.xblackbytesx.slopstream.data.Clock
import com.xblackbytesx.slopstream.data.FeedRepository
import com.xblackbytesx.slopstream.data.Settings
import com.xblackbytesx.slopstream.data.Sources
import com.xblackbytesx.slopstream.data.SubscriptionRepository
import com.xblackbytesx.slopstream.data.db.AppDatabase
import com.xblackbytesx.slopstream.data.db.FeedItemEntity
import com.xblackbytesx.slopstream.data.db.SubscriptionEntity
import com.xblackbytesx.slopstream.feed
import com.xblackbytesx.slopstream.item
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Decision 19: the server's lists first, this device fetches only what they lack. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ServerFeedTest {
    private lateinit var db: AppDatabase
    private lateinit var source: FakeSource
    private lateinit var repo: FeedRepository
    private val server = FakeSyncServer()
    private val store = MemorySyncStore()
    private val now = 1_800_000_000_000L
    private val clock = object : Clock() { override fun now() = now }

    @Before
    fun setUp() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        source = FakeSource()
        val sources = Sources(source)
        val accounts = Accounts(MemorySecretStore())
        val subs = SubscriptionRepository(db.subscriptions(), Settings(ctx), sources, clock)
        repo = FeedRepository(
            db.feed(), db.subscriptions(), sources, subs, accounts, Settings(ctx), clock,
            ServerFeed(db, store, { _, _ -> server }, clock),
        )
        store.setToken("ss_t")
        store.update { it.copy(server = "https://s.example/") }
        db.subscriptions().upsert(listOf("A", "B", "C").map { SubscriptionEntity("yt", it, "Channel $it", null, 0, 0, null, "dev") })
        listOf("A", "B", "C").forEach { id -> source.feeds[id] = { feed(id, item("$id-local", id, "2026-10-20T10:00:00Z")) } }
    }

    @After
    fun tearDown() = db.close()

    private fun row(id: String, channel: String, rev: Long, duration: Long? = null, deleted: Boolean = false) = FeedRow(
        rev = rev, source = "yt", remoteId = id, channelId = channel, channelName = "Channel $channel", title = "Video $id",
        publishedAt = now - rev, kind = VideoKind.VIDEO.name, durationSec = duration, deleted = deleted,
    )

    private suspend fun ids(): List<String> {
        val page = db.feed().page("yt", listOf(VideoKind.VIDEO.name)).load(PagingSource.LoadParams.Refresh(null, 100, false))
        return (page as PagingSource.LoadResult.Page).data.map { it.remoteId }.sorted()
    }

    @Test
    fun freshChannelsComeFromTheServerTheRestAreFetchedHere() = runTest {
        server.feedRows += listOf(row("a1", "A", 5), row("b1", "B", 6))
        server.statuses += ChannelStatus("yt", "A", "Channel A!", lastOkAt = now - 60_000, rev = 5)
        // B was fetched by the server, but too long ago; C not at all.
        server.statuses += ChannelStatus("yt", "B", null, lastOkAt = now - 3 * 60 * 60_000L, rev = 6)

        repo.refreshAll()

        assertEquals(listOf("B", "C"), source.fetched.sorted())
        assertEquals(listOf("B-local", "C-local", "a1", "b1"), ids())
        val a = db.subscriptions().get("yt", "A")!!
        assertEquals(5L, a.feedRev)
        assertEquals("renamed from the server's status", "Channel A!", a.name)
        assertEquals(now - 60_000, a.lastRefreshAt)

        // The next pull asks from there.
        repo.pullFromServer()
        assertEquals(5L, server.feedRequests.last().channels["yt:A"])
    }

    @Test
    fun aKnownDurationStaysAndAnEndedStreamGoes() = runTest {
        db.feed().upsert(
            listOf(FeedItemEntity("yt", "a1", "A", "Channel A", "Video a1", now, VideoKind.VIDEO.name, null, 600, false, now)),
        )
        server.feedRows += listOf(row("a1", "A", 5), row("a2", "A", 6))
        server.statuses += ChannelStatus("yt", "A", null, lastOkAt = now, rev = 6)
        repo.pullFromServer()
        assertEquals(600L, db.feed().get("yt", "a1")!!.durationSec)

        server.feedRows += row("a2", "A", 7, deleted = true)
        server.statuses[0] = server.statuses[0].copy(rev = 7)
        repo.pullFromServer()
        assertNull(db.feed().get("yt", "a2"))
        assertEquals(listOf("a1"), ids())
    }

    @Test
    fun anUnreachableServerMeansEverythingIsFetchedHere() = runTest {
        server.feedDown = true
        repo.refreshAll()
        assertEquals(listOf("A", "B", "C"), source.fetched.sorted())
    }

    @Test
    fun pullToRefreshAsksTheServerFirst() = runTest {
        repo.refreshNow()
        assertEquals(1, server.feedRefreshes)
    }
}
