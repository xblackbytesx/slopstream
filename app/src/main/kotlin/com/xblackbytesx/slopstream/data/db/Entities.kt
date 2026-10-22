package com.xblackbytesx.slopstream.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/*
 * Rows the future sync server merges (subscription, watch_state, watch_later) carry
 * `updatedAt` (epoch ms, last writer wins), `deletedAt` (tombstone, never a hard delete) and
 * the writing `deviceId`. Everything keyed by (source, remoteId) so Floatplane and Sauce+ fit
 * the same tables. feed_item and the channel columns refreshed from the network are cache.
 */

@Entity(tableName = "subscription", primaryKeys = ["source", "remoteId"])
data class SubscriptionEntity(
    val source: String,
    val remoteId: String,
    val name: String,
    val avatarUrl: String?,
    val addedAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val deviceId: String,
    /** Pinned at the top of Subscriptions and refreshed first. Synced: bumps [updatedAt]. */
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
    // Cache, not synced:
    val lastRefreshAt: Long? = null,
    val lastError: String? = null,
)

@Entity(
    tableName = "feed_item",
    primaryKeys = ["source", "remoteId"],
    indices = [Index("kind", "publishedAt"), Index("source", "channelId")],
)
data class FeedItemEntity(
    val source: String,
    val remoteId: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val publishedAt: Long,
    /** [com.xblackbytesx.slopstream.core.VideoKind] name. */
    val kind: String,
    val thumbnailUrl: String?,
    val durationSec: Long?,
    val dateApproximate: Boolean,
    val fetchedAt: Long,
)

@Entity(tableName = "watch_state", primaryKeys = ["source", "remoteId"])
data class WatchStateEntity(
    val source: String,
    val remoteId: String,
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    val updatedAt: Long,
    val deviceId: String,
)

@Entity(tableName = "watch_later", primaryKeys = ["source", "remoteId"])
data class WatchLaterEntity(
    val source: String,
    val remoteId: String,
    /** Denormalised so the list survives the feed cache pruning the item. */
    val title: String,
    val channelId: String,
    val channelName: String,
    val thumbnailUrl: String?,
    val durationSec: Long?,
    val publishedAt: Long,
    val kind: String,
    val addedAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val deviceId: String,
)

/** A feed or watch-later row joined with its watch state, as the tiles show it. */
data class VideoRow(
    val source: String,
    val remoteId: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val publishedAt: Long,
    val kind: String,
    val thumbnailUrl: String?,
    val durationSec: Long?,
    val dateApproximate: Boolean,
    @ColumnInfo(name = "positionMs") val positionMs: Long?,
    @ColumnInfo(name = "watchDurationMs") val watchDurationMs: Long?,
    @ColumnInfo(name = "watched") val watched: Boolean?,
    @ColumnInfo(name = "inWatchLater") val inWatchLater: Boolean,
)
