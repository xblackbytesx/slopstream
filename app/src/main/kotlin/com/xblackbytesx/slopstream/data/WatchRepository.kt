package com.xblackbytesx.slopstream.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.data.db.VideoRow
import com.xblackbytesx.slopstream.data.db.WatchDao
import com.xblackbytesx.slopstream.data.db.WatchLaterEntity
import com.xblackbytesx.slopstream.data.db.WatchStateEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchRepository @Inject constructor(
    private val dao: WatchDao,
    private val settings: Settings,
    private val clock: Clock,
) {
    fun watchLater(source: SourceKey): Flow<PagingData<VideoRow>> =
        Pager(PagingConfig(pageSize = 48, enablePlaceholders = false)) { dao.pageWatchLater(source.id) }.flow

    suspend fun state(source: String, remoteId: String): WatchStateEntity? = dao.state(source, remoteId)

    /** Where to start: the saved position, unless the video was finished or barely started. */
    suspend fun resumePositionMs(source: String, remoteId: String): Long {
        val s = dao.state(source, remoteId) ?: return 0
        return if (s.watched || s.positionMs < MIN_RESUME_MS) 0 else s.positionMs
    }

    suspend fun savePosition(source: String, remoteId: String, positionMs: Long, durationMs: Long, ended: Boolean = false) {
        if (durationMs <= 0) return
        val old = dao.state(source, remoteId)
        val watched = ended || isWatched(positionMs, durationMs) || (old?.watched == true)
        dao.upsertState(
            WatchStateEntity(
                source = source,
                remoteId = remoteId,
                positionMs = if (ended) durationMs else positionMs,
                durationMs = durationMs,
                watched = watched,
                updatedAt = clock.now(),
                deviceId = settings.deviceId(),
            ),
        )
    }

    suspend fun setWatched(source: String, remoteId: String, watched: Boolean) {
        val old = dao.state(source, remoteId)
        dao.upsertState(
            WatchStateEntity(
                source = source,
                remoteId = remoteId,
                positionMs = if (watched) old?.durationMs ?: 0 else 0,
                durationMs = old?.durationMs ?: 0,
                watched = watched,
                updatedAt = clock.now(),
                deviceId = settings.deviceId(),
            ),
        )
    }

    /** Rows from outside the library (YouTube search) with this device's watch state added. */
    suspend fun annotate(rows: List<VideoRow>): List<VideoRow> = rows.map { r ->
        val s = dao.state(r.source, r.remoteId)
        val later = dao.watchLater(r.source, r.remoteId)
        r.copy(
            positionMs = s?.positionMs,
            watchDurationMs = s?.durationMs,
            watched = s?.watched,
            inWatchLater = later != null && later.deletedAt == null,
        )
    }

    /** Adds the row to Watch later, or removes it (tombstone) when it is already there. */
    suspend fun toggleWatchLater(row: VideoRow): Boolean {
        val old = dao.watchLater(row.source, row.remoteId)
        val now = clock.now()
        val add = old == null || old.deletedAt != null
        dao.upsertWatchLater(
            WatchLaterEntity(
                source = row.source,
                remoteId = row.remoteId,
                title = row.title,
                channelId = row.channelId,
                channelName = row.channelName,
                thumbnailUrl = row.thumbnailUrl,
                durationSec = row.durationSec,
                publishedAt = row.publishedAt,
                kind = row.kind,
                addedAt = if (add) now else old.addedAt,
                updatedAt = now,
                deletedAt = if (add) null else now,
                deviceId = settings.deviceId(),
            ),
        )
        return add
    }

    companion object {
        const val MIN_RESUME_MS = 10_000L

        /** Watched at 90 %, or with under 30 s left of a video over 5 minutes (end screens). */
        fun isWatched(positionMs: Long, durationMs: Long): Boolean =
            durationMs > 0 && (
                positionMs >= durationMs * 0.9 ||
                    (durationMs > 5 * 60_000L && durationMs - positionMs <= 30_000L)
                )
    }
}
