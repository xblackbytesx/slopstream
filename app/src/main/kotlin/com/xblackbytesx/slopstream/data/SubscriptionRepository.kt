package com.xblackbytesx.slopstream.data

import com.xblackbytesx.slopstream.core.Channel
import com.xblackbytesx.slopstream.core.ImportedSubscription
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.data.db.SubscriptionDao
import com.xblackbytesx.slopstream.data.db.SubscriptionEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

data class ImportSummary(val added: Int, val alreadySubscribed: Int, val failed: List<String>)

@Singleton
class SubscriptionRepository @Inject constructor(
    private val dao: SubscriptionDao,
    private val settings: Settings,
    private val sources: Sources,
    private val clock: Clock,
) {
    fun observe(source: SourceKey): Flow<List<SubscriptionEntity>> = dao.observeActive(source.id)
    fun observeCount(source: SourceKey): Flow<Int> = dao.observeCount(source.id)

    suspend fun isSubscribed(source: SourceKey, channelId: String): Boolean =
        dao.get(source.id, channelId)?.let { it.deletedAt == null } ?: false

    /** Subscribes to the channels not already followed and returns those (to refresh now). */
    suspend fun subscribe(channels: List<Channel>): List<Channel> {
        val now = clock.now()
        val device = settings.deviceId()
        val existing = channels.associateWith { dao.get(it.source.id, it.remoteId) }
        val fresh = channels.filter { existing[it]?.deletedAt != null || existing[it] == null }
        dao.upsert(
            fresh.map { c ->
                val old = existing[c]
                SubscriptionEntity(
                    source = c.source.id,
                    remoteId = c.remoteId,
                    name = c.name.ifBlank { old?.name.orEmpty() },
                    avatarUrl = c.avatarUrl ?: old?.avatarUrl,
                    addedAt = old?.addedAt ?: now,
                    updatedAt = now,
                    deletedAt = null,
                    deviceId = device,
                    favorite = old?.favorite ?: false,
                    lastRefreshAt = old?.lastRefreshAt,
                )
            },
        )
        return fresh
    }

    /**
     * Mirrors the subscriptions of a signed-in account ([com.xblackbytesx.slopstream.core.SourceCapabilities.accountSubscriptions]):
     * new ones are added, ones the account no longer has are tombstoned. Favorites survive a
     * lapse and come back with the subscription. Returns the ones added.
     */
    suspend fun syncAccount(source: SourceKey, channels: List<Channel>): List<Channel> {
        val added = subscribe(channels)
        val keep = channels.mapTo(HashSet()) { it.remoteId }
        dao.activeIn(source.id).filter { it.remoteId !in keep }.forEach { unsubscribe(it.source, it.remoteId) }
        return added
    }

    fun observe(source: String, channelId: String): Flow<SubscriptionEntity?> = dao.observe(source, channelId)

    suspend fun setFavorite(source: String, channelId: String, favorite: Boolean) {
        val old = dao.get(source, channelId) ?: return
        if (old.favorite == favorite) return
        dao.upsert(listOf(old.copy(favorite = favorite, updatedAt = clock.now(), deviceId = settings.deviceId())))
    }

    suspend fun unsubscribe(source: String, channelId: String) {
        val old = dao.get(source, channelId) ?: return
        val now = clock.now()
        dao.upsert(listOf(old.copy(deletedAt = now, updatedAt = now, deviceId = settings.deviceId())))
    }

    /**
     * Stores imported subscriptions, looking up the URLs that had no channel id. Imports only
     * add: an export never unsubscribes anything here.
     */
    suspend fun import(
        found: List<ImportedSubscription>,
        unresolved: List<Pair<String, String>>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Pair<ImportSummary, List<Channel>> {
        val failed = ArrayList<String>()
        val channels = ArrayList<Channel>()
        found.forEach { s -> channels += Channel(s.source, s.channelId, s.name, url = "") }
        unresolved.forEachIndexed { i, (url, name) ->
            onProgress(i, unresolved.size)
            runCatching { sources.youtube.resolveChannel(url) }
                .onSuccess { channels += it }
                .onFailure { failed += name.ifBlank { url } }
        }
        val distinct = channels.distinctBy { it.source to it.remoteId }
        val added = subscribe(distinct)
        return ImportSummary(added.size, distinct.size - added.size, failed) to added
    }
}
