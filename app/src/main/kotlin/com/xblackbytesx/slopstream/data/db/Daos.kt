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
    suspend fun upsert(rows: List<SubscriptionEntity>)

    @Query("UPDATE subscription SET lastRefreshAt = :at, lastError = :error WHERE source = :source AND remoteId = :remoteId")
    suspend fun markRefreshed(source: String, remoteId: String, at: Long, error: String?)

    @Query("UPDATE subscription SET name = :name WHERE source = :source AND remoteId = :remoteId AND name != :name")
    suspend fun rename(source: String, remoteId: String, name: String)
}

@Dao
interface FeedDao {
    /** Subscribed channels only: unsubscribing hides a channel's items without deleting them. */
    @Query(
        """
        SELECT $VIDEO_ROW_COLUMNS
        FROM feed_item f
        JOIN subscription s ON s.source = f.source AND s.remoteId = f.channelId AND s.deletedAt IS NULL
        LEFT JOIN watch_state w ON w.source = f.source AND w.remoteId = f.remoteId
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
        LEFT JOIN watch_state w ON w.source = f.source AND w.remoteId = f.remoteId
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
        LEFT JOIN watch_state w ON w.source = f.source AND w.remoteId = f.remoteId
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
    suspend fun upsertState(row: WatchStateEntity)

    @Query(
        """
        SELECT l.source, l.remoteId, l.channelId, l.channelName, l.title, l.publishedAt, l.kind,
            l.thumbnailUrl, l.durationSec, 0 AS dateApproximate,
            w.positionMs AS positionMs, w.durationMs AS watchDurationMs, w.watched AS watched,
            1 AS inWatchLater
        FROM watch_later l
        LEFT JOIN watch_state w ON w.source = l.source AND w.remoteId = l.remoteId
        WHERE l.source = :source AND l.deletedAt IS NULL
        ORDER BY l.addedAt DESC
        """,
    )
    fun pageWatchLater(source: String): PagingSource<Int, VideoRow>

    @Query("SELECT * FROM watch_later WHERE source = :source AND remoteId = :remoteId")
    suspend fun watchLater(source: String, remoteId: String): WatchLaterEntity?

    @Upsert
    suspend fun upsertWatchLater(row: WatchLaterEntity)
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
        LEFT JOIN watch_state w ON w.source = f.source AND w.remoteId = f.remoteId
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
        LEFT JOIN watch_state w ON w.source = f.source AND w.remoteId = f.remoteId
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
