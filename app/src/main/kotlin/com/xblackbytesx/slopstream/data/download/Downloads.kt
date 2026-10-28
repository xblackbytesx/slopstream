package com.xblackbytesx.slopstream.data.download

import android.content.Context
import com.xblackbytesx.slopstream.core.DownloadOffer
import com.xblackbytesx.slopstream.core.DownloadStream
import com.xblackbytesx.slopstream.core.playback.PlaybackPlan
import com.xblackbytesx.slopstream.core.playback.VideoCandidate
import com.xblackbytesx.slopstream.data.Clock
import com.xblackbytesx.slopstream.data.Settings
import com.xblackbytesx.slopstream.data.Sources
import com.xblackbytesx.slopstream.data.db.DownloadDao
import com.xblackbytesx.slopstream.data.db.DownloadEntity
import com.xblackbytesx.slopstream.data.db.DownloadState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Starts the background work that empties the queue ([DownloadWorker] in the app). */
fun interface DownloadScheduler {
    fun kick()
}

/** Not enough room on the device for a download, with what it needs and what is free. */
class NotEnoughSpaceException(val needed: Long, val free: Long) : IOException("Not enough space")

/**
 * Videos saved on this device for offline viewing (decision 24). The queue is the `download`
 * table; [runQueue] works it one video at a time, video file then audio file, each resumable.
 * Playing a finished download never touches the network ([localPlan]). Nothing here is synced:
 * every device keeps its own downloads.
 */
@Singleton
class Downloads(
    private val dir: File,
    private val dao: DownloadDao,
    private val sources: Sources,
    private val settings: Settings,
    private val clock: Clock,
    private val client: OkHttpClient,
    private val scheduler: DownloadScheduler,
    private val fetcher: RangeFetcher = RangeFetcher(client),
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        dao: DownloadDao,
        sources: Sources,
        settings: Settings,
        clock: Clock,
        client: OkHttpClient,
        scheduler: DownloadScheduler,
    ) : this(
        (context.getExternalFilesDir(null) ?: context.filesDir).resolve("downloads"),
        dao, sources, settings, clock, client, scheduler,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The download being fetched right now, by [key], so removing it stops it. */
    private val running = ConcurrentHashMap<String, Job>()

    val all: Flow<List<DownloadEntity>> = dao.observeAll()

    /** Every download's state by [key], for tile badges and menus. */
    val states: StateFlow<Map<String, DownloadState>> = dao.observeAll()
        .map { rows -> rows.associate { key(it.source, it.remoteId) to DownloadState.valueOf(it.state) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun offers(source: String): Boolean = runCatching { sources[source].capabilities.canDownload }.getOrDefault(false)

    fun isDone(source: String, remoteId: String) = states.value[key(source, remoteId)] == DownloadState.DONE

    /** Bytes free where downloads go. */
    fun freeBytes(): Long = dir.apply { mkdirs() }.usableSpace

    /** What [remoteId] can be saved as on this device: its playable qualities, best first. */
    suspend fun offer(source: String, remoteId: String): DownloadOffer {
        val s = settings.current()
        return sources[source].downloadOffer(remoteId, s.effectiveProfile, s.prefs)
    }

    /** Queues [offer] at [video] quality. Refuses when the device lacks the room for it. */
    suspend fun start(source: String, offer: DownloadOffer, video: DownloadStream) {
        val size = (video.sizeBytes ?: 0) + (offer.audio.sizeBytes ?: 0)
        val free = freeBytes()
        if (size + SPARE_BYTES > free) throw NotEnoughSpaceException(size, free)
        val c = requireNotNull(video.video) { "not a video stream" }
        val base = baseName(source, offer.videoId)
        dao.upsert(
            DownloadEntity(
                source = source,
                remoteId = offer.videoId,
                title = offer.title,
                channelId = offer.channelId,
                channelName = offer.channelName,
                durationSec = offer.durationSec,
                thumbnailUrl = offer.thumbnailUrl,
                videoStreamId = video.id,
                audioStreamId = offer.audio.id,
                videoFile = "$base.v.${video.extension}",
                audioFile = "$base.a.${offer.audio.extension}",
                codecs = c.codecs,
                width = c.width,
                height = c.height,
                fps = c.fps,
                hdr = c.hdr,
                sizeBytes = size,
                doneBytes = 0,
                state = DownloadState.QUEUED.name,
                error = null,
                addedAt = clock.now(),
                finishedAt = null,
            ),
        )
        scheduler.kick()
    }

    suspend fun retry(source: String, remoteId: String) {
        dao.setState(source, remoteId, DownloadState.QUEUED.name)
        scheduler.kick()
    }

    /** Stops it if it is running, then deletes its files and its row. */
    suspend fun remove(source: String, remoteId: String) {
        running[key(source, remoteId)]?.let { it.cancel(); it.join() }
        val row = dao.get(source, remoteId) ?: return
        withContext(Dispatchers.IO) { files(row).forEach { it.delete() } }
        dao.delete(source, remoteId)
    }

    suspend fun removeAll() {
        dao.all().forEach { remove(it.source, it.remoteId) }
        // Anything left behind by a row that is gone.
        withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }
    }

    /** Downloads finished at or after [at] (epoch ms). */
    suspend fun finishedSince(at: Long): List<DownloadEntity> = dao.all().finishedAfter(at)

    /** Bytes the downloads take on disk, finished or not. */
    suspend fun usedBytes(): Long = withContext(Dispatchers.IO) { dir.listFiles()?.sumOf { it.length() } ?: 0L }

    /** The saved thumbnail, once there is one. */
    fun thumbnail(row: DownloadEntity): File? = File(dir, "${baseName(row.source, row.remoteId)}.jpg").takeIf { it.exists() }

    /**
     * A finished download as a plan that plays from the files. A download whose files are gone
     * (cleared by the system or by hand) is marked failed, so Retry fetches it again.
     */
    suspend fun localPlan(source: String, remoteId: String): PlaybackPlan.Local? {
        if (states.value[key(source, remoteId)] != DownloadState.DONE) return null
        val row = dao.get(source, remoteId)?.takeIf { it.state == DownloadState.DONE.name } ?: return null
        val video = File(dir, row.videoFile)
        val audio = File(dir, row.audioFile)
        if (!video.exists() || !audio.exists()) {
            dao.setState(source, remoteId, DownloadState.FAILED.name, error = "The files are gone")
            return null
        }
        return PlaybackPlan.Local(
            videoId = remoteId,
            title = row.title,
            durationSec = row.durationSec,
            videoPath = video.absolutePath,
            audioPath = audio.absolutePath,
            quality = VideoCandidate(row.videoStreamId, row.codecs, row.width, row.height, row.fps, bitrate = 0, hdr = row.hdr),
        )
    }

    /**
     * Fetches every queued download in turn. Returns false when the network went away part way:
     * that download stays queued and the caller tries again later. [onProgress] gets the row and
     * the bytes done of both files.
     */
    suspend fun runQueue(onProgress: suspend (DownloadEntity, Long) -> Unit = { _, _ -> }): Boolean {
        dir.mkdirs()
        while (true) {
            val row = dao.next() ?: return true
            val k = key(row.source, row.remoteId)
            var networkLost = false
            coroutineScope {
                val job = launch { networkLost = !fetch(row, onProgress) }
                running[k] = job
                try {
                    job.join()
                } finally {
                    running.remove(k)
                }
            }
            if (networkLost) return false
        }
    }

    /** One download, both files. False when the network went away; the row stays queued. */
    private suspend fun fetch(row: DownloadEntity, onProgress: suspend (DownloadEntity, Long) -> Unit): Boolean {
        dao.setState(row.source, row.remoteId, DownloadState.RUNNING.name)
        val source = sources[row.source]
        return try {
            val video = source.downloadStream(row.remoteId, row.videoStreamId)
            val audio = source.downloadStream(row.remoteId, row.audioStreamId)
            if (video.sizeExact && audio.sizeExact) dao.setSize(row.source, row.remoteId, video.sizeBytes!! + audio.sizeBytes!!)
            val videoFile = File(dir, row.videoFile)
            val videoSize = fetcher.fetch(
                videoFile,
                video.sizeBytes.takeIf { video.sizeExact },
                url = { renew -> if (renew) source.downloadStream(row.remoteId, row.videoStreamId).url else video.url },
            ) { done -> progress(row, done, onProgress) }
            val audioSize = fetcher.fetch(
                File(dir, row.audioFile),
                audio.sizeBytes.takeIf { audio.sizeExact },
                url = { renew -> if (renew) source.downloadStream(row.remoteId, row.audioStreamId).url else audio.url },
            ) { done -> progress(row, videoSize + done, onProgress) }
            saveThumbnail(row)
            val total = videoSize + audioSize
            dao.setSize(row.source, row.remoteId, total)
            dao.progress(row.source, row.remoteId, total)
            dao.setState(row.source, row.remoteId, DownloadState.DONE.name, finishedAt = clock.now())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: RangeFetcher.ExpiredException) {
            dao.setState(row.source, row.remoteId, DownloadState.FAILED.name, error = e.message)
            true
        } catch (e: IOException) {
            // The network went away: keep the part files and go on when it is back.
            dao.setState(row.source, row.remoteId, DownloadState.QUEUED.name, error = "Waiting for the network")
            false
        } catch (e: Exception) {
            dao.setState(row.source, row.remoteId, DownloadState.FAILED.name, error = e.message ?: e.javaClass.simpleName)
            true
        }
    }

    private suspend fun progress(row: DownloadEntity, done: Long, onProgress: suspend (DownloadEntity, Long) -> Unit) {
        dao.progress(row.source, row.remoteId, done)
        onProgress(row, done)
    }

    /** Best effort: the tile in Downloads shows it offline. */
    private suspend fun saveThumbnail(row: DownloadEntity) {
        val url = row.thumbnailUrl ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (r.isSuccessful) File(dir, "${baseName(row.source, row.remoteId)}.jpg").writeBytes(r.body.bytes())
                }
            }
        }
    }

    private fun files(row: DownloadEntity): List<File> {
        val base = baseName(row.source, row.remoteId)
        return listOf(row.videoFile, row.audioFile, "$base.jpg").flatMap { listOf(File(dir, it), File(dir, "$it.part")) }
    }

    companion object {
        fun key(source: String, remoteId: String) = "$source:$remoteId"

        /** Room left free after a download, so the device keeps working. */
        const val SPARE_BYTES = 200L * 1024 * 1024

        private fun baseName(source: String, remoteId: String) = source + "_" + remoteId.replace(Regex("[^A-Za-z0-9_-]"), "_")
    }
}
