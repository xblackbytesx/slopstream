package com.xblackbytesx.slopstream.core

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
)

/** A subscription read from another app's export, before it is stored. */
data class ImportedSubscription(
    val source: SourceKey,
    val channelId: String,
    val name: String,
)
