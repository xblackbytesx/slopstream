package com.xblackbytesx.slopstream.data.download

import com.xblackbytesx.slopstream.core.Segmented
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Saves an HLS stream as one file (decision 26): every segment of the media playlist in turn,
 * AES-128 decrypted, appended to `<target>.part`, then renamed to [target]. MPEG-TS segments
 * joined are one valid TS file; fMP4 ones after their init segment are one valid MP4. For
 * services that switch their download files off (Sauce+, 2026-11-09) but stream to the
 * account: this is the stream the player plays, kept.
 *
 * Resumable: `<target>.part.hls` holds how many segments, and how many bytes, are on disk, so
 * a later run cuts a half-written segment off and goes on with the next. An expired playlist
 * token (403, 410) is renewed once per segment.
 */
class HlsFetcher(private val client: OkHttpClient) {

    suspend fun fetch(
        target: File,
        access: Segmented,
        url: suspend (renew: Boolean) -> String,
        onProgress: suspend (Long) -> Unit = {},
    ): Long {
        if (target.exists()) return target.length()
        val part = File(target.path + ".part")
        val state = File(target.path + ".part.hls")
        val (doneSegments, doneBytes) = resume(part, state)
        var parts = segments(url(false), access)
        if (parts.isEmpty()) throw IOException("The stream has no segments")
        var index = doneSegments
        var renewedAt = -1
        val keys = HashMap<String, ByteArray>()
        onProgress(doneBytes)
        while (index < parts.size) {
            currentCoroutineContext().ensureActive()
            val segment = parts[index]
            val bytes = get(segment.url, access)
            if (bytes == null) {
                if (renewedAt == index) throw RangeFetcher.ExpiredException("The stream kept refusing (HTTP 403)")
                renewedAt = index
                parts = segments(url(true), access)
                continue
            }
            val plain = segment.key?.let { decrypt(bytes, keyBytes(it.uri, access, keys), it.iv ?: sequenceIv(segment.sequence)) } ?: bytes
            withContext(Dispatchers.IO) {
                FileOutputStream(part, true).use { it.write(plain) }
                index++
                state.writeText("$index ${part.length()}")
            }
            onProgress(part.length())
        }
        val size = part.length()
        if (!part.renameTo(target)) throw IOException("Could not finish ${target.name}")
        state.delete()
        return size
    }

    /** What is on disk from an earlier run; a segment written past the record is cut off. */
    private suspend fun resume(part: File, state: File): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val saved = runCatching { state.readText().trim().split(' ').let { it[0].toInt() to it[1].toLong() } }.getOrNull()
        if (saved == null || !part.exists() || part.length() < saved.second) {
            part.delete()
            state.delete()
            return@withContext 0 to 0L
        }
        if (part.length() > saved.second) RandomAccessFile(part, "rw").use { it.setLength(saved.second) }
        saved
    }

    /** The media playlist's parts, the init segment first; a master playlist is followed to its best variant. */
    private suspend fun segments(playlistUrl: String, access: Segmented): List<HlsPlaylist.Part> {
        var at = playlistUrl.toHttpUrl()
        repeat(2) {
            val text = get(at.toString(), access)?.decodeToString() ?: throw RangeFetcher.ExpiredException("The stream refused its playlist (HTTP 403)")
            when (val p = HlsPlaylist.parse(text, at)) {
                is HlsPlaylist.Media -> return p.parts
                is HlsPlaylist.Master -> at = p.best ?: throw IOException("The stream lists no qualities")
            }
        }
        throw IOException("The stream's playlist points to another playlist again")
    }

    private suspend fun keyBytes(uri: HttpUrl, access: Segmented, cache: HashMap<String, ByteArray>): ByteArray =
        cache[uri.toString()] ?: (get(uri.toString(), access) ?: throw RangeFetcher.ExpiredException("The stream's key was refused (HTTP 403)"))
            .also { if (it.size != 16) throw IOException("The stream's key is not an AES-128 key") }
            .also { cache[uri.toString()] = it }

    /** The body; null when refused (403, 410). */
    private suspend fun get(url: String, access: Segmented): ByteArray? = withContext(Dispatchers.IO) {
        client.newCall(request(url, access)).execute().use { r ->
            when {
                r.code == 403 || r.code == 410 -> null
                !r.isSuccessful -> throw IOException("Download failed (HTTP ${r.code})")
                else -> r.body.bytes()
            }
        }
    }

    companion object {
        /** [Segmented.hostRewrites] first, then its headers for its hosts over https only. */
        internal fun request(url: String, access: Segmented): Request {
            val parsed = url.toHttpUrl()
            val moved = access.hostRewrites[parsed.host]?.takeIf { parsed.isHttps }?.let { parsed.newBuilder().host(it).build() } ?: parsed
            val b = Request.Builder().url(moved)
            if (moved.isHttps && moved.host in access.authHosts) access.authHeaders.forEach { (k, v) -> b.header(k, v) }
            return b.build()
        }

        internal fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
            Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                doFinal(data)
            }

        /** Without an IV attribute, a segment's IV is its media sequence number (RFC 8216 5.2). */
        internal fun sequenceIv(sequence: Long): ByteArray = ByteArray(16).also { iv ->
            for (i in 0 until 8) iv[15 - i] = (sequence ushr (8 * i)).toByte()
        }
    }
}

/** The parts of RFC 8216 a download needs. Pure, so HlsPlaylistTest covers it. */
object HlsPlaylist {
    sealed interface Parsed
    data class Master(val best: HttpUrl?) : Parsed
    data class Media(val parts: List<Part>) : Parsed

    data class Key(val uri: HttpUrl, val iv: ByteArray?) {
        override fun equals(other: Any?) = other is Key && uri == other.uri && iv.contentEquals(other.iv)
        override fun hashCode() = uri.hashCode()
    }

    data class Part(val url: String, val key: Key?, val sequence: Long)

    fun parse(text: String, base: HttpUrl): Parsed {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull() != "#EXTM3U") throw IOException("Not an HLS playlist")
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) return Master(best(lines, base))
        val parts = ArrayList<Part>()
        var sequence = 0L
        var key: Key? = null
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').toLong()
                line.startsWith("#EXT-X-KEY:") -> key = key(attributes(line), base)
                line.startsWith("#EXT-X-MAP:") -> {
                    val a = attributes(line)
                    if ("BYTERANGE" in a) throw IOException("Byte-range HLS can't be downloaded")
                    parts += Part(resolve(base, a["URI"] ?: throw IOException("A map without a URI")), key?.takeIf { it.iv != null }, -1)
                }
                line.startsWith("#EXT-X-BYTERANGE") -> throw IOException("Byte-range HLS can't be downloaded")
                line.startsWith("#") -> Unit
                else -> parts += Part(resolve(base, line), key, sequence++)
            }
        }
        return Media(parts)
    }

    private fun key(a: Map<String, String>, base: HttpUrl): Key? = when (val method = a["METHOD"]) {
        null, "NONE" -> null
        "AES-128" -> Key(
            (base.resolve(a["URI"] ?: throw IOException("A key without a URI")) ?: throw IOException("A key URI that is no URL")),
            a["IV"]?.let(::iv),
        )
        else -> throw IOException("The stream is encrypted with $method, which can't be saved")
    }

    private fun iv(hex: String): ByteArray {
        val digits = hex.removePrefix("0x").removePrefix("0X")
        val raw = BigInteger(digits, 16).toByteArray().takeLast(16).toByteArray()
        return ByteArray(16 - raw.size) + raw
    }

    private fun best(lines: List<String>, base: HttpUrl): HttpUrl? =
        lines.withIndex()
            .filter { it.value.startsWith("#EXT-X-STREAM-INF") }
            .mapNotNull { (i, line) ->
                val uri = lines.getOrNull(i + 1)?.takeIf { !it.startsWith("#") } ?: return@mapNotNull null
                (attributes(line)["BANDWIDTH"]?.toLongOrNull() ?: 0L) to uri
            }
            .maxByOrNull { it.first }
            ?.let { base.resolve(it.second) }

    private fun resolve(base: HttpUrl, uri: String) = (base.resolve(uri) ?: throw IOException("A segment URI that is no URL")).toString()

    /** `KEY=value,KEY="quoted, value"` after the tag's colon. */
    internal fun attributes(line: String): Map<String, String> =
        Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)").findAll(line.substringAfter(':'))
            .associate { m -> m.groupValues[1] to m.groupValues[2].removeSurrounding("\"") }
}
