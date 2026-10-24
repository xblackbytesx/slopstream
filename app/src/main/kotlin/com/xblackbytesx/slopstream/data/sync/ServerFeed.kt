package com.xblackbytesx.slopstream.data.sync

import androidx.room.withTransaction
import com.xblackbytesx.slopstream.data.Clock
import com.xblackbytesx.slopstream.data.FeedRepository
import com.xblackbytesx.slopstream.data.db.AppDatabase
import com.xblackbytesx.slopstream.data.db.FeedItemEntity
import com.xblackbytesx.slopstream.data.db.SubscriptionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The video lists the sync server fetches all day (AGENTS.md decision 19). [pull] fills the
 * feed cache from the server and says which channels this device still has to fetch itself:
 * ones the server has not fetched successfully in [STALE_MS], or never, or that it does not
 * fetch at all (no server, unreachable, a session it lacks). Feed items stay a cache either
 * way, and the last good copy rule holds: nothing here deletes what the server did not say is
 * gone.
 */
@Singleton
class ServerFeed @Inject constructor(
    private val db: AppDatabase,
    private val store: SyncStore,
    private val apis: SyncApiFactory,
    private val clock: Clock,
) {
    private val feed = db.feed()
    private val subs = db.subscriptions()

    private suspend fun api(): SyncApi? {
        val server = store.current().server ?: return null
        val token = withContext(Dispatchers.IO) { store.token() } ?: return null
        return apis.create(server, token)
    }

    /** Pull-to-refresh: the server fetches this person's channels now (it waits a little). */
    suspend fun refresh() {
        val api = api() ?: return
        try {
            api.refreshFeed()
        } catch (e: SyncException) {
            // The pull that follows, or the device itself, covers it.
        }
    }

    /** Fills the cache for [channels]; returns the ones this device must fetch itself. */
    suspend fun pull(channels: List<SubscriptionEntity>): List<SubscriptionEntity> {
        if (channels.isEmpty()) return channels
        val api = api() ?: return channels
        val revs = channels.associate { key(it.source, it.remoteId) to it.feedRev }.toMutableMap()
        var statuses = emptyList<ChannelStatus>()
        try {
            for (page in 0 until MAX_PAGES) {
                val r = api.feed(FeedRequest(channels = revs.toMap()))
                apply(r)
                r.items.forEach { i -> revs.merge(key(i.source, i.channelId), i.rev, ::maxOf) }
                if (!r.more) {
                    statuses = r.channels
                    break
                }
            }
        } catch (e: SyncException) {
            return channels
        }
        db.withTransaction {
            statuses.forEach { s ->
                // Everything up to the channel's rev is here now, including revs of pruned items.
                subs.setFeedRev(s.source, s.remoteId, maxOf(s.rev, revs[key(s.source, s.remoteId)] ?: 0))
                s.name?.takeIf { it.isNotBlank() }?.let { subs.rename(s.source, s.remoteId, it) }
                s.lastOkAt?.let { subs.markRefreshed(s.source, s.remoteId, it, s.lastError) }
            }
        }
        val now = clock.now()
        val fresh = statuses.filter { it.lastOkAt != null && now - it.lastOkAt < STALE_MS }.map { key(it.source, it.remoteId) }.toSet()
        return channels.filter { key(it.source, it.remoteId) !in fresh }
    }

    private suspend fun apply(r: FeedResponse) {
        val now = clock.now()
        db.withTransaction {
            r.items.filter { it.deleted }.forEach { feed.delete(it.source, it.remoteId) }
            r.items.filter { !it.deleted }.groupBy { it.source to it.channelId }.forEach { (ch, rows) ->
                feed.merge(ch.first, ch.second, rows.map { it.toEntity(now) }, FeedRepository.KEEP_PER_KIND)
            }
            // Mid-way through the pages: what arrived so far, per channel.
            if (r.more) {
                r.items.groupBy { it.source to it.channelId }.forEach { (ch, rows) ->
                    subs.setFeedRev(ch.first, ch.second, rows.maxOf { it.rev })
                }
            }
        }
    }

    private fun FeedRow.toEntity(fetchedAt: Long) = FeedItemEntity(
        source = source, remoteId = remoteId, channelId = channelId, channelName = channelName, title = title,
        publishedAt = publishedAt, kind = kind, thumbnailUrl = thumbnailUrl, durationSec = durationSec,
        dateApproximate = false, fetchedAt = fetchedAt,
    )

    companion object {
        /** Older than this on the server, and the device fetches the channel itself. */
        const val STALE_MS = 2 * 60 * 60_000L
        private const val MAX_PAGES = 20

        fun key(source: String, remoteId: String) = "$source:$remoteId"
    }
}
