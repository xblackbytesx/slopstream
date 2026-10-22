package com.xblackbytesx.slopstream.floatplane

import com.xblackbytesx.slopstream.core.Channel
import com.xblackbytesx.slopstream.core.ChannelFeed
import com.xblackbytesx.slopstream.core.ContentSource
import com.xblackbytesx.slopstream.core.SourceAuthException
import com.xblackbytesx.slopstream.core.SourceCapabilities
import com.xblackbytesx.slopstream.core.SourceException
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.core.SourceUnavailableException
import com.xblackbytesx.slopstream.core.VideoItem
import com.xblackbytesx.slopstream.core.VideoKind
import com.xblackbytesx.slopstream.core.playback.DeviceProfile
import com.xblackbytesx.slopstream.core.playback.ManifestType
import com.xblackbytesx.slopstream.core.playback.ManifestVariant
import com.xblackbytesx.slopstream.core.playback.PlaybackPlan
import com.xblackbytesx.slopstream.core.playback.PlaybackPrefs
import com.xblackbytesx.slopstream.core.playback.StreamSelector
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import java.time.Clock

/**
 * Floatplane behind the shared contract.
 *
 * Subscriptions come from the account ([accountSubscriptions]) and are read-only here: they are
 * paid, so they are managed on floatplane.com. Searching and adding channels does not apply.
 */
class FloatplaneSource(
    private val api: FloatplaneApi,
    private val clock: Clock = Clock.systemUTC(),
) : ContentSource {
    override val key = SourceKey.FLOATPLANE
    override val capabilities = SourceCapabilities(
        needsLogin = true,
        hasShorts = false,
        hasLive = true,
        canSearchChannels = false,
        accountSubscriptions = true,
        // delivery/info is rate limited: resolve once per play, never on focus.
        prefetchPlayback = false,
    )

    /** Who is signed in; also the check that a session works. */
    suspend fun username(): String = api.self().username

    suspend fun signOut() = api.logout()

    /** The creators the account pays for, one row each. */
    override suspend fun accountSubscriptions(): List<Channel> =
        api.subscriptions().map { it.creator }.distinct().map { FloatplaneMapping.channel(api.creator(it)) }

    override suspend fun searchChannels(query: String): List<Channel> = emptyList()

    override suspend fun searchVideos(query: String): List<VideoItem> = emptyList()

    override suspend fun resolveChannel(input: String): Channel =
        throw SourceUnavailableException("Floatplane subscriptions come from your account")

    /**
     * [channelId] is a creator id: one request covers all its sub-channels. A livestream that
     * is on right now leads the list as a [VideoKind.LIVE] item ([liveId]).
     */
    override suspend fun fetchChannelFeed(channelId: String): ChannelFeed {
        val creator = api.creator(channelId)
        val posts = api.posts(channelId)
        // Only look sub-channel names up when a post names its channel by id alone.
        val titles = if (posts.any { it.channel !is JsonObject && FloatplaneMapping.channelIdOf(it) != null }) {
            api.channels(listOf(channelId)).associate { it.id to it.title }
        } else {
            emptyMap()
        }
        val live = liveNow(creator)
        val items = listOfNotNull(live.getOrNull()) + FloatplaneMapping.feedItems(creator, posts, titles)
        // Only an answer either way says whether an earlier live item has ended.
        val complete = if (live.isSuccess) setOf(VideoKind.LIVE) else emptySet()
        return ChannelFeed(key, creator.id, creator.title, items, completeKinds = complete)
    }

    /**
     * The creator's stream as a feed item when it is on air, null when off. The delivery
     * endpoint hands out a URL even while offline; the playlist itself is 404 then.
     */
    private suspend fun liveNow(creator: CreatorDto): Result<VideoItem?> {
        val stream = creator.liveStream ?: return Result.success(null)
        return try {
            val url = FloatplaneMapping.liveUrl(api.delivery("live", stream.id))
            val on = url != null && api.status(url) == 200
            Result.success(if (on) FloatplaneMapping.liveItem(creator, stream, clock.instant()) else null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SourceAuthException) {
            throw e
        } catch (e: SourceException) {
            // A rate limit or hiccup here must not cost the creator's videos.
            Result.failure(e)
        }
    }

    /**
     * [videoId] is a post id, or a [liveId]. Floatplane serves one HLS playlist per quality,
     * audio muxed in, so the plan is the chosen variant's playlist and the others are its
     * quality menu. The segments are AES-128 encrypted and the key server wants the account,
     * so the plan carries the credentials for that host only.
     */
    override suspend fun resolvePlayback(videoId: String, profile: DeviceProfile, prefs: PlaybackPrefs): PlaybackPlan {
        FloatplaneMapping.creatorOfLiveId(videoId)?.let { return resolveLive(it) }
        val post = api.post(videoId)
        val video = FloatplaneMapping.firstPlayable(post)
            ?: throw SourceUnavailableException("This post has no playable video yet")
        val variants = FloatplaneMapping.variants(api.delivery("onDemand", video.id))
        val candidates = variants.map { it.candidate }
        val chosen = StreamSelector.selectVideo(candidates, profile, prefs)
            ?: throw SourceUnavailableException("No stream this device can play")
        val menu = StreamSelector.playable(candidates, profile, prefs).ifEmpty { listOf(chosen) }
        return PlaybackPlan.Manifest(
            videoId = videoId,
            title = post.title,
            durationSec = video.duration.toLong(),
            url = variants.first { it.candidate == chosen }.url,
            type = ManifestType.HLS,
            live = false,
            variants = menu.map { c -> ManifestVariant(c, variants.first { it.candidate == c }.url) },
            chosen = chosen,
            authHosts = setOf(api.host),
            authHeaders = api.credentialHeaders(),
        )
    }

    /** The creator's livestream, when it is on. */
    suspend fun resolveLive(creatorId: String): PlaybackPlan.Manifest {
        val stream = api.creator(creatorId).liveStream ?: throw SourceUnavailableException("This creator has no livestream")
        val url = FloatplaneMapping.liveUrl(api.delivery("live", stream.id))
            ?.takeIf { api.status(it) == 200 }
            ?: throw SourceUnavailableException("The livestream is offline")
        return PlaybackPlan.Manifest(FloatplaneMapping.liveId(creatorId), stream.title, 0, url, ManifestType.HLS, live = true)
    }

    override suspend fun switchVideo(plan: PlaybackPlan.Adaptive, candidateId: String): PlaybackPlan.Adaptive =
        throw UnsupportedOperationException("Floatplane plans are HLS variants, not adaptive tracks")
}
