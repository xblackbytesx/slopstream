package com.xblackbytesx.slopstream.floatplane

import com.xblackbytesx.slopstream.core.ArchivePage
import com.xblackbytesx.slopstream.core.Channel
import com.xblackbytesx.slopstream.core.ChannelFeed
import com.xblackbytesx.slopstream.core.CommentsPage
import com.xblackbytesx.slopstream.core.DownloadOffer
import com.xblackbytesx.slopstream.core.DownloadStream
import com.xblackbytesx.slopstream.core.ContentSource
import com.xblackbytesx.slopstream.core.Segmented
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
        hasComments = true,
        canDownload = true,
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

    /**
     * A post's comments [COMMENTS_PAGE] at a time, after the last comment shown. The first page
     * also reads the post, for its creator's account: their comments are marked as the
     * uploader's. A livestream has a chat instead, which is not shown here.
     */
    override suspend fun comments(videoId: String, from: CommentsPage.Cursor?): CommentsPage {
        if (FloatplaneMapping.creatorOfLiveId(videoId) != null) return CommentsPage(emptyList(), null, disabled = true)
        return when (val at = from?.handle) {
            null -> topComments(videoId, FloatplaneMapping.ownerOf(api.post(videoId)), after = null)
            is TopCursor -> topComments(videoId, at.owner, at.after)
            is RepliesCursor -> replies(at)
            else -> throw IllegalArgumentException("Not a Floatplane comments cursor")
        }
    }

    private suspend fun topComments(postId: String, owner: String?, after: String?): CommentsPage {
        val page = api.comments(postId, COMMENTS_PAGE, after)
        val comments = page.map { c ->
            val replies = if (c.totalReplies > 0) {
                CommentsPage.Cursor(RepliesCursor(postId, c.id, owner, inline = c.replies.orEmpty(), after = null, loaded = 0, total = c.totalReplies))
            } else {
                null
            }
            FloatplaneMapping.comment(c, owner, replies)
        }
        val next = if (page.size < COMMENTS_PAGE) null else CommentsPage.Cursor(TopCursor(owner, page.last().id))
        return CommentsPage(comments, next)
    }

    /** The replies that came with the comment first, then [REPLIES_PAGE] at a time after the last one shown. */
    private suspend fun replies(at: RepliesCursor): CommentsPage {
        val page = at.inline.ifEmpty { api.replies(at.commentId, at.postId, REPLIES_PAGE, at.after) }
        val loaded = at.loaded + page.size
        val next = if (page.isNotEmpty() && loaded < at.total) {
            CommentsPage.Cursor(RepliesCursor(at.postId, at.commentId, at.owner, inline = emptyList(), after = page.last().id, loaded = loaded, total = at.total))
        } else {
            null
        }
        return CommentsPage(page.map { FloatplaneMapping.comment(it.copy(totalReplies = 0), at.owner, null) }, next)
    }

    /**
     * Floatplane's own downloads (the website's Download button, decision 26): the `download`
     * scenario of `delivery/info` lists one plain MP4 per quality, picture and sound in one
     * file, fetched by range with its URL token alone. Sizes are estimated from the bitrates.
     * A stream id is `<attachment>:<variant>`, so renewing a URL is one delivery call.
     *
     * Where those files are switched off (Sauce+ lists every one off and without a link, live
     * report 2026-11-09), the stream the player plays is saved instead: its HLS segments,
     * decrypted with the account's key ([Segmented]), stream id `<attachment>:hls:<variant>`.
     * Once a site's files were off, later offers skip asking for them (one delivery call less).
     */
    override suspend fun downloadOffer(videoId: String, profile: DeviceProfile, prefs: PlaybackPrefs): DownloadOffer {
        if (FloatplaneMapping.creatorOfLiveId(videoId) != null) throw SourceUnavailableException("A livestream can't be downloaded")
        val post = api.post(videoId)
        val video = FloatplaneMapping.firstPlayable(post) ?: throw SourceUnavailableException("This post has no downloadable video")
        val files = if (filesOff) null else api.delivery("download", video.id)
        val fileVariants = files?.let(FloatplaneMapping::variants).orEmpty()
        val fileOptions = StreamSelector.playable(fileVariants.map { it.candidate }, profile, prefs)
        val streamed = fileOptions.isEmpty()
        if (files != null && streamed && FloatplaneMapping.filesSwitchedOff(files)) filesOff = true
        val variants = if (streamed) FloatplaneMapping.variants(api.delivery("onDemand", video.id)) else fileVariants
        val options = if (streamed) StreamSelector.playable(variants.map { it.candidate }, profile, prefs) else fileOptions
        if (options.isEmpty()) {
            throw SourceUnavailableException(files?.let { FloatplaneMapping.noDownload(site.name, it) } ?: "No stream this device can play")
        }
        val access = if (streamed) segmented() else null
        val duration = video.duration.toLong()
        val (creatorId, creatorName) = FloatplaneMapping.creatorOf(post) ?: ("" to "")
        return DownloadOffer(
            videoId = videoId,
            title = post.title,
            channelId = creatorId,
            channelName = creatorName,
            durationSec = duration,
            thumbnailUrl = post.thumbnail?.pick(640),
            videos = options.map { c -> download(video.id, variants.first { it.candidate == c }, duration, access) },
            audio = null,
            details = FloatplaneMapping.details(post),
        )
    }

    override suspend fun downloadStream(videoId: String, streamId: String): DownloadStream {
        val attachment = streamId.substringBefore(':')
        val rest = streamId.substringAfter(':')
        val streamed = rest.startsWith(HLS_PREFIX)
        val name = rest.removePrefix(HLS_PREFIX)
        val variant = FloatplaneMapping.variants(api.delivery(if (streamed) "onDemand" else "download", attachment)).firstOrNull { it.candidate.id == name }
            ?: throw SourceUnavailableException("${site.name} no longer offers this quality ($name)")
        // Duration only feeds the size estimate, which the download does not rely on.
        return download(attachment, variant, durationSec = 0, access = if (streamed) segmented() else null)
    }

    /** Set once the site listed its download files switched off: offers go straight to the stream. */
    @Volatile private var filesOff = false

    /** What fetching the stream's segments and key needs: the player's credentials and key host. */
    private suspend fun segmented() = Segmented(
        authHosts = setOf(api.host),
        authHeaders = api.credentialHeaders(),
        hostRewrites = keyRewrites(),
    )

    // Sauce+ playlists name www.floatplane.com's watchKey, which refuses a Sauce+ session; the
    // same path on www.sauceplus.com serves the key (live report 2026-10-23).
    private fun keyRewrites() = if (api.host != FLOATPLANE_KEY_HOST) mapOf(FLOATPLANE_KEY_HOST to api.host) else emptyMap()

    private fun download(attachment: String, v: FloatplaneMapping.Variant, durationSec: Long, access: Segmented? = null): DownloadStream {
        val audioBits = v.audioBitrate ?: 0
        val bits = v.candidate.bitrate + audioBits
        return DownloadStream(
            id = if (access != null) "$attachment:$HLS_PREFIX${v.candidate.id}" else "$attachment:${v.candidate.id}",
            url = v.url,
            // MPEG-TS segments joined (the platform's on-demand streams, video/MP2T).
            extension = if (access != null) "ts" else "mp4",
            segmented = access,
            sizeBytes = (bits * durationSec / 8).takeIf { it > 0 },
            sizeExact = false,
            video = v.candidate,
            audioSizeBytes = (audioBits * durationSec / 8).takeIf { it > 0 },
        )
    }

    private class TopCursor(val owner: String?, val after: String)

    private class RepliesCursor(
        val postId: String,
        val commentId: String,
        val owner: String?,
        /** The replies the comment arrived with, shown first without a request. */
        val inline: List<CommentDto>,
        val after: String?,
        val loaded: Int,
        val total: Int,
    )

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
            hostRewrites = keyRewrites(),
            details = FloatplaneMapping.details(post),
        )
    }

    private companion object {
        /** In a stream id: the variant is saved from the stream's segments. */
        const val HLS_PREFIX = "hls:"

        /** Where every playlist on the platform points its AES key, Sauce+ included. */
        val FLOATPLANE_KEY_HOST = FloatplaneSite.FLOATPLANE.host

        /** Posts per older-videos page; the most the posts list hands out at once. */
        const val ARCHIVE_PAGE = 20

        /** Comments per page, the spec's default; replies the same. */
        const val COMMENTS_PAGE = 20
        const val REPLIES_PAGE = 20
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
