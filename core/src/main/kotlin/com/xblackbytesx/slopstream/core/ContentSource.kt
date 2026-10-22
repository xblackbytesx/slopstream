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

    /** A channel URL, handle (@name) or id, as typed or pasted by the user. */
    suspend fun resolveChannel(input: String): Channel

    /** Recent uploads of one channel: regular videos, Shorts and live, each tagged by kind. */
    suspend fun fetchChannelFeed(channelId: String): ChannelFeed

    suspend fun resolvePlayback(videoId: String, profile: DeviceProfile, prefs: PlaybackPrefs): PlaybackPlan

    /** The same plan playing another of its [PlaybackPlan.Adaptive.videoOptions] (quality menu). */
    suspend fun switchVideo(plan: PlaybackPlan.Adaptive, candidateId: String): PlaybackPlan.Adaptive
}

open class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The service refused to serve us (rate limit, bot check). Back off rather than retry. */
class SourceBlockedException(message: String, cause: Throwable? = null) : SourceException(message, cause)

/** The account is signed out or its session expired: sign in again, never retry. */
class SourceAuthException(message: String, cause: Throwable? = null) : SourceException(message, cause)

/** The thing is gone or private: not an error worth retrying. */
class SourceUnavailableException(message: String, cause: Throwable? = null) : SourceException(message, cause)
