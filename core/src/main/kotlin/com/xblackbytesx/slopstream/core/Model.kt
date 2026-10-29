package com.xblackbytesx.slopstream.core

import com.xblackbytesx.slopstream.core.playback.VideoCandidate
import java.time.Instant

/**
 * A streaming service. The id is persisted in every row and in future sync payloads, so it
 * never changes once shipped.
 */
enum class SourceKey(val id: String, val label: String) {
    YOUTUBE("yt", "YouTube"),
    FLOATPLANE("fp", "Floatplane"),
    SAUCEPLUS("sp", "Sauce+"),
    ;

    companion object {
        fun fromId(id: String): SourceKey = entries.first { it.id == id }
    }
}

/** Where an upload belongs. Shorts never appear in the main feed (AGENTS.md invariant). */
enum class VideoKind {
    /** A regular upload. */
    VIDEO,
    SHORT,

    /** Live right now. */
    LIVE,

    /** A scheduled premiere or livestream that has not started. */
    UPCOMING,
}

data class Channel(
    val source: SourceKey,
    val remoteId: String,
    val name: String,
    val url: String,
    val avatarUrl: String? = null,
    val subscriberCount: Long? = null,
)

data class VideoItem(
    val source: SourceKey,
    val remoteId: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val publishedAt: Instant,
    val kind: VideoKind,
    val thumbnailUrl: String?,
    val durationSec: Long? = null,
    /** True when the date came from a relative label ("3 days ago") instead of a timestamp. */
    val dateApproximate: Boolean = false,
    /**
     * The service gave no date at all (YouTube's Shorts tab): [publishedAt] only keeps the list
     * in order and is never shown.
     */
    val dateUnknown: Boolean = false,
)

/** One channel's recent uploads, already split by kind. */
data class ChannelFeed(
    val source: SourceKey,
    val channelId: String,
    val channelName: String?,
    val items: List<VideoItem>,
    /**
     * Kinds [items] lists completely: cached items of these kinds that are missing here are
     * gone (a livestream that ended). Empty for feeds that only show a recent window.
     */
    val completeKinds: Set<VideoKind> = emptySet(),
)

data class SourceCapabilities(
    val needsLogin: Boolean,
    val hasShorts: Boolean,
    val hasLive: Boolean,
    val canSearchChannels: Boolean,
    val canSearchVideos: Boolean = false,
    /**
     * Subscriptions come from the signed-in account ([ContentSource.accountSubscriptions]) and
     * are managed on the service's site: no adding, importing or unsubscribing here.
     */
    val accountSubscriptions: Boolean = false,
    /** Resolving playback is cheap enough to do on focus. Off where the service rate limits it. */
    val prefetchPlayback: Boolean = true,
    /**
     * Every channel is listed ([ContentSource.catalog]): adding one is picking from that list,
     * with "Add all". Sauce+, whose one subscription covers its whole (small) catalog.
     */
    val catalog: Boolean = false,
    /** Other apps' subscription exports can be imported (NewPipe, Google Takeout). */
    val importsSubscriptions: Boolean = false,
    /** A video's comments can be read ([ContentSource.comments]), when the user turns them on. */
    val hasComments: Boolean = false,
    /** The feed lacks durations and [ContentSource.durations] can fill them in (YouTube's RSS). */
    val enrichesDurations: Boolean = false,
    /** Kinds whose older uploads a channel page can load, page by page ([ContentSource.channelPage]). */
    val archiveKinds: Set<VideoKind> = emptySet(),
    /** A video can be saved on the device for offline viewing ([ContentSource.downloadOffer]). */
    val canDownload: Boolean = false,
)

/**
 * One comment, as plain text. Loaded only when someone opens a video's comments, kept only in
 * memory while they are open, never stored.
 */
data class Comment(
    val id: String,
    val author: String,
    val avatarUrl: String?,
    val text: String,
    /** Null when the service does not say. */
    val likes: Int?,
    /** As the service words it ("3 days ago"). */
    val published: String?,
    val replyCount: Int,
    val pinned: Boolean,
    val heartedByUploader: Boolean,
    val byUploader: Boolean,
    /** Where its replies start; null without replies. */
    val replies: CommentsPage.Cursor?,
    /** When it was posted, for a service that sends a time instead of [published] wording. */
    val postedAt: Instant? = null,
)

data class CommentsPage(
    val comments: List<Comment>,
    val next: Cursor?,
    /** The uploader turned comments off. */
    val disabled: Boolean = false,
) {
    /** Where a page starts. Opaque: only the source that made it reads it. */
    class Cursor(val handle: Any)
}

/** One page of a channel's uploads of one kind, newest first ([ContentSource.channelPage]). */
data class ArchivePage(
    val items: List<VideoItem>,
    /** Where the next, older page starts; null at the channel's first upload. */
    val next: Cursor?,
) {
    /** Opaque: only the source that made it reads it. */
    class Cursor(val handle: Any)
}

/** A subscription read from another app's export, before it is stored. */
data class ImportedSubscription(
    val source: SourceKey,
    val channelId: String,
    val name: String,
)

/**
 * What a video can be downloaded as (decision 24): its video streams the device can play, best
 * first, and the audio that goes with them. Each is one whole file, fetched by byte range.
 * Without [audio] each video file carries its own sound (Floatplane's MP4s, decision 26); an
 * audio-only download then takes the smallest one and keeps its sound alone.
 */
data class DownloadOffer(
    val videoId: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val durationSec: Long,
    val thumbnailUrl: String?,
    val videos: List<DownloadStream>,
    val audio: DownloadStream?,
) {
    /** Where an audio-only download comes from: the audio stream, or the smallest video file. */
    val audioSource: DownloadStream? get() = audio ?: videos.minByOrNull { it.sizeBytes ?: Long.MAX_VALUE }

    /** What an audio-only download ends up taking on the device. */
    val audioOnlySize: Long? get() = audio?.sizeBytes ?: audioSource?.audioSizeBytes
}

/** One downloadable file. [url] expires within hours: [ContentSource.downloadStream] renews it. */
data class DownloadStream(
    /** Stable across URL renewals: what [ContentSource.downloadStream] looks up. */
    val id: String,
    val url: String,
    /** File name extension: webm, mp4, m4a. */
    val extension: String,
    /** Exact when the service says, else estimated from the bitrate; null when neither is known. */
    val sizeBytes: Long?,
    val sizeExact: Boolean,
    /** Null for audio. */
    val video: VideoCandidate? = null,
    /** A video file with its sound in it: the sound's share of [sizeBytes], estimated. */
    val audioSizeBytes: Long? = null,
)
