package com.xblackbytesx.slopstream.data.download

import com.xblackbytesx.slopstream.youtube.StreamRequests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Fetches one file in byte ranges of [chunkBytes] into `<target>.part`, then renames it to
 * [target]. A part file left by an earlier run is continued where it stopped. googlevideo.com
 * gets its ranges the way the player asks for them ([StreamRequests]): YouTube throttles one
 * long request, not many short ones. An expired URL (403, 410) is renewed once per position.
 */
class RangeFetcher(private val client: OkHttpClient, private val chunkBytes: Long = CHUNK_BYTES) {

    /** Thrown when the server refuses the renewed URL too. */
    class ExpiredException(message: String) : IOException(message)

    /**
     * @param expected the file's exact size when the service says; null reads to the end.
     * @param url the stream URL; called again with `renew = true` after a refusal.
     * @param onProgress bytes of this file on disk so far.
     * @return the finished file's size.
     */
    suspend fun fetch(
        target: File,
        expected: Long?,
        url: suspend (renew: Boolean) -> String,
        onProgress: suspend (Long) -> Unit = {},
    ): Long {
        if (target.exists()) return target.length()
        val part = File(target.path + ".part")
        var current = url(false)
        var renewedAt = -1L
        var requestNumber = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val position = part.length()
            if (expected != null && position >= expected) break
            val length = expected?.let { minOf(chunkBytes, it - position) } ?: chunkBytes
            val got = chunk(current, part, position, length, requestNumber++, onProgress)
            when {
                got == EXPIRED -> {
                    if (renewedAt == position) throw ExpiredException("The stream kept refusing (HTTP 403)")
                    renewedAt = position
                    current = url(true)
                }
                got == END -> break
                // Short of what was asked: the end of a file of unknown size.
                expected == null && got < length -> break
                got == 0L -> break
            }
        }
        val size = part.length()
        if (expected != null && size != expected) throw IOException("Download incomplete: $size of $expected bytes")
        if (!part.renameTo(target)) throw IOException("Could not finish ${target.name}")
        return size
    }

    /** Appends one range to [part]; the bytes added, or [EXPIRED] or [END]. */
    private suspend fun chunk(url: String, part: File, position: Long, length: Long, requestNumber: Long, onProgress: suspend (Long) -> Unit): Long =
        withContext(Dispatchers.IO) {
            val youtube = StreamRequests.isVideoPlayback(url)
            val request = if (youtube) {
                val shaped = StreamRequests.shape(url, position, length, requestNumber)
                Request.Builder().url(shaped.url).post(shaped.body.toRequestBody()).apply { shaped.headers.forEach { (k, v) -> header(k, v) } }.build()
            } else {
                Request.Builder().url(url).header("Range", "bytes=$position-${position + length - 1}").build()
            }
            client.newCall(request).execute().use { r ->
                when {
                    r.code == 403 || r.code == 410 -> return@withContext EXPIRED
                    r.code == 416 -> return@withContext END
                    !r.isSuccessful -> throw IOException("Download failed (HTTP ${r.code})")
                }
                // A server that ignores Range answers 200 with the whole file: start over.
                val restart = !youtube && r.code == 200 && position > 0
                var added = 0L
                var reported = 0L
                FileOutputStream(part, !restart).use { out ->
                    val input = r.body.byteStream()
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        added += n
                        if (added - reported >= REPORT_BYTES) {
                            reported = added
                            onProgress((if (restart) 0 else position) + added)
                        }
                    }
                }
                onProgress(part.length())
                added
            }
        }

    companion object {
        /** About what NewPipe asks for per request; YouTube serves these at full speed. */
        const val CHUNK_BYTES = 10L * 1024 * 1024
        private const val BUFFER = 64 * 1024
        private const val REPORT_BYTES = 512L * 1024
        private const val EXPIRED = -1L
        private const val END = -2L
    }
}
