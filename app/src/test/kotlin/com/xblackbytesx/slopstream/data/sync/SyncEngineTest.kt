package com.xblackbytesx.slopstream.data.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xblackbytesx.slopstream.FakeSource
import com.xblackbytesx.slopstream.MemorySecretStore
import com.xblackbytesx.slopstream.core.Channel
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.data.AccountStatus
import com.xblackbytesx.slopstream.data.Accounts
import com.xblackbytesx.slopstream.data.Clock
import com.xblackbytesx.slopstream.data.Settings
import com.xblackbytesx.slopstream.data.Sources
import com.xblackbytesx.slopstream.data.SubscriptionRepository
import com.xblackbytesx.slopstream.data.WatchRepository
import com.xblackbytesx.slopstream.data.db.AppDatabase
import com.xblackbytesx.slopstream.data.db.VideoRow
import com.xblackbytesx.slopstream.fakeFloatplane
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Two devices of one person against [FakeSyncServer]: the server leads, nothing is lost. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SyncEngineTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val server = FakeSyncServer()
    private val clock = object : Clock() {
        var t = 1_800_000_000_000L
        override fun now() = t
    }
    private val devices = ArrayList<Device>()
    private val person = Person("Sam", "sam@example.com")

    inner class Device(vararg signedIn: SourceKey) {
        val db: AppDatabase = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        val accounts = Accounts(MemorySecretStore(*signedIn))
        val sources = Sources(FakeSource(), fakeFloatplane())
        val subs = SubscriptionRepository(db.subscriptions(), Settings(ctx), sources, clock)
        val watch = WatchRepository(db.watch(), Settings(ctx), clock)
        val store = MemorySyncStore()
        val engine = SyncEngine(db, store, accounts, sources, subs, clock) { _, _ -> server }

        suspend fun connect(replace: Boolean = false) = engine.connect("https://s.example/", "ss_t", person, replace)
        suspend fun unsynced(): Int = db.sync().observeUnsynced().first()
        suspend fun sub(id: String) = db.sync().subscription("yt", id)
        suspend fun follow(id: String, source: SourceKey = SourceKey.YOUTUBE) =
            subs.subscribe(listOf(Channel(source, id, "Channel $id", url = "")))

        init { devices += this }
    }

    @After
    fun tearDown() = devices.forEach { it.db.close() }

    private fun row(id: String) = VideoRow(
        source = "yt", remoteId = id, channelId = "UC1", channelName = "Channel 1", title = "Video $id",
        publishedAt = 1L, kind = "VIDEO", thumbnailUrl = null, durationSec = 600, dateApproximate = false, dateUnknown = false,
        positionMs = null, watchDurationMs = null, watched = null, inWatchLater = false,
    )

    @Test
    fun changesGoUpAndReachTheOtherDevice() = runTest {
        val phone = Device()
        phone.follow("UC1")
        phone.watch.toggleWatchLater(row("v1"))
        phone.watch.savePosition("yt", "v1", 61_000, 600_000)
        phone.connect()
        assertNotNull(phone.engine.sync())
        assertEquals(0, phone.unsynced())
        assertEquals(setOf("yt:UC1"), server.subscriptions.keys)
        assertEquals("Video v1", server.watchState["yt:v1"]?.title)

        val tv = Device()
        tv.connect()
        val outcome = tv.engine.sync()!!
        assertEquals(listOf(SourceKey.YOUTUBE to "UC1"), outcome.newChannels)
        assertNull(tv.sub("UC1")!!.deletedAt)
        assertEquals(61_000L, tv.watch.resumePositionMs("yt", "v1"))
        assertEquals(0, tv.unsynced())
    }

    @Test
    fun theServerLeadsUnlessAChangeIsClearlyNewer() = runTest {
        val phone = Device()
        val tv = Device()
        phone.follow("UC1")
        phone.connect(); phone.engine.sync()
        tv.connect(); tv.engine.sync()

        // The TV favorites it and syncs first.
        clock.t += 10_000
        tv.subs.setFavorite("yt", "UC1", true)
        tv.engine.sync()

        // The phone, which never saw that, unfollows a second later: in doubt, the server's row
        // stays, and the phone takes it.
        clock.t += 1_000
        phone.subs.unsubscribe("yt", "UC1")
        phone.engine.sync()
        val back = phone.sub("UC1")!!
        assertTrue(back.favorite)
        assertNull(back.deletedAt)
        assertEquals(0, phone.unsynced())

        // Clearly later, it wins, and the TV follows.
        clock.t += 60_000
        phone.subs.unsubscribe("yt", "UC1")
        phone.engine.sync()
        tv.engine.sync()
        assertNotNull(tv.sub("UC1")!!.deletedAt)
    }

    @Test
    fun anEditMadeDuringASyncIsNeitherLostNorOverwritten() = runTest {
        val phone = Device()
        phone.follow("UC1")
        phone.connect()
        server.during = { phone.subs.setFavorite("yt", "UC1", true) }
        phone.engine.sync()
        assertTrue("kept", phone.sub("UC1")!!.favorite)
        assertEquals("still to send", 1, phone.unsynced())
        assertFalse(server.subscriptions["yt:UC1"]!!.favorite)

        phone.engine.sync()
        assertTrue(server.subscriptions["yt:UC1"]!!.favorite)
        assertEquals(0, phone.unsynced())
    }

    @Test
    fun aServerThatLostRowsGetsThemBack() = runTest {
        val phone = Device()
        phone.follow("UC1")
        phone.connect(); phone.engine.sync()
        server.subscriptions.clear()
        server.resetNext = true
        phone.engine.sync()
        assertEquals(setOf("yt:UC1"), server.subscriptions.keys)
        assertEquals(0, phone.unsynced())
    }

    @Test
    fun replacingTheLibraryDropsThisDevicesCopy() = runTest {
        server.put(SubscriptionWire(source = "yt", remoteId = "UC9", name = "Server's", favorite = false, addedAt = 1, updatedAt = 1, deviceId = "web"))
        val tv = Device()
        tv.follow("UC1")
        tv.connect(replace = true)
        tv.engine.sync()
        assertNull(tv.sub("UC1"))
        assertEquals("Server's", tv.sub("UC9")!!.name)
        assertEquals(setOf("yt:UC9"), server.subscriptions.keys)
    }

    @Test
    fun oneSignInReachesTheOtherDeviceAndOneSignOutEndsItThere() = runTest {
        val phone = Device(SourceKey.FLOATPLANE)
        phone.connect()
        phone.engine.sync()
        assertEquals("the local sign-in was adopted", "session-fp", server.sessions["fp"]?.first)

        val tv = Device()
        tv.follow("LTT", SourceKey.FLOATPLANE)
        tv.connect()
        val outcome = tv.engine.sync()!!
        assertEquals(listOf(SourceKey.FLOATPLANE), outcome.newSessions)
        assertEquals("session-fp", tv.accounts.secret(SourceKey.FLOATPLANE))
        assertEquals(AccountStatus.SIGNED_IN, tv.accounts.account(SourceKey.FLOATPLANE).status)

        // The service refuses it on the TV: the phone hears it from the server.
        tv.accounts.markExpired(SourceKey.FLOATPLANE)
        tv.engine.reportExpired(SourceKey.FLOATPLANE)
        assertEquals(listOf("fp"), server.expiredReports)
        phone.engine.sync()
        assertEquals(AccountStatus.EXPIRED, phone.accounts.account(SourceKey.FLOATPLANE).status)

        // Signed in again on the phone: the TV is signed in again too.
        phone.accounts.save(SourceKey.FLOATPLANE, "session-fp-renewed", "user")
        phone.engine.sessionSignedIn(SourceKey.FLOATPLANE)
        phone.engine.sync()
        tv.engine.sync()
        assertEquals("session-fp-renewed", tv.accounts.secret(SourceKey.FLOATPLANE))
        assertEquals(AccountStatus.SIGNED_IN, tv.accounts.account(SourceKey.FLOATPLANE).status)

        // Signed out on the phone: gone on the TV, with its account follows.
        phone.accounts.clear(SourceKey.FLOATPLANE)
        phone.engine.sessionSignedOut(SourceKey.FLOATPLANE)
        phone.engine.sync()
        assertNull(server.sessions["fp"])
        tv.engine.sync()
        assertNull(tv.accounts.secret(SourceKey.FLOATPLANE))
        assertNotNull(tv.db.sync().subscription("fp", "LTT")!!.deletedAt)
    }

    @Test
    fun withoutAServerNothingHappens() = runTest {
        val phone = Device(SourceKey.FLOATPLANE)
        phone.follow("UC1")
        assertNull(phone.engine.sync())
        phone.engine.sessionSignedIn(SourceKey.FLOATPLANE)
        assertEquals(0, server.syncs)
        assertTrue(phone.store.current().pending.isEmpty())
    }

    @Test
    fun aRevokedDeviceKeepsItsLibrary() = runTest {
        val phone = Device()
        phone.follow("UC1")
        val revoked = object : SyncApi by server {
            override suspend fun sync(request: SyncRequest): SyncResponse = throw SyncUnauthorizedException()
        }
        val engine = SyncEngine(phone.db, phone.store, phone.accounts, phone.sources, phone.subs, clock) { _, _ -> revoked }
        engine.connect("https://s.example/", "ss_t", person, replace = false)
        assertNull(engine.sync())
        assertTrue(phone.store.current().lastError!!.contains("no longer accepts"))
        assertNotNull(phone.sub("UC1"))
        assertEquals(1, phone.unsynced())
    }
}
