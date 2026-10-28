package com.xblackbytesx.slopstream.floatplane

import com.xblackbytesx.slopstream.core.ArchivePage
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
 * A Floatplane-platform site (Floatplane, Sauce+) behind the shared contract.
 *
 * On Floatplane, subscriptions come from the account ([accountSubscriptions]) and are
 * read-only here: they are paid per creator, so they are managed on floatplane.com. On a site
 * with [FloatplaneSite.globalSubscriptions] (Sauce+) one subscription covers every creator, so
 * the member picks creators from the [catalog] and follows them locally, like YouTube.
 */
class FloatplaneSource(
    private val api: FloatplaneApi,
    val site: FloatplaneSite = FloatplaneSite.FLOATPLANE,
    private val clock: Clock = Clock.systemUTC(),
) : ContentSource {
    override val key: SourceKey = site.key
    override val capabilities = SourceCapabilities(
        needsLogin = true,
        hasShorts = false,
        hasLive = site.hasLive,
        canSearchChannels = site.globalSubscriptions,
        accountSubscriptions = !site.globalSubscriptions,
        // delivery/info is rate limited: resolve once per play, never on focus.
        prefetchPlayback = false,
        catalog = site.globalSubscriptions,
        archiveKinds = setOf(VideoKind.VIDEO),
    )

    /** Who is signed in; also the check that a session works. */
    suspend fun username(): String = api.self().username

    suspend fun signOut() = api.logout()

    /** The creators the account pays for, one row each. None on a global-subscription site. */
    override suspend fun accountSubscriptions(): List<Channel> =
        if (site.globalSubscriptions) {
            emptyList()
        } else {
            api.subscriptions().map { it.creator }.distinct().map { FloatplaneMapping.channel(api.creator(it), site) }
        }

    /** Every creator on the site, A to Z. Only offered where one subscription covers them all. */
    override suspend fun catalog(): List<Channel> =
        if (site.globalSubscriptions) api.creators().map { FloatplaneMapping.channel(it, site) }.sortedBy { it.name.lowercase() } else emptyList()

    override suspend fun searchChannels(query: String): List<Channel> =
        if (site.globalSubscriptions) api.creators(query.trim()).map { FloatplaneMapping.channel(it, site) } else emptyList()

    override suspend fun searchVideos(query: String): List<VideoItem> = emptyList()

    override suspend fun resolveChannel(input: String): Channel =
        throw SourceUnavailableException("Pick ${site.name} creators from its list")

    /**
     * [channelId] is a creator id: one request covers all its sub-channels. A livestream that
     * is on right now leads the list as a [VideoKind.LIVE] item ([liveId]).
     */
    override suspend fun fetchChannelFeed(channelId: String): ChannelFeed {
        val creator = api.creator(channelId)
        val posts = api.posts(channelId)
        val live = liveNow(creator)
        val items = listOfNotNull(live.getOrNull()) + FloatplaneMapping.feedItems(creator, posts, subChannelTitles(channelId, posts), site)
        // Only an answer either way says whether an earlier live item has ended.
        val complete = if (live.isSuccess) setOf(VideoKind.LIVE) else emptySet()
        return ChannelFeed(key, creator.id, creator.title, items, completeKinds = complete)
    }

    /**
     * The creator's video posts [ARCHIVE_PAGE] at a time, by offset (`fetchAfter`). A page can
     * map to fewer items: posts the plan does not include are left out, as in the feed. Past
     * livestreams are posted as videos, so there is no live archive.
     */
    override suspend fun channelPage(channelId: String, kind: VideoKind, from: ArchivePage.Cursor?): ArchivePage {
        if (kind != VideoKind.VIDEO) return ArchivePage(emptyList(), null)
        val at = from?.handle as? PostsCursor
        val creator = at?.creator ?: api.creator(channelId)
        val offset = at?.offset ?: 0
        val posts = api.posts(channelId, limit = ARCHIVE_PAGE, fetchAfter = offset)
        val items = FloatplaneMapping.feedItems(creator, posts, subChannelTitles(channelId, posts), site)
        val next = if (posts.size < ARCHIVE_PAGE) null else ArchivePage.Cursor(PostsCursor(creator, offset + posts.size))
        return ArchivePage(items, next)
    }

    private class PostsCursor(val creator: CreatorDto, val offset: Int)

    /** Only looks sub-channel names up when a post names its channel by id alone. */
    private suspend fun subChannelTitles(creatorId: String, posts: List<PostDto>): Map<String, String> =
        if (posts.any { it.channel !is JsonObject && FloatplaneMapping.channelIdOf(it) != null }) {
            api.channels(listOf(creatorId)).associate { it.id to it.title }
        } else {
            emptyMap()
        }

    /**
     * The creator's stream as a feed item when it is on air, null when off. The delivery
     * endpoint hands out a URL even while offline; the playlist itself is 404 then.
     */
    private suspend fun liveNow(creator: CreatorDto): Result<VideoItem?> {
        if (!site.hasLive) return Result.success(null)
        val stream = creator.liveStream ?: return Result.success(null)
        return try {
            val url = FloatplaneMapping.liveUrl(api.delivery("live", stream.id))
            val on = url != null && api.status(url) == 200
            Result.success(if (on) FloatplaneMapping.liveItem(creator, stream, clock.instant(), site) else null)
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
            // Sauce+ playlists name www.floatplane.com's watchKey, which refuses a Sauce+
            // session; the same path on www.sauceplus.com serves the key (live report 2026-10-23).
            hostRewrites = if (api.host != FLOATPLANE_KEY_HOST) mapOf(FLOATPLANE_KEY_HOST to api.host) else emptyMap(),
        )
    }

    private companion object {
        /** Where every playlist on the platform points its AES key, Sauce+ included. */
        val FLOATPLANE_KEY_HOST = FloatplaneSite.FLOATPLANE.host

        /** Posts per older-videos page; the most the posts list hands out at once. */
        const val ARCHIVE_PAGE = 20
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
