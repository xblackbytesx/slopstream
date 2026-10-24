package com.xblackbytesx.slopstream.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

internal const val VIDEO_ROW_COLUMNS = """
    f.source, f.remoteId, f.channelId, f.channelName, f.title, f.publishedAt, f.kind,
    f.thumbnailUrl, f.durationSec, f.dateApproximate,
    w.positionMs AS positionMs, w.durationMs AS watchDurationMs, w.watched AS watched,
    (l.remoteId IS NOT NULL AND l.deletedAt IS NULL) AS inWatchLater
"""

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscription WHERE source = :source AND deletedAt IS NULL ORDER BY name COLLATE NOCASE")
    fun observeActive(source: String): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscription WHERE deletedAt IS NULL")
    suspend fun active(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscription WHERE source = :source AND deletedAt IS NULL")
    suspend fun activeIn(source: String): List<SubscriptionEntity>

    @Query("SELECT * FROM subscription WHERE source = :source AND remoteId = :remoteId")
    suspend fun get(source: String, remoteId: String): SubscriptionEntity?

    @Query("SELECT COUNT(*) FROM subscription WHERE source = :source AND deletedAt IS NULL")
    fun observeCount(source: String): Flow<Int>

    @Query("SELECT * FROM subscription WHERE source = :source AND remoteId = :remoteId")
    fun observe(source: String, remoteId: String): Flow<SubscriptionEntity?>

    @Upsert
    suspend fun upsertRaw(rows: List<SubscriptionEntity>)

    /** A local change: keeps the sync bookkeeping and marks the rows unsynced. */
    @Transaction
    suspend fun upsert(rows: List<SubscriptionEntity>) {
        upsertRaw(
            rows.map { r ->
                val old = get(r.source, r.remoteId)
                r.copy(
                    rev = old?.rev,
                    localVersion = (old?.localVersion ?: 0) + 1,
                    syncedVersion = old?.syncedVersion ?: 0,
                    feedRev = old?.feedRev ?: 0,
                )
            },
        )
    }

    @Query("UPDATE subscription SET lastRefreshAt = :at, lastError = :error WHERE source = :source AND remoteId = :remoteId")
    suspend fun markRefreshed(source: String, remoteId: String, at: Long, error: String?)

    @Query("UPDATE subscription SET name = :name WHERE source = :source AND remoteId = :remoteId AND name != :name")
    suspend fun rename(source: String, remoteId: String, name: String)

    @Query("UPDATE subscription SET feedRev = :rev WHERE source = :source AND remoteId = :remoteId")
    suspend fun setFeedRev(source: String, remoteId: String, rev: Long)
}

@Dao
interface FeedDao {
    /** Subscribed channels only: unsubscribing hides a channel's items without deleting them. */
    @Query(
        """
        SELECT $VIDEO_ROW_COLUMNS
        FROM feed_item f
        JOIN subscription s ON s.source = f.source AND s.remoteId = f.channelId AND s.deletedAt IS NULL
        LEFT JOIN watch_state w ON w.source = f.source AND w.remoteId = f.remoteId AND w.deletedAt IS NULL
        LEFT JOIN watch_later l ON l.source = f.source AND l.remoteId = f.remoteId
        WHERE f.source = :source AND f.kind IN (:kinds)
        ORDER BY f.publishedAt DESC, f.remoteId
        """,
    )
    fun page(source: String, kinds: List<String>): PagingSource<Int, VideoRow>

    @Query(
        """
        SELECT $VIDEO_ROW_COLUMNS
        FROM feed_item f
        LEFT JOIN watch_state w ON w.source = f.source AND w.remoteId = f.remoteId AND w.deletedAt IS NULL
        LEFT JOIN watch_later l ON l.source = f.source AND l.remoteId = f.remoteId
        WHERE f.source = :source AND f.channelId = :channelId AND f.kind IN (:kinds)
        ORDER BY f.publishedAt DESC, f.remoteId
        """,
    )
    fun pageChannel(source: String, channelId: String, kinds: List<String>): PagingSource<Int, VideoRow>

    /** The Shorts player pages through this list in order. */
    @Query(
        """
        SELECT $VIDEO_ROW_COLUMNS
        FROM feed_item f
        JOIN subscription s ON s.source = f.source AND s.remoteId = f.channelId AND s.deletedAt IS NULL
        LEFT JOIN watch_state w ON w.source = f.source AND w.remoteId = f.remoteId AND w.deletedAt IS NULL
        LEFT JOIN watch_later l ON l.source = f.source AND l.remoteId = f.remoteId
        WHERE f.source = :source AND f.kind = 'SHORT'
        ORDER BY f.publishedAt DESC, f.remoteId
        LIMIT :limit
        """,
    )
    suspend fun shorts(source: String, limit: Int): List<VideoRow>

    @Query("SELECT * FROM feed_item WHERE source = :source AND remoteId = :remoteId")
    suspend fun get(source: String, remoteId: String): FeedItemEntity?

    /** Library search, built by [LibrarySearch]: feed items of followed channels, Watch later. */
    @RawQuery
    suspend fun search(query: SupportSQLiteQuery): List<VideoRow>

    @Query("SELECT remoteId FROM feed_item WHERE source = :source AND channelId = :channelId")
    suspend fun idsForChannel(source: String, channelId: String): List<String>

    @Upsert
    suspend fun upsert(rows: List<FeedItemEntity>)

    /** An item the server says is gone (an ended livestream). */
    @Query("DELETE FROM feed_item WHERE source = :source AND remoteId = :remoteId")
    suspend fun delete(source: String, remoteId: String)

    @Query("UPDATE feed_item SET durationSec = :durationSec WHERE source = :source AND remoteId = :remoteId")
    suspend fun setDuration(source: String, remoteId: String, durationSec: Long)

    /**
     * Keeps the newest [keep] items per channel and kind. RSS only ever returns the latest 15,
     * so older items exist only here; the cap keeps the cache bounded without losing history
     * anyone scrolls to.
     */
    @Query(
        """
        DELETE FROM feed_item WHERE source = :source AND channelId = :channelId AND kind = :kind AND remoteId NOT IN (
            SELECT remoteId FROM feed_item WHERE source = :source AND channelId = :channelId AND kind = :kind
            ORDER BY publishedAt DESC LIMIT :keep
        )
        """,
    )
    suspend fun prune(source: String, channelId: String, kind: String, keep: Int)

    @Query("DELETE FROM feed_item WHERE source = :source AND channelId = :channelId AND kind = :kind AND remoteId NOT IN (:keep)")
    suspend fun deleteMissing(source: String, channelId: String, kind: String, keep: List<String>)

    /**
     * Merges one channel's fresh items. Existing rows keep a known duration when the new
     * fetch has none (RSS never carries durations). For [completeKinds] the fresh list is the
     * whole truth, so cached items of those kinds that it lacks are dropped (an ended stream).
     */
    @Transaction
    suspend fun merge(source: String, channelId: String, fresh: List<FeedItemEntity>, keepPerKind: Int, completeKinds: Set<String> = emptySet()) {
        val merged = fresh.map { new ->
            val old = get(new.source, new.remoteId)
            if (old != null && new.durationSec == null) new.copy(durationSec = old.durationSec) else new
        }
        upsert(merged)
        completeKinds.forEach { kind -> deleteMissing(source, channelId, kind, fresh.filter { it.kind == kind }.map { it.remoteId }) }
        fresh.map { it.kind }.distinct().forEach { prune(source, channelId, it, keepPerKind) }
    }
}

@Dao
interface WatchDao {
    @Query("SELECT * FROM watch_state WHERE source = :source AND remoteId = :remoteId")
    suspend fun state(source: String, remoteId: String): WatchStateEntity?

    @Upsert
    suspend fun upsertStateRaw(row: WatchStateEntity)

    /** A local change: keeps the sync bookkeeping and marks the row unsynced. */
    @Transaction
    suspend fun upsertState(row: WatchStateEntity) {
        val old = state(row.source, row.remoteId)
        upsertStateRaw(row.copy(rev = old?.rev, localVersion = (old?.localVersion ?: 0) + 1, syncedVersion = old?.syncedVersion ?: 0))
    }

    /** What a history entry shows, from the feed cache or Watch later. */
    @Query(
        """
        SELECT title, channelId, channelName, thumbnailUrl, kind FROM feed_item WHERE source = :source AND remoteId = :remoteId
        UNION ALL
        SELECT title, channelId, channelName, thumbnailUrl, kind FROM watch_later WHERE source = :source AND remoteId = :remoteId
        LIMIT 1
        """,
    )
    suspend fun meta(source: String, remoteId: String): VideoMeta?

    @Query(
        """
        SELECT l.source, l.remoteId, l.channelId, l.channelName, l.title, l.publishedAt, l.kind,
            l.thumbnailUrl, l.durationSec, 0 AS dateApproximate,
            w.positionMs AS positionMs, w.durationMs AS watchDurationMs, w.watched AS watched,
            1 AS inWatchLater
        FROM watch_later l
        LEFT JOIN watch_state w ON w.source = l.source AND w.remoteId = l.remoteId AND w.deletedAt IS NULL
        WHERE l.source = :source AND l.deletedAt IS NULL
        ORDER BY l.addedAt DESC
        """,
    )
    fun pageWatchLater(source: String): PagingSource<Int, VideoRow>

    /** What was watched on this service, last watched first; display fields from the history row. */
    @Query(
        """
        SELECT w.source, w.remoteId,
            COALESCE(w.channelId, f.channelId, '') AS channelId, COALESCE(w.channelName, f.channelName, '') AS channelName,
            COALESCE(w.title, f.title, w.remoteId) AS title, COALESCE(f.publishedAt, w.updatedAt) AS publishedAt,
            COALESCE(w.kind, f.kind, 'VIDEO') AS kind, COALESCE(w.thumbnailUrl, f.thumbnailUrl) AS thumbnailUrl,
            COALESCE(f.durationSec, w.durationMs / 1000) AS durationSec, 0 AS dateApproximate,
            w.positionMs AS positionMs, w.durationMs AS watchDurationMs, w.watched AS watched,
            (l.remoteId IS NOT NULL AND l.deletedAt IS NULL) AS inWatchLater
        FROM watch_state w
        LEFT JOIN feed_item f ON f.source = w.source AND f.remoteId = w.remoteId
        LEFT JOIN watch_later l ON l.source = w.source AND l.remoteId = w.remoteId
        WHERE w.source = :source AND w.deletedAt IS NULL
        ORDER BY w.updatedAt DESC
        """,
    )
    fun pageHistory(source: String): PagingSource<Int, VideoRow>

    @Query("SELECT * FROM watch_later WHERE source = :source AND remoteId = :remoteId")
    suspend fun watchLater(source: String, remoteId: String): WatchLaterEntity?

    @Upsert
    suspend fun upsertWatchLaterRaw(row: WatchLaterEntity)

    /** A local change: keeps the sync bookkeeping and marks the row unsynced. */
    @Transaction
    suspend fun upsertWatchLater(row: WatchLaterEntity) {
        val old = watchLater(row.source, row.remoteId)
        upsertWatchLaterRaw(row.copy(rev = old?.rev, localVersion = (old?.localVersion ?: 0) + 1, syncedVersion = old?.syncedVersion ?: 0))
    }
}

/** A video's display fields, for its history entry. */
data class VideoMeta(
    val title: String,
    val channelId: String,
    val channelName: String,
    val thumbnailUrl: String?,
    val kind: String,
)

/**
 * The sync engine's view of the synced tables: unsynced rows out, server rows in. A row is
 * unsynced while `localVersion > syncedVersion`; server rows are written with both equal, so
 * applying them never marks anything for sending.
 */
@Dao
interface SyncDao {
    @Query("SELECT * FROM subscription WHERE localVersion > syncedVersion LIMIT :limit")
    suspend fun unsyncedSubscriptions(limit: Int): List<SubscriptionEntity>

    @Query("SELECT * FROM watch_later WHERE localVersion > syncedVersion LIMIT :limit")
    suspend fun unsyncedWatchLater(limit: Int): List<WatchLaterEntity>

    @Query("SELECT * FROM watch_state WHERE localVersion > syncedVersion LIMIT :limit")
    suspend fun unsyncedWatchState(limit: Int): List<WatchStateEntity>

    @Query(
        """
        SELECT (SELECT COUNT(*) FROM subscription WHERE localVersion > syncedVersion)
             + (SELECT COUNT(*) FROM watch_later WHERE localVersion > syncedVersion)
             + (SELECT COUNT(*) FROM watch_state WHERE localVersion > syncedVersion)
        """,
    )
    fun observeUnsynced(): Flow<Int>

    /** The version that was sent is on the server now; a newer local edit stays unsynced. */
    @Query("UPDATE subscription SET syncedVersion = :version WHERE source = :source AND remoteId = :remoteId AND syncedVersion < :version")
    suspend fun sentSubscription(source: String, remoteId: String, version: Long)

    @Query("UPDATE watch_later SET syncedVersion = :version WHERE source = :source AND remoteId = :remoteId AND syncedVersion < :version")
    suspend fun sentWatchLater(source: String, remoteId: String, version: Long)

    @Query("UPDATE watch_state SET syncedVersion = :version WHERE source = :source AND remoteId = :remoteId AND syncedVersion < :version")
    suspend fun sentWatchState(source: String, remoteId: String, version: Long)

    /** The server has what this device sent; a newer local edit builds on that rev. */
    @Query("UPDATE subscription SET rev = :rev WHERE source = :source AND remoteId = :remoteId")
    suspend fun rebaseSubscription(source: String, remoteId: String, rev: Long?)

    @Query("UPDATE watch_later SET rev = :rev WHERE source = :source AND remoteId = :remoteId")
    suspend fun rebaseWatchLater(source: String, remoteId: String, rev: Long?)

    @Query("UPDATE watch_state SET rev = :rev WHERE source = :source AND remoteId = :remoteId")
    suspend fun rebaseWatchState(source: String, remoteId: String, rev: Long?)

    @Query("SELECT * FROM subscription WHERE source = :source AND remoteId = :remoteId")
    suspend fun subscription(source: String, remoteId: String): SubscriptionEntity?

    @Query("SELECT * FROM watch_later WHERE source = :source AND remoteId = :remoteId")
    suspend fun watchLater(source: String, remoteId: String): WatchLaterEntity?

    @Query("SELECT * FROM watch_state WHERE source = :source AND remoteId = :remoteId")
    suspend fun watchState(source: String, remoteId: String): WatchStateEntity?

    @Upsert
    suspend fun putSubscription(row: SubscriptionEntity)

    @Upsert
    suspend fun putWatchLater(row: WatchLaterEntity)

    @Upsert
    suspend fun putWatchState(row: WatchStateEntity)

    /** A new server, or one restored from an older backup: everything goes up again. */
    @Query("UPDATE subscription SET rev = NULL, localVersion = syncedVersion + 1")
    suspend fun resendSubscriptions()

    @Query("UPDATE watch_later SET rev = NULL, localVersion = syncedVersion + 1")
    suspend fun resendWatchLater()

    @Query("UPDATE watch_state SET rev = NULL, localVersion = syncedVersion + 1")
    suspend fun resendWatchState()

    /**
     * "Replace this device's library with the server's": the one hard delete, chosen by the
     * user when connecting; the server's copy comes right after.
     */
    @Query("DELETE FROM subscription")
    suspend fun clearSubscriptions()

    @Query("DELETE FROM watch_later")
    suspend fun clearWatchLater()

    @Query("DELETE FROM watch_state")
    suspend fun clearWatchState()
}

/**
 * Builds the library search: every word must appear in the title or the channel name. SQLite's
 * LIKE ignores case for ASCII letters only, so "ÉCOLE" does not find "école".
 */
object LibrarySearch {
    const val LIMIT = 120
    private const val MAX_WORDS = 6

    fun words(query: String): List<String> =
        query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.distinct().take(MAX_WORDS)

    private fun like(word: String) = "%" + word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    /** Followed channels' feed items of one service. Null when there is nothing to search for. */
    fun feedQuery(source: String, query: String): SupportSQLiteQuery? = build(source, query) { where ->
        """
        SELECT $VIDEO_ROW_COLUMNS
        FROM feed_item f
        JOIN subscription s ON s.source = f.source AND s.remoteId = f.channelId AND s.deletedAt IS NULL
        LEFT JOIN watch_state w ON w.source = f.source AND w.remoteId = f.remoteId AND w.deletedAt IS NULL
        LEFT JOIN watch_later l ON l.source = f.source AND l.remoteId = f.remoteId
        WHERE $where
        ORDER BY f.publishedAt DESC, f.remoteId
        LIMIT $LIMIT
        """
    }

    /** Watch later, including videos of channels no longer followed. */
    fun watchLaterQuery(source: String, query: String): SupportSQLiteQuery? = build(source, query) { where ->
        """
        SELECT f.source, f.remoteId, f.channelId, f.channelName, f.title, f.publishedAt, f.kind,
            f.thumbnailUrl, f.durationSec, 0 AS dateApproximate,
            w.positionMs AS positionMs, w.durationMs AS watchDurationMs, w.watched AS watched,
            1 AS inWatchLater
        FROM watch_later f
        LEFT JOIN watch_state w ON w.source = f.source AND w.remoteId = f.remoteId AND w.deletedAt IS NULL
        WHERE f.deletedAt IS NULL AND $where
        ORDER BY f.publishedAt DESC, f.remoteId
        LIMIT $LIMIT
        """
    }

    private fun build(source: String, query: String, sql: (where: String) -> String): SupportSQLiteQuery? {
        val words = words(query).takeIf { it.isNotEmpty() } ?: return null
        val where = "f.source = ? AND " + words.joinToString(" AND ") { "(f.title LIKE ? ESCAPE '\\' OR f.channelName LIKE ? ESCAPE '\\')" }
        val args = listOf(source) + words.flatMap { listOf(like(it), like(it)) }
        return SimpleSQLiteQuery(sql(where), args.toTypedArray())
    }
}
