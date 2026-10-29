package com.xblackbytesx.slopstream.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/*
 * Rows the sync server merges (subscription, watch_state, watch_later) carry `updatedAt` (epoch
 * ms, the conflict clock), `deletedAt` (tombstone, never a hard delete) and the writing
 * `deviceId`, plus the sync bookkeeping (`rev`, `localVersion`, `syncedVersion`; AGENTS.md
 * decision 18). Everything keyed by (source, remoteId) so Floatplane and Sauce+ fit the same
 * tables. feed_item and the channel columns refreshed from the network are cache.
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
    /** The highest feed rev the sync server sent for this channel (schema 4); 0: none yet. */
    @ColumnInfo(defaultValue = "0") val feedRev: Long = 0,
    // Sync (schema 3), never set by repositories: the DAO's write keeps them.
    /** The server's rev of this row when it was last synced; null until then. */
    @ColumnInfo(defaultValue = "NULL") val rev: Long? = null,
    /** Bumped by every local change; the row is unsynced while it is ahead of [syncedVersion]. */
    @ColumnInfo(defaultValue = "0") val localVersion: Long = 0,
    @ColumnInfo(defaultValue = "0") val syncedVersion: Long = 0,
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
    /**
     * Loaded from a channel page's "Load older" (schema 5, decision 23): shown on that channel
     * page only, never in the main feeds, and never pruned or dropped by a refresh.
     */
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    /** No date from the service (schema 5): [publishedAt] only orders the list, tiles show none. */
    @ColumnInfo(defaultValue = "0") val dateUnknown: Boolean = false,
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
    // History (schema 3): what the server's History page shows without the feed cache.
    @ColumnInfo(defaultValue = "NULL") val title: String? = null,
    @ColumnInfo(defaultValue = "NULL") val channelId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val channelName: String? = null,
    @ColumnInfo(defaultValue = "NULL") val thumbnailUrl: String? = null,
    @ColumnInfo(defaultValue = "NULL") val kind: String? = null,
    /** Removed from history (on the server's website): the video shows as unwatched. */
    @ColumnInfo(defaultValue = "NULL") val deletedAt: Long? = null,
    // Sync (schema 3), never set by repositories: the DAO's write keeps them.
    /** The server's rev of this row when it was last synced; null until then. */
    @ColumnInfo(defaultValue = "NULL") val rev: Long? = null,
    /** Bumped by every local change; the row is unsynced while it is ahead of [syncedVersion]. */
    @ColumnInfo(defaultValue = "0") val localVersion: Long = 0,
    @ColumnInfo(defaultValue = "0") val syncedVersion: Long = 0,
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
    // Sync (schema 3), never set by repositories: the DAO's write keeps them.
    /** The server's rev of this row when it was last synced; null until then. */
    @ColumnInfo(defaultValue = "NULL") val rev: Long? = null,
    /** Bumped by every local change; the row is unsynced while it is ahead of [syncedVersion]. */
    @ColumnInfo(defaultValue = "0") val localVersion: Long = 0,
    @ColumnInfo(defaultValue = "0") val syncedVersion: Long = 0,
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
    val dateUnknown: Boolean,
    @ColumnInfo(name = "positionMs") val positionMs: Long?,
    @ColumnInfo(name = "watchDurationMs") val watchDurationMs: Long?,
    @ColumnInfo(name = "watched") val watched: Boolean?,
    @ColumnInfo(name = "inWatchLater") val inWatchLater: Boolean,
)

/**
 * A video saved on this device (decision 24). Never synced: each device keeps its own. Files
 * live in the downloads directory under [videoFile] and [audioFile]; a part file sits beside
 * each until it is complete.
 */
@Entity(tableName = "download", primaryKeys = ["source", "remoteId"])
data class DownloadEntity(
    val source: String,
    val remoteId: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val durationSec: Long,
    val thumbnailUrl: String?,
    /**
     * The service's stream ids, to renew an expired URL, and the files they become. Both: the
     * two files YouTube serves. Video alone: one file with its sound (Floatplane). Audio file
     * alone: audio only, from [audioStreamId], or when that is null taken out of the
     * [videoStreamId] file ([extractsAudio]).
     */
    val videoStreamId: String?,
    val audioStreamId: String?,
    val videoFile: String?,
    val audioFile: String?,
    /** The video stream as the player's quality label shows it; empty and 0 for audio only. */
    val codecs: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val hdr: Boolean,
    /** Expected total of both files; exact once both are done. */
    val sizeBytes: Long,
    val doneBytes: Long,
    /** [DownloadState] name. */
    val state: String,
    val error: String?,
    val addedAt: Long,
    val finishedAt: Long?,
)

enum class DownloadState { QUEUED, RUNNING, DONE, FAILED }

/** Audio only from a file with picture and sound: fetched whole, then its sound kept. */
val DownloadEntity.extractsAudio: Boolean get() = videoFile == null && audioStreamId == null && videoStreamId != null
