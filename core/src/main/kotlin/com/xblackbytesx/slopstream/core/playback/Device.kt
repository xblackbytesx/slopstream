package com.xblackbytesx.slopstream.core.playback

enum class VideoCodec {
    AV1,
    VP9,
    HEVC,
    H264,
    OTHER,
    ;

    companion object {
        /** Maps an RFC 6381 codecs string ("av01.0.08M.08", "vp09.00.51.08", "avc1.640028"). */
        fun fromCodecs(codecs: String?): VideoCodec {
            val c = codecs?.lowercase()?.trim() ?: return OTHER
            return when {
                c.startsWith("av01") -> AV1
                c.startsWith("vp09") || c.startsWith("vp9") -> VP9
                c.startsWith("hev1") || c.startsWith("hvc1") -> HEVC
                c.startsWith("avc1") || c.startsWith("avc3") -> H264
                else -> OTHER
            }
        }

        /**
         * True when the codecs string declares a PQ (16) or HLG (18) transfer function. Short
         * forms carry no colour fields, so VP9 profile 2 (10-bit) counts as HDR on its own.
         */
        fun isHdr(codecs: String?): Boolean {
            val parts = codecs?.lowercase()?.trim()?.split('.') ?: return false
            val transferIndex = when (parts.firstOrNull()) {
                "vp09" -> 6
                "av01" -> 7
                else -> return false
            }
            val transfer = parts.getOrNull(transferIndex)?.toIntOrNull()
            return when {
                transfer != null -> transfer == 16 || transfer == 18
                else -> parts[0] == "vp09" && parts.getOrNull(1) == "02"
            }
        }
    }
}

enum class AudioCodec {
    OPUS,
    AAC,
    OTHER,
    ;

    companion object {
        fun fromCodecs(codecs: String?): AudioCodec {
            val c = codecs?.lowercase()?.trim() ?: return OTHER
            return when {
                c.startsWith("opus") -> OPUS
                c.startsWith("mp4a") -> AAC
                else -> OTHER
            }
        }
    }
}

/**
 * What one hardware decoder can do, probed at the standard ladder sizes. [supports] answers
 * for any size by rounding up to the next probed rung, so a 1920x800 film counts as 1080p.
 */
data class DecoderCaps(
    val codec: VideoCodec,
    val hardware: Boolean,
    /** Short side of the frame ("p" value) to the highest frame rate it decodes at that size. */
    val maxFpsByLines: Map<Int, Int>,
    val hdr: Boolean = false,
) {
    fun supports(width: Int, height: Int, fps: Int): Boolean {
        val lines = minOf(width, height).takeIf { it > 0 } ?: return false
        val rung = maxFpsByLines.keys.sorted().firstOrNull { it >= lines } ?: return false
        return (maxFpsByLines[rung] ?: 0) >= fps.coerceAtLeast(1)
    }

    val maxLines: Int get() = maxFpsByLines.filterValues { it > 0 }.keys.maxOrNull() ?: 0
}

/**
 * The result of the device check (first start, rerun from Settings). Stored, shown to the user,
 * and the only input playback uses besides the user's quality cap.
 */
data class DeviceProfile(
    val decoders: List<DecoderCaps>,
    /** The display can show HDR10 or HLG. */
    val hdrDisplay: Boolean,
    /** Short side of the largest display mode. */
    val displayLines: Int,
    /** Measured download speed, null when the test could not run. */
    val throughputKbps: Int?,
    /** Television: match the display refresh rate to the video when the device allows it. */
    val matchFrameRate: Boolean,
) {
    fun hardware(codec: VideoCodec): DecoderCaps? = decoders.firstOrNull { it.codec == codec && it.hardware }

    companion object {
        /** Used before the first device check finishes: every phone and TV since 2015 does this. */
        val CONSERVATIVE = DeviceProfile(
            decoders = listOf(
                DecoderCaps(VideoCodec.H264, hardware = true, maxFpsByLines = mapOf(480 to 60, 720 to 60, 1080 to 30)),
            ),
            hdrDisplay = false,
            displayLines = 1080,
            throughputKbps = null,
            matchFrameRate = false,
        )
    }
}

/** The user's quality ceiling. [BEST] means the highest the device decodes in hardware. */
enum class QualityCap(val maxLines: Int?) {
    BEST(null),
    P2160(2160),
    P1440(1440),
    P1080(1080),
    P720(720),
    ;

    companion object {
        /**
         * The cap the device check recommends: the link has to carry the chosen bitrate with
         * headroom. YouTube's VP9/AV1 tiers run roughly 2160p ~20 Mbit/s, 1440p ~10, 1080p ~5.
         */
        fun recommendedFor(throughputKbps: Int?): QualityCap = when {
            throughputKbps == null -> BEST
            throughputKbps >= 35_000 -> BEST
            throughputKbps >= 18_000 -> P1440
            throughputKbps >= 8_000 -> P1080
            else -> P720
        }
    }
}
