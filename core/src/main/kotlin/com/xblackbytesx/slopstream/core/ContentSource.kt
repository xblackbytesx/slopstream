package com.xblackbytesx.slopstream.core

import com.xblackbytesx.slopstream.core.playback.DeviceProfile
import com.xblackbytesx.slopstream.core.playback.PlaybackPlan
import com.xblackbytesx.slopstream.core.playback.PlaybackPrefs

/**
 * One streaming service. YouTube is the first; Floatplane and Sauce+ add a login step on top of
 * the same contract. Every call is blocking network I/O behind `suspend`, run on Dispatchers.IO
 * by the implementation, and throws [SourceException] for anything the user should see.
 */
interface ContentSource {
    val key: SourceKey
    val capabilities: SourceCapabilities

    suspend fun searchChannels(query: String): List<Channel>

    /**
     * The service's own video search, beyond the user's subscriptions. Only ever run when the
     * user asks for it on the Search screen ([SourceCapabilities.canSearchVideos]).
     */
    suspend fun searchVideos(query: String): List<VideoItem>

    /** The channels the signed-in account subscribes to ([SourceCapabilities.accountSubscriptions]). */
    suspend fun accountSubscriptions(): List<Channel> = emptyList()

    /** Every channel there is ([SourceCapabilities.catalog]), to pick from. */
    suspend fun catalog(): List<Channel> = emptyList()

    /** A channel URL, handle (@name) or id, as typed or pasted by the user. */
    suspend fun resolveChannel(input: String): Channel

    /** Recent uploads of one channel: regular videos, Shorts and live, each tagged by kind. */
    suspend fun fetchChannelFeed(channelId: String): ChannelFeed

    suspend fun resolvePlayback(videoId: String, profile: DeviceProfile, prefs: PlaybackPrefs): PlaybackPlan

    /** The same plan playing another of its [PlaybackPlan.Adaptive.videoOptions] (quality menu). */
    suspend fun switchVideo(plan: PlaybackPlan.Adaptive, candidateId: String): PlaybackPlan.Adaptive

    /**
     * A page of a video's comments in the service's own order, or of one comment's replies
     * ([Comment.replies]); the first page when [from] is null ([SourceCapabilities.hasComments]).
     */
    suspend fun comments(videoId: String, from: CommentsPage.Cursor? = null): CommentsPage =
        throw SourceUnavailableException("This service has no comments here")

    /**
     * Durations of a channel's recent uploads, video id to seconds
     * ([SourceCapabilities.enrichesDurations]): what the feed itself does not carry.
     */
    suspend fun durations(channelId: String): Map<String, Long> = emptyMap()

    /** What [videoId] can be downloaded as on this device ([SourceCapabilities.canDownload]). */
    suspend fun downloadOffer(videoId: String, profile: DeviceProfile, prefs: PlaybackPrefs): DownloadOffer =
        throw SourceUnavailableException("This service has no downloads here")

    /** One stream of [downloadOffer] again, by its [DownloadStream.id], with a fresh URL. */
    suspend fun downloadStream(videoId: String, streamId: String): DownloadStream =
        throw SourceUnavailableException("This service has no downloads here")

    /**
     * A channel's uploads of one [kind], a page at a time from the newest back to the first
     * ([SourceCapabilities.archiveKinds]); the first page when [from] is null. Only run when the
     * user asks for older videos on a channel page, never by a refresh.
     */
    suspend fun channelPage(channelId: String, kind: VideoKind, from: ArchivePage.Cursor? = null): ArchivePage =
        throw SourceUnavailableException("This service has no older videos here")
}

open class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The service refused to serve us (rate limit, bot check). Back off rather than retry. */
class SourceBlockedException(message: String, cause: Throwable? = null) : SourceException(message, cause)

/** The account is signed out or its session expired: sign in again, never retry. */
class SourceAuthException(message: String, cause: Throwable? = null) : SourceException(message, cause)

/** The thing is gone or private: not an error worth retrying. */
class SourceUnavailableException(message: String, cause: Throwable? = null) : SourceException(message, cause)
