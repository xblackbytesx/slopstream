package com.xblackbytesx.slopstream.data

import android.app.Application
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xblackbytesx.slopstream.FakeSource
import com.xblackbytesx.slopstream.MemorySecretStore
import com.xblackbytesx.slopstream.core.SourceBlockedException
import com.xblackbytesx.slopstream.core.VideoKind
import com.xblackbytesx.slopstream.data.db.AppDatabase
import com.xblackbytesx.slopstream.data.db.SubscriptionEntity
import com.xblackbytesx.slopstream.data.db.VideoRow
import com.xblackbytesx.slopstream.item
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** "Load older" on a channel page (decision 23), against a real (in-memory) Room database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChannelArchiveTest {
    private lateinit var db: AppDatabase
    private lateinit var source: FakeSource
    private val clock = object : Clock() { override fun now() = 1_800_000_000_000L }
    private val key = ChannelArchive.Key("yt", "A", VideoKind.VIDEO)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        source = FakeSource()
        // Pages as the service lists them, newest first: the first is what RSS already gave us.
        source.pagesOf["A"] = listOf(
            listOf(item("new1", "A", "2026-10-20T10:00:00Z"), item("new2", "A", "2026-10-10T10:00:00Z")),
            listOf(item("old1", "A", "2025-06-01T10:00:00Z"), item("old2", "A", "2025-01-01T10:00:00Z")),
            listOf(item("old3", "A", "2023-03-01T10:00:00Z")),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun archive() = ChannelArchive(Sources(source), db.feed(), Accounts(MemorySecretStore()), clock)

    private suspend fun cached() {
        db.subscriptions().upsert(listOf(SubscriptionEntity("yt", "A", "Channel A", null, 0, 0, null, "dev")))
        db.feed().merge("yt", "A", source.pagesOf.getValue("A")[0].map { it.toEntity(clock.now()) }, FeedRepository.KEEP_PER_KIND)
    }

    private suspend fun load(paging: PagingSource<Int, VideoRow>): List<VideoRow> =
        (paging.load(PagingSource.LoadParams.Refresh(null, 500, false)) as PagingSource.LoadResult.Page).data

    private suspend fun channelPage() = load(db.feed().pageChannel("yt", "A", listOf("VIDEO"))).map { it.remoteId }
    private suspend fun mainFeed() = load(db.feed().page("yt", listOf("VIDEO"))).map { it.remoteId }

    @Test
    fun pagesBackToTheFirstUploadOnTheChannelPageOnly() = runTest {
        cached()
        val archive = archive()

        archive.loadOlder(key)
        // The first page was cached already, so the press walked past it.
        assertEquals(listOf(0, 1), source.pageCalls)
        assertEquals(listOf("new1", "new2", "old1", "old2"), channelPage())
        assertFalse(archive.state(key).first().done)

        archive.loadOlder(key)
        assertEquals(listOf("new1", "new2", "old1", "old2", "old3"), channelPage())
        assertTrue(archive.state(key).first().done)

        // The main feed stays "what's new".
        assertEquals(listOf("new1", "new2"), mainFeed())

        // Done means done: no more requests.
        archive.loadOlder(key)
        assertEquals(listOf(0, 1, 2), source.pageCalls)
    }

    @Test
    fun afterARestartTheNextPressWalksPastWhatIsCached() = runTest {
        cached()
        archive().loadOlder(key)
        source.pageCalls.clear()

        // A new process: no cursor, but old1 and old2 are cached.
        archive().loadOlder(key)
        assertEquals(listOf(0, 1, 2), source.pageCalls)
        assertEquals(listOf("new1", "new2", "old1", "old2", "old3"), channelPage())
    }

    @Test
    fun refreshesNeverPruneOrDropArchivedItems() = runTest {
        cached()
        archive().loadOlder(key)
        // A refresh with a tiny cap and a complete list that lacks the old ones.
        val fresh = listOf(item("new0", "A", "2026-10-25T10:00:00Z").toEntity(clock.now()))
        db.feed().merge("yt", "A", fresh, keepPerKind = 1, completeKinds = setOf("VIDEO"))
        assertEquals(listOf("new0", "old1", "old2"), channelPage())

        // A refresh that lists an archived item leaves it archived.
        db.feed().merge("yt", "A", listOf(item("old1", "A", "2025-06-01T10:00:00Z").toEntity(clock.now())), keepPerKind = 1)
        assertTrue(db.feed().get("yt", "old1")!!.archived)
        assertEquals(listOf("new0"), mainFeed())
    }

    @Test
    fun undatedShortsGoBelowTheCacheInPageOrder() = runTest {
        val shorts = ChannelArchive.Key("yt", "A", VideoKind.SHORT)
        db.subscriptions().upsert(listOf(SubscriptionEntity("yt", "A", "Channel A", null, 0, 0, null, "dev")))
        // RSS dated the newest Short; YouTube's Shorts tab dates none of them.
        val rss = item("s1", "A", "2026-10-20T10:00:00Z", VideoKind.SHORT)
        db.feed().merge("yt", "A", listOf(rss.toEntity(clock.now())), FeedRepository.KEEP_PER_KIND)
        fun undated(id: String) = item(id, "A", "2026-10-28T00:00:00Z", VideoKind.SHORT).copy(dateUnknown = true)
        source.pagesOf["A"] = listOf(listOf(undated("s1"), undated("s2"), undated("s3")))

        archive().loadOlder(shorts)
        val tab = load(db.feed().pageChannel("yt", "A", listOf("SHORT")))
        assertEquals(listOf("s1", "s2", "s3"), tab.map { it.remoteId })
        assertEquals(listOf(false, true, true), tab.map { it.dateUnknown })
        assertTrue(tab[1].publishedAt < Instant.parse("2026-10-20T10:00:00Z").toEpochMilli())
    }

    @Test
    fun aRefusalIsShownAndTheNextPressRetries() = runTest {
        cached()
        val archive = archive()
        source.pageError = SourceBlockedException("YouTube asked for a captcha; try again later")
        archive.loadOlder(key)
        val failed = archive.state(key).first()
        assertEquals("YouTube asked for a captcha; try again later", failed.error)
        assertFalse(failed.loading)

        source.pageError = null
        archive.loadOlder(key)
        assertNull(archive.state(key).first().error)
        assertEquals(listOf("new1", "new2", "old1", "old2"), channelPage())
    }
}
