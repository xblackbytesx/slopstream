package com.xblackbytesx.slopstream.youtube.innertube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Base64

/** What playback reads from `/youtubei/v1/player`. Pure: [parse] is covered by fixtures. */
data class PlayerResponse(
    /** `OK`, `LOGIN_REQUIRED`, `UNPLAYABLE`, `ERROR`, `LIVE_STREAM_OFFLINE`, ... */
    val status: String,
    val reason: String?,
    val title: String,
    /** The creator's description, plain text (`shortDescription` is the whole of it). */
    val description: String,
    val views: Long?,
    val channelId: String,
    /** The channel's name. */
    val author: String,
    val durationSec: Long,
    val live: Boolean,
    /** Ended stream still being processed: its formats are DVR segments, not files. */
    val postLive: Boolean,
    val formats: List<Format>,
    val hlsManifestUrl: String?,
    /** The cpn sent with the request; every stream URL needs it appended. */
    val cpn: String,
) {
    val ok: Boolean get() = status == "OK"

    /** YouTube wants a signed-in "not a bot" proof: this visitor or IP is flagged, not the video. */
    val botCheck: Boolean get() = status == "LOGIN_REQUIRED" && reason.orEmpty().contains("bot", ignoreCase = true)

    data class Format(
        val itag: Int,
        /** With `&cpn=` already appended. */
        val url: String,
        val mimeType: String,
        val codecs: String,
        val bitrate: Int,
        val width: Int,
        val height: Int,
        val fps: Int,
        val contentLength: Long?,
        val initRange: LongRange?,
        val indexRange: LongRange?,
        /** Streamed in segments (fresh uploads): no byte ranges, needs a different manifest. */
        val otf: Boolean,
        val hdr: Boolean,
        val audioChannels: Int,
        val audioSampleRate: Int,
        val audioTrackId: String?,
        /** From `xtags` `acont`: original, dubbed, dubbed-auto, descriptive, secondary. */
        val audioContent: String?,
        val audioDefault: Boolean,
        /** Loudness-compressed duplicate of an audio stream. */
        val drc: Boolean,
    ) {
        val isVideo: Boolean get() = mimeType.startsWith("video/")
        val isAudio: Boolean get() = mimeType.startsWith("audio/")
        /** `video/webm`, without the codecs part. */
        val container: String get() = mimeType.substringBefore(';').trim()
    }

    companion object {
        fun parse(root: JsonObject, cpn: String): PlayerResponse {
            val playability = root.obj("playabilityStatus")
            val details = root.obj("videoDetails")
            val streaming = root.obj("streamingData")
            val formats = (streaming?.arr("adaptiveFormats").orEmpty())
                .mapNotNull { (it as? JsonObject)?.let { f -> format(f, cpn) } }
            return PlayerResponse(
                status = playability?.str("status") ?: "ERROR",
                reason = playability?.str("reason")
                    ?: playability?.obj("errorScreen")?.obj("playerErrorMessageRenderer")?.obj("reason")?.str("simpleText"),
                title = details?.str("title").orEmpty(),
                description = details?.str("shortDescription").orEmpty(),
                views = details?.str("viewCount")?.toLongOrNull(),
                channelId = details?.str("channelId").orEmpty(),
                author = details?.str("author").orEmpty(),
                durationSec = details?.str("lengthSeconds")?.toLongOrNull() ?: 0,
                live = details?.bool("isLive") == true,
                postLive = details?.bool("isPostLiveDvr") == true,
                formats = formats,
                hlsManifestUrl = streaming?.str("hlsManifestUrl"),
                cpn = cpn,
            )
        }

        private fun format(f: JsonObject, cpn: String): Format? {
            // A signatureCipher (no plain url) needs the web player's JavaScript: not this client.
            val url = f.str("url") ?: return null
            val mime = f.str("mimeType").orEmpty()
            val codecs = Regex("codecs=\"([^\"]+)\"").find(mime)?.groupValues?.get(1).orEmpty()
            val transfer = f.obj("colorInfo")?.str("transferCharacteristics").orEmpty()
            val audioTrack = f.obj("audioTrack")
            return Format(
                itag = f.int("itag") ?: return null,
                url = "$url&cpn=$cpn",
                mimeType = mime,
                codecs = codecs,
                bitrate = f.int("averageBitrate") ?: f.int("bitrate") ?: 0,
                width = f.int("width") ?: 0,
                height = f.int("height") ?: 0,
                fps = f.int("fps") ?: 0,
                contentLength = f.str("contentLength")?.toLongOrNull(),
                initRange = range(f.obj("initRange")),
                indexRange = range(f.obj("indexRange")),
                otf = f.str("type") == "FORMAT_STREAM_TYPE_OTF",
                hdr = "SMPTEST2084" in transfer || "ARIB_STD_B67" in transfer || "HDR" in f.str("qualityLabel").orEmpty(),
                audioChannels = f.int("audioChannels") ?: 2,
                audioSampleRate = f.str("audioSampleRate")?.toIntOrNull() ?: 0,
                audioTrackId = audioTrack?.str("id"),
                audioContent = f.str("xtags")?.let(::audioContent),
                audioDefault = audioTrack?.bool("audioIsDefault") == true,
                drc = f.bool("isDrc") == true,
            )
        }

        private fun range(o: JsonObject?): LongRange? {
            val start = o?.str("start")?.toLongOrNull() ?: return null
            val end = o.str("end")?.toLongOrNull() ?: return null
            return start..end
        }

        /**
         * `xtags` is a base64url protobuf of key/value pairs; `acont` holds the audio's kind.
         * Read just enough of it: a length-prefixed "acont" key, then its length-prefixed value.
         */
        internal fun audioContent(xtags: String): String? {
            val bytes = runCatching { Base64.getUrlDecoder().decode(xtags.trimEnd('=')) }.getOrNull() ?: return null
            val text = String(bytes, Charsets.ISO_8859_1)
            val at = text.indexOf("acont").takeIf { it >= 0 } ?: return null
            var i = at + "acont".length
            if (i + 2 > text.length || text[i] != '\u0012') return null
            val length = text[i + 1].code
            i += 2
            if (i + length > text.length) return null
            return text.substring(i, i + length)
        }

        private fun JsonObject.obj(key: String) = this[key] as? JsonObject
        private fun JsonObject.arr(key: String) = this[key] as? JsonArray
        private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
        private fun JsonObject.str(key: String): String? = prim(key)?.contentOrNull
        private fun JsonObject.int(key: String): Int? = prim(key)?.intOrNull
        private fun JsonObject.bool(key: String): Boolean? = prim(key)?.booleanOrNull
    }
}
