package com.xblackbytesx.slopstream.data

import com.xblackbytesx.slopstream.core.ArchivePage
import com.xblackbytesx.slopstream.core.SourceAuthException
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.core.VideoItem
import com.xblackbytesx.slopstream.core.VideoKind
import com.xblackbytesx.slopstream.data.db.FeedDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Where "Load older" stands for one channel tab. */
data class ArchiveState(
    val loading: Boolean = false,
    /** The channel's first upload of this kind is in the cache. */
    val done: Boolean = false,
    val error: String? = null,
)

/**
 * A channel's older uploads, loaded page by page when the user asks on its channel page
 * (decision 23). Pages go into the cache as archived rows: shown on that channel page only and
 * never pruned. Where the next page starts is kept for the life of the process; after a
 * restart the next load walks past the pages already cached before it adds anything.
 */
@Singleton
class ChannelArchive @Inject constructor(
    private val sources: Sources,
    private val feedDao: FeedDao,
    private val accounts: Accounts,
    private val clock: Clock,
) {
    data class Key(val source: String, val channelId: String, val kind: VideoKind)

    private val cursors = ConcurrentHashMap<Key, ArchivePage.Cursor>()
    private val running = ConcurrentHashMap.newKeySet<Key>()
    private val states = MutableStateFlow<Map<Key, ArchiveState>>(emptyMap())

    fun offers(source: String, kind: VideoKind): Boolean = kind in sources[source].capabilities.archiveKinds

    fun state(key: Key): Flow<ArchiveState> = states.map { it[key] ?: ArchiveState() }.distinctUntilChanged()

    /** Adds the next page of [key]'s older uploads to the cache. A second call while one runs is ignored. */
    suspend fun loadOlder(key: Key) {
        if (states.value[key]?.done == true || !running.add(key)) return
        states.update { it + (key to ArchiveState(loading = true)) }
        var result = ArchiveState()
        try {
            val source = sources[key.source]
            val known = feedDao.idsForChannel(key.source, key.channelId).toHashSet()
            var page = source.channelPage(key.channelId, key.kind, cursors[key])
            var walked = 1
            // Catching up: the newest pages, and what an earlier run loaded, are cached already.
            while (page.items.all { it.remoteId in known } && page.next != null && walked < MAX_WALK) {
                page = source.channelPage(key.channelId, key.kind, page.next)
                walked++
            }
            val now = clock.now()
            val fresh = page.items.filter { it.remoteId !in known }
            feedDao.addArchived(datedBelowTheCache(key, fresh).map { it.toEntity(now, archived = true) })
            val next = page.next
            if (next == null) cursors.remove(key) else cursors[key] = next
            result = ArchiveState(done = next == null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SourceAuthException) {
            accounts.markExpired(SourceKey.fromId(key.source))
            result = ArchiveState(error = "Sign in again to load older videos")
        } catch (e: Exception) {
            result = ArchiveState(error = e.message ?: e.javaClass.simpleName)
        } finally {
            // Also when the screen went away mid-load: the next press starts cleanly.
            states.update { it + (key to result) }
            running.remove(key)
        }
    }

    /**
     * Items without a date (YouTube's Shorts tab) go just below the oldest cached one, a minute
     * apart in page order, so the tab keeps the service's order. Their tiles show no date.
     */
    private suspend fun datedBelowTheCache(key: Key, items: List<VideoItem>): List<VideoItem> {
        if (items.none { it.dateUnknown }) return items
        var floor = Instant.ofEpochMilli(feedDao.oldest(key.source, key.channelId, key.kind.name) ?: clock.now())
        return items.map { item ->
            if (item.dateUnknown) {
                floor = floor.minus(Duration.ofMinutes(1))
                item.copy(publishedAt = floor)
            } else {
                item.also { if (it.publishedAt < floor) floor = it.publishedAt }
            }
        }
    }

    private companion object {
        /** Pages walked per press before giving up for now; the next press goes on from there. */
        const val MAX_WALK = 25
    }
}
