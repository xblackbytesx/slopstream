package com.xblackbytesx.slopstream.core.playback

/**
 * What the player needs to start a video. Services produce it; the app turns it into Media3
 * sources without knowing which service it came from.
 */
sealed interface PlaybackPlan {
    val videoId: String
    val title: String
    val durationSec: Long

    /** Separate video and audio tracks, merged at playback. */
    data class Adaptive(
        override val videoId: String,
        override val title: String,
        override val durationSec: Long,
        val video: Track,
        val audio: Track,
        /** The candidate [video] plays. */
        val chosen: VideoCandidate,
        /** Every choice the device decodes, best first, for the in-player quality menu. */
        val videoOptions: List<VideoCandidate>,
    ) : PlaybackPlan

    /**
     * An HLS or DASH manifest URL. Livestreams: the player's ABR picks within the cap. Services
     * that serve one playlist per quality (Floatplane) list them in [variants]; [url] is the
     * chosen one's and the quality menu swaps it without asking the service again.
     */
    data class Manifest(
        override val videoId: String,
        override val title: String,
        override val durationSec: Long,
        val url: String,
        val type: ManifestType,
        val live: Boolean,
        val variants: List<ManifestVariant> = emptyList(),
        val chosen: VideoCandidate? = null,
        /**
         * Sent only to requests for these hosts (an HLS key server that wants the account),
         * never to the CDN. Held in memory with the plan, never stored.
         */
        val authHosts: Set<String> = emptySet(),
        val authHeaders: Map<String, String> = emptyMap(),
        /**
         * Requests to a key host (on the left) go to another host (on the right) first. Sauce+
         * playlists name Floatplane's key server, which only answers on www.sauceplus.com for
         * a Sauce+ member.
         */
        val hostRewrites: Map<String, String> = emptyMap(),
    ) : PlaybackPlan {
        fun withVariant(candidateId: String): Manifest {
            val v = variants.firstOrNull { it.candidate.id == candidateId } ?: return this
            return copy(url = v.url, chosen = v.candidate)
        }
    }

    /**
     * A download on this device (decision 24): the video file and the audio file, merged at
     * playback like [Adaptive]; one file with both (Floatplane, [audioPath] null); or the audio
     * alone ([audioOnly], [videoPath] null), which plays like a podcast, on with the screen off.
     * An audio-only file may still hold a picture track: the player leaves it undecoded. Paths
     * are absolute; nothing here touches the network.
     */
    data class Local(
        override val videoId: String,
        override val title: String,
        override val durationSec: Long,
        val videoPath: String?,
        val audioPath: String?,
        val quality: VideoCandidate?,
        val channelName: String = "",
        /** The saved thumbnail, for the player and the media notification. */
        val artworkPath: String? = null,
    ) : PlaybackPlan {
        val audioOnly: Boolean get() = videoPath == null
    }

    /** One muxed file: last resort when a video has no adaptive streams. */
    data class Progressive(
        override val videoId: String,
        override val title: String,
        override val durationSec: Long,
        val url: String,
    ) : PlaybackPlan
}

enum class ManifestType { HLS, DASH }

/** One quality of a [PlaybackPlan.Manifest]: its own playlist. */
data class ManifestVariant(val candidate: VideoCandidate, val url: String)

/** What the in-player quality menu offers, best first; one entry or none hides the menu. */
val PlaybackPlan.qualityOptions: List<VideoCandidate>
    get() = when (this) {
        is PlaybackPlan.Adaptive -> videoOptions
        is PlaybackPlan.Manifest -> variants.map { it.candidate }
        is PlaybackPlan.Local, is PlaybackPlan.Progressive -> emptyList()
    }

val PlaybackPlan.chosenQuality: VideoCandidate?
    get() = when (this) {
        is PlaybackPlan.Adaptive -> chosen
        is PlaybackPlan.Manifest -> chosen
        is PlaybackPlan.Local -> quality
        is PlaybackPlan.Progressive -> null
    }

/**
 * A single media track. [dashManifest] is a ready MPD document when the service needs one built
 * (YouTube itags); otherwise [url] is fetched directly.
 */
data class Track(
    val url: String,
    val mimeType: String,
    val codecs: String,
    val dashManifest: String?,
    /** Stable across URL refreshes, for the player cache. */
    val cacheKey: String,
)
