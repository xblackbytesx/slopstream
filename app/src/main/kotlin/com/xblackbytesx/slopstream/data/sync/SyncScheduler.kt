package com.xblackbytesx.slopstream.data.sync

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.xblackbytesx.slopstream.data.Accounts
import com.xblackbytesx.slopstream.data.FeedRepository
import com.xblackbytesx.slopstream.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When to sync, while the app is in use: whenever the server says something changed (a
 * long-poll, [LongPoll]) and when it comes to the front, [DEBOUNCE_MS] after a local change
 * (so a run of changes, or the progress saved when the player closes, goes up in one round),
 * and after a sign-in or sign-out. Never in the background.
 */
@Singleton
class SyncScheduler @Inject constructor(
    private val engine: SyncEngine,
    private val accounts: Accounts,
    private val feed: FeedRepository,
    db: AppDatabase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val unsynced = db.sync().observeUnsynced()
    private var foreground: Job? = null

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            merge(unsynced.filter { it > 0 }.map { }, engine.requests).debounce(DEBOUNCE_MS).collect { syncNow() }
        }
        scope.launch {
            accounts.expired.collect { key -> engine.reportExpired(key)?.let { follow(it) } }
        }
        scope.launch {
            withContext(Dispatchers.Main) {
                ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        foreground?.cancel()
                        foreground = scope.launch {
                            val poll = LongPoll(engine, { syncNow(); feed.pullFromServer() }, System::currentTimeMillis)
                            while (isActive) {
                                val pause = poll.step()
                                if (pause > 0) delay(pause)
                            }
                        }
                    }

                    override fun onStop(owner: LifecycleOwner) {
                        foreground?.cancel()
                        foreground = null
                    }
                })
            }
        }
    }

    /** Also what "Sync now" in Settings calls. */
    suspend fun syncNow() {
        engine.sync()?.let { follow(it) }
    }

    /** Feeds for channels and services that arrived from the server. */
    private suspend fun follow(outcome: SyncOutcome) {
        outcome.newChannels.groupBy({ it.first }, { it.second }).forEach { (source, ids) ->
            if (source !in outcome.newSessions) runCatching { feed.refreshChannels(source, ids) }
        }
        outcome.newSessions.forEach { runCatching { feed.refreshSource(it) } }
    }

    private companion object {
        const val DEBOUNCE_MS = 5_000L
    }
}

/**
 * The foreground loop's rounds. Waits on the server's long-poll and refreshes (sync, then the
 * feed) when it says something changed, and at least every [FULL_MS]. Without a server, or
 * while it cannot be reached, it falls back to a refresh every [PERIOD_MS] ([RETRY_MS] when
 * connected but failing).
 */
class LongPoll(
    private val engine: SyncEngine,
    private val refresh: suspend () -> Unit,
    private val now: () -> Long,
) {
    private var since = -1L
    private var lastFull = Long.MIN_VALUE / 2

    /** One round; returns how long to pause before the next (0: wait again at once). */
    suspend fun step(): Long {
        val answer = engine.waitForChanges(since)
        val t = now()
        if (answer == null) {
            refresh()
            lastFull = t
            return if (engine.connected()) RETRY_MS else PERIOD_MS
        }
        since = answer.generation
        if (answer.changed || t - lastFull >= FULL_MS) {
            refresh()
            lastFull = t
        }
        return 0
    }

    companion object {
        const val PERIOD_MS = 60_000L
        const val RETRY_MS = 30_000L
        const val FULL_MS = 5 * 60_000L
    }
}
