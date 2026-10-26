package com.xblackbytesx.slopstream.data.sync

import androidx.room.withTransaction
import com.xblackbytesx.slopstream.core.SourceException
import com.xblackbytesx.slopstream.data.Sources
import com.xblackbytesx.slopstream.data.db.AppDatabase
import com.xblackbytesx.slopstream.playback.PlayerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durations RSS does not carry (decision 21), looked up from a channel's tabs in the
 * background: one channel at a time, [GAP_MS] apart, never while a player is open. The sync
 * server hands out the channels (`FeedResponse.durationJobs`, one device per channel) and
 * shares the results with every device; without a server, a local refresh offers the
 * channels it found new uploads for.
 */
@Singleton
class Durations @Inject constructor(
    private val sources: Sources,
    private val db: AppDatabase,
    private val store: SyncStore,
    private val apis: SyncApiFactory,
) {
    private val queue = LinkedHashSet<String>()
    private val wake = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val working = Mutex()

    /** Waits while this is true; a player is open by default. Tests replace it. */
    var busy: () -> Boolean = { PlayerSession.info.value.active }
    var gapMs: Long = GAP_MS

    /** YouTube channel ids to look up; already queued ones are not added twice. */
    fun offer(channelIds: Collection<String>) {
        if (channelIds.isEmpty()) return
        synchronized(queue) { queue.addAll(channelIds) }
        wake.tryEmit(Unit)
    }

    /** For the life of the app. */
    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) { wake.collect { drain() } }
    }

    /** Works the queue until it is empty. */
    suspend fun drain() = working.withLock {
        while (true) {
            val channel = synchronized(queue) { queue.firstOrNull()?.also(queue::remove) } ?: break
            while (busy()) delay(BUSY_WAIT_MS)
            work(channel)
            delay(gapMs)
        }
    }

    private suspend fun work(channelId: String) {
        val key = sources.keys.firstOrNull { sources[it].capabilities.enrichesDurations } ?: return
        val found = try {
            sources[key].durations(channelId)
        } catch (e: SourceException) {
            // The server's lease runs out and the channel comes back later, maybe to another device.
            return
        }
        val feed = db.feed()
        db.withTransaction { found.forEach { (id, secs) -> feed.setDuration(key.id, id, secs) } }
        val server = store.current().server ?: return
        val token = withContext(Dispatchers.IO) { store.token() } ?: return
        try {
            apis.create(server, token).reportDurations(FeedDurations(channelId, found))
        } catch (e: SyncException) {
            // Kept here; the server asks again once the lease ends.
        }
    }

    private companion object {
        const val GAP_MS = 2_000L
        const val BUSY_WAIT_MS = 5_000L
    }
}
