package com.xblackbytesx.slopstream.core.playback

/** One video-only stream a service offers, reduced to what selection needs. */
data class VideoCandidate(
    val id: String,
    val codecs: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val hdr: Boolean,
) {
    val codec: VideoCodec get() = VideoCodec.fromCodecs(codecs)

    /** The "p" number people know: the short side, so a 1080x1920 Short is 1080p. */
    val lines: Int get() = minOf(width, height)

    val label: String
        get() = buildString {
            append(lines).append('p')
            if (fps > 30) append(fps)
            if (hdr) append(" HDR")
            append(" · ").append(codec.name)
        }
}

enum class AudioTrackRole { ORIGINAL, DUBBED, DESCRIPTIVE, SECONDARY, UNKNOWN }

data class AudioCandidate(
    val id: String,
    val codecs: String,
    val bitrate: Int,
    val role: AudioTrackRole,
    val language: String? = null,
) {
    val codec: AudioCodec get() = AudioCodec.fromCodecs(codecs)
}

data class PlaybackPrefs(
    val cap: QualityCap = QualityCap.BEST,
    val allowHdr: Boolean = true,
)

/**
 * Picks the stream to play. Pure, so every rule here is covered by StreamSelectorTest.
 *
 * Video: the highest resolution the device decodes in hardware, within the cap, then the higher
 * frame rate, then the more efficient codec (AV1 > VP9 > HEVC > H.264), then bitrate. HDR only
 * when the display and a decoder both support it and the user allows it. If nothing fits in
 * hardware (an odd device), the smallest H.264 stream still plays in software.
 *
 * Audio: the original track, never an auto-dub or audio description, at the highest bitrate.
 */
object StreamSelector {

    private val codecRank = listOf(VideoCodec.AV1, VideoCodec.VP9, VideoCodec.HEVC, VideoCodec.H264)

    /** What the device can play, best first: the quality menu. Ignores the cap on purpose. */
    fun playable(candidates: List<VideoCandidate>, profile: DeviceProfile, prefs: PlaybackPrefs): List<VideoCandidate> {
        val hdrOk = prefs.allowHdr && profile.hdrDisplay
        return candidates
            .filter { c ->
                val dec = profile.hardware(c.codec) ?: return@filter false
                dec.supports(c.width, c.height, c.fps) && (!c.hdr || (hdrOk && dec.hdr))
            }
            .sortedWith(ranking)
            // One entry per resolution, frame rate and HDR: the menu shows the best codec of each.
            .distinctBy { Triple(it.lines, it.fps, it.hdr) }
    }

    private val ranking: Comparator<VideoCandidate> =
        compareByDescending<VideoCandidate> { it.lines }
            .thenByDescending { it.fps }
            .thenByDescending { if (it.hdr) 1 else 0 }
            .thenBy { codecRank.indexOf(it.codec).let { i -> if (i < 0) codecRank.size else i } }
            .thenByDescending { it.bitrate }

    fun selectVideo(
        candidates: List<VideoCandidate>,
        profile: DeviceProfile,
        prefs: PlaybackPrefs,
    ): VideoCandidate? {
        if (candidates.isEmpty()) return null
        val capLines = prefs.cap.maxLines ?: Int.MAX_VALUE
        val hdrOk = prefs.allowHdr && profile.hdrDisplay

        val playable = candidates.filter { c ->
            val dec = profile.hardware(c.codec) ?: return@filter false
            dec.supports(c.width, c.height, c.fps) && (!c.hdr || (hdrOk && dec.hdr))
        }
        val capped = playable.filter { it.lines <= capLines }
        // A cap below every stream (720 cap, 1080-only upload) still plays the smallest one.
        val pool = capped.ifEmpty { playable.minByOrNull { it.lines }?.let { min -> playable.filter { it.lines == min.lines } }.orEmpty() }

        if (pool.isNotEmpty()) return pool.sortedWith(ranking).first()

        return candidates
            .filter { it.codec == VideoCodec.H264 && !it.hdr }
            .minWithOrNull(compareBy<VideoCandidate> { it.lines }.thenBy { it.fps })
    }

    fun selectAudio(candidates: List<AudioCandidate>): AudioCandidate? {
        if (candidates.isEmpty()) return null
        val roleRank = listOf(AudioTrackRole.ORIGINAL, AudioTrackRole.UNKNOWN, AudioTrackRole.SECONDARY, AudioTrackRole.DUBBED, AudioTrackRole.DESCRIPTIVE)
        val bestRole = candidates.minOf { roleRank.indexOf(it.role) }
        return candidates
            .filter { roleRank.indexOf(it.role) == bestRole }
            .sortedWith(
                compareByDescending<AudioCandidate> { it.bitrate }
                    .thenBy { if (it.codec == AudioCodec.OPUS) 0 else 1 },
            )
            .first()
    }
}
