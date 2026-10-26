package com.xblackbytesx.slopstream.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.xblackbytesx.slopstream.core.SourceAuthException
import com.xblackbytesx.slopstream.core.SourceBlockedException
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.core.VideoItem
import com.xblackbytesx.slopstream.core.VideoKind
import com.xblackbytesx.slopstream.data.db.FeedDao
import com.xblackbytesx.slopstream.data.db.FeedItemEntity
import com.xblackbytesx.slopstream.data.db.LibrarySearch
import com.xblackbytesx.slopstream.data.db.SubscriptionDao
import com.xblackbytesx.slopstream.data.db.SubscriptionEntity
import com.xblackbytesx.slopstream.data.sync.Durations
import com.xblackbytesx.slopstream.data.sync.ServerFeed
import com.xblackbytesx.slopstream.data.db.VideoRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

data class RefreshState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val failedChannels: Int = 0,
    /** Set when the service refused us; the refresh stopped early. */
    val blocked: String? = null,
    val finishedAt: Long? = null,
)

@Singleton
class FeedRepository @Inject constructor(
    private val feedDao: FeedDao,
    private val subscriptionDao: SubscriptionDao,
    private val sources: Sources,
    private val subscriptions: SubscriptionRepository,
    private val accounts: Accounts,
    private val settings: Settings,
    private val clock: Clock,
    private val server: ServerFeed,
    private val durations: Durations,
) {
    private val _state = MutableStateFlow(RefreshState())
    val state: StateFlow<RefreshState> = _state.asStateFlow()
    private val refreshLock = Mutex()

    private val pagingConfig = PagingConfig(pageSize = 48, prefetchDistance = 24, enablePlaceholders = false)

    /** The main feed: regular uploads, plus live and premieres when [includeLive]. Never Shorts. */
    fun videos(source: SourceKey, includeLive: Boolean): Flow<PagingData<VideoRow>> {
        val kinds = buildList {
            add(VideoKind.VIDEO.name)
            if (includeLive) { add(VideoKind.LIVE.name); add(VideoKind.UPCOMING.name) }
        }
        return Pager(pagingConfig) { feedDao.page(source.id, kinds) }.flow
    }

    fun shorts(source: SourceKey): Flow<PagingData<VideoRow>> = Pager(pagingConfig) { feedDao.page(source.id, listOf(VideoKind.SHORT.name)) }.flow

    fun channel(source: String, channelId: String, kinds: List<VideoKind>): Flow<PagingData<VideoRow>> =
        Pager(pagingConfig) { feedDao.pageChannel(source, channelId, kinds.map { it.name }) }.flow

    suspend fun shortsList(source: String, limit: Int = 300): List<VideoRow> = feedDao.shorts(source, limit)

    /** Videos of one service already in the library whose title or channel matches, newest first. */
    suspend fun searchLibrary(source: SourceKey, query: String): List<VideoRow> {
        val feed = LibrarySearch.feedQuery(source.id, query)?.let { feedDao.search(it) }.orEmpty()
        val later = LibrarySearch.watchLaterQuery(source.id, query)?.let { feedDao.search(it) }.orEmpty()
        return (feed + later).distinctBy { it.source to it.remoteId }.sortedByDescending { it.publishedAt }
    }

    /** Refreshes when the last full refresh is older than [maxAgeMs]. */
    suspend fun refreshIfStale(maxAgeMs: Long = 15 * 60_000L) {
        if (clock.now() - settings.lastRefreshAt() >= maxAgeMs) refreshAll()
    }

    /**
     * Brings every subscribed channel up to date: first from the sync server's lists when one
     * is connected (decision 19), then by fetching here whatever it does not have fresh,
     * [PARALLEL] at a time. A channel that fails keeps its cached items (last good copy) and
     * records the error; a block from the service stops the whole run, since hammering on
     * makes it worse.
     */
    suspend fun refreshAll(only: List<SubscriptionEntity>? = null) {
        // A full refresh already running covers everything; targeted ones (new channels) wait.
        if (only == null) {
            if (!refreshLock.tryLock()) return
        } else {
            refreshLock.lock()
        }
        try {
            if (only == null) syncAccounts()
            // Favorites first: launches start in order and the semaphore is fair, so they are
            // fetched before the rest, and before a block can stop the run. Services signed out
            // or expired are skipped: their cached items stay (last good copy).
            val followed = (only ?: subscriptionDao.active()).filter { signedIn(SourceKey.fromId(it.source)) }
            val channels = server.pull(followed).sortedByDescending { it.favorite }
            _state.value = RefreshState(running = true, total = channels.size)
            val blocked = AtomicBoolean(false)
            val gate = Semaphore(PARALLEL)
            coroutineScope {
                channels.forEach { sub ->
                    launch {
                        gate.withPermit {
                            if (blocked.get()) return@withPermit
                            val ok = refreshOne(sub, blocked)
                            _state.update { it.copy(done = it.done + 1, failedChannels = it.failedChannels + if (ok) 0 else 1) }
                        }
                    }
                }
            }
            val now = clock.now()
            if (only == null && !blocked.get()) settings.setLastRefreshAt(now)
            _state.update { it.copy(running = false, finishedAt = now) }
        } finally {
            refreshLock.unlock()
        }
    }

    /** Pull-to-refresh and the TV's refresh button: the server fetches first, then the rest. */
    suspend fun refreshNow() {
        server.refresh()
        refreshAll()
    }

    /** The server's lists only, no fetching here: the minute tick while the app is open. */
    suspend fun pullFromServer() {
        if (refreshLock.isLocked) return
        server.pull(subscriptionDao.active().filter { signedIn(SourceKey.fromId(it.source)) })
    }

    private fun signedIn(source: SourceKey): Boolean =
        !sources[source].capabilities.needsLogin || accounts.account(source).status == AccountStatus.SIGNED_IN

    /**
     * Brings account-provided subscriptions up to date before their feeds are fetched. A
     * failure keeps the rows as they are; a rejected session marks the account expired.
     */
    suspend fun syncAccounts() {
        sources.keys.filter { sources[it].capabilities.accountSubscriptions && signedIn(it) }.forEach { key ->
            try {
                subscriptions.syncAccount(key, sources[key].accountSubscriptions())
            } catch (e: CancellationException) {
                throw e
            } catch (e: SourceAuthException) {
                accounts.markExpired(key)
            } catch (e: Exception) {
                // Last good copy: the subscriptions stay as they were.
            }
        }
    }

    /** One service right away, its account subscriptions first (just signed in). */
    suspend fun refreshSource(source: SourceKey) {
        syncAccounts()
        val subs = subscriptionDao.activeIn(source.id)
        if (subs.isNotEmpty()) refreshAll(only = subs)
    }

    /** Refreshes a few channels right away (just subscribed or imported). */
    suspend fun refreshChannels(source: SourceKey, channelIds: List<String>) {
        val subs = channelIds.mapNotNull { subscriptionDao.get(source.id, it) }
        if (subs.isNotEmpty()) refreshAll(only = subs)
    }

    private suspend fun refreshOne(sub: SubscriptionEntity, blocked: AtomicBoolean): Boolean {
        val now = clock.now()
        val key = SourceKey.fromId(sub.source)
        // An earlier channel of this service found the session rejected: skip the rest.
        if (!signedIn(key)) return false
        return try {
            val feed = sources[key].fetchChannelFeed(sub.remoteId)
            val known = feedDao.idsForChannel(sub.source, sub.remoteId).toHashSet()
            feedDao.merge(sub.source, sub.remoteId, feed.items.map { it.toEntity(now) }, KEEP_PER_KIND, feed.completeKinds.mapTo(HashSet()) { it.name })
            feed.channelName?.takeIf { it.isNotBlank() }?.let { subscriptionDao.rename(sub.source, sub.remoteId, it) }
            subscriptionDao.markRefreshed(sub.source, sub.remoteId, now, null)
            // New uploads without durations: looked up in the background. With a sync server
            // connected, it hands those jobs out instead (one device per channel).
            val missing = feed.items.any { it.remoteId !in known && it.durationSec == null && it.kind != VideoKind.SHORT }
            if (missing && sources[key].capabilities.enrichesDurations && !server.connected()) durations.offer(listOf(sub.remoteId))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: SourceAuthException) {
            accounts.markExpired(key)
            subscriptionDao.markRefreshed(sub.source, sub.remoteId, now, e.message)
            false
        } catch (e: SourceBlockedException) {
            blocked.set(true)
            _state.update { it.copy(blocked = e.message) }
            subscriptionDao.markRefreshed(sub.source, sub.remoteId, now, e.message)
            false
        } catch (e: Exception) {
            subscriptionDao.markRefreshed(sub.source, sub.remoteId, now, e.message ?: e.javaClass.simpleName)
            false
        }
    }

    suspend fun rememberDuration(source: String, remoteId: String, durationSec: Long) {
        if (durationSec > 0) feedDao.setDuration(source, remoteId, durationSec)
    }

    private fun VideoItem.toEntity(fetchedAt: Long) = FeedItemEntity(
        source = source.id,
        remoteId = remoteId,
        channelId = channelId,
        channelName = channelName,
        title = title,
        publishedAt = publishedAt.toEpochMilli(),
        kind = kind.name,
        thumbnailUrl = thumbnailUrl,
        durationSec = durationSec,
        dateApproximate = dateApproximate,
        fetchedAt = fetchedAt,
    )

    companion object {
        /** Concurrent channel fetches: quick on 300 subscriptions, gentle enough for YouTube. */
        const val PARALLEL = 6
        const val KEEP_PER_KIND = 200
    }
}
