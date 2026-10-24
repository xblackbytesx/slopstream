package com.xblackbytesx.slopstream.floatplane

import com.xblackbytesx.slopstream.core.Channel
import com.xblackbytesx.slopstream.core.VideoItem
import com.xblackbytesx.slopstream.core.VideoKind
import com.xblackbytesx.slopstream.core.playback.VideoCandidate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Floatplane's shapes to ours. Pure, so FloatplaneMappingTest covers it.
 *
 * Identity: a subscription is a **creator** (that is what Floatplane sells), so the creator id is
 * our channel id and one request fetches everything it posts. A post's sub-channel (TechLinked,
 * ShortCircuit, ...) only names the tile. A feed item is a **post**, not a video attachment:
 * posts are what Floatplane lists, dates and tracks progress for.
 */
object FloatplaneMapping {

    fun creatorUrl(urlname: String, site: FloatplaneSite = FloatplaneSite.FLOATPLANE) = "${site.baseUrl}/channel/$urlname/home"
    fun postUrl(postId: String, site: FloatplaneSite = FloatplaneSite.FLOATPLANE) = "${site.baseUrl}/post/$postId"

    fun channel(c: CreatorDto, site: FloatplaneSite = FloatplaneSite.FLOATPLANE) = Channel(
        source = site.key,
        remoteId = c.id,
        name = c.title,
        url = creatorUrl(c.urlname, site),
        avatarUrl = c.icon?.pick(200),
    )

    /** The sub-channel id of a post: the list endpoint sends an object or a bare id. */
    fun channelIdOf(post: PostDto): String? = when (val ch = post.channel) {
        is JsonObject -> ch["id"]?.jsonPrimitive?.contentOrNull
        is JsonPrimitive -> ch.contentOrNull
        else -> null
    }

    private fun channelTitleOf(post: PostDto): String? =
        (post.channel as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull

    /**
     * Video posts the account can play, newest first. Posts outside the user's plan and posts
     * without video are left out: this is a video player, and a locked tile is a dead end.
     */
    fun feedItems(
        creator: CreatorDto,
        posts: List<PostDto>,
        channelTitles: Map<String, String>,
        site: FloatplaneSite = FloatplaneSite.FLOATPLANE,
    ): List<VideoItem> =
        posts.filter { it.metadata.hasVideo && it.isAccessible }
            .map { p ->
                VideoItem(
                    source = site.key,
                    remoteId = p.id,
                    channelId = creator.id,
                    channelName = channelTitleOf(p) ?: channelIdOf(p)?.let(channelTitles::get) ?: creator.title,
                    title = p.title,
                    publishedAt = Instant.parse(p.releaseDate),
                    kind = VideoKind.VIDEO,
                    thumbnailUrl = p.thumbnail?.pick(640),
                    durationSec = p.metadata.videoDuration.roundToInt().toLong().takeIf { it > 0 },
                )
            }
            .sortedByDescending { it.publishedAt }

    private const val LIVE_PREFIX = "live:"

    /** The feed id of a creator's livestream: one per creator, replaced when the stream changes. */
    fun liveId(creatorId: String) = LIVE_PREFIX + creatorId

    fun creatorOfLiveId(id: String): String? = id.takeIf { it.startsWith(LIVE_PREFIX) }?.removePrefix(LIVE_PREFIX)

    /** The on-air stream as a tile. Dated [now] so it leads the feed while it lasts. */
    fun liveItem(creator: CreatorDto, stream: LiveStreamDto, now: Instant, site: FloatplaneSite = FloatplaneSite.FLOATPLANE) = VideoItem(
        source = site.key,
        remoteId = liveId(creator.id),
        channelId = creator.id,
        channelName = creator.title,
        title = stream.title.ifBlank { "${creator.title} is live" },
        publishedAt = now,
        kind = VideoKind.LIVE,
        thumbnailUrl = stream.thumbnail?.pick(640),
    )

    /** The video a post plays first: attachment order as the creator arranged it. */
    fun firstPlayable(post: PostDetailDto): VideoAttachmentDto? {
        val byId = post.videoAttachments.associateBy { it.id }
        val ordered = post.attachmentOrder.mapNotNull(byId::get) + post.videoAttachments.filter { it.id !in post.attachmentOrder }
        return ordered.firstOrNull { it.isAccessible && !it.isProcessing }
    }

    /** A playable variant: its stream URL and what the selector needs to rank it. */
    data class Variant(val candidate: VideoCandidate, val url: String, val label: String)

    fun variants(delivery: DeliveryDto): List<Variant> = delivery.groups.flatMap { group ->
        group.variants
            .filter { it.enabled && !it.hidden && it.url.isNotBlank() }
            .mapNotNull { v ->
                val video = v.meta?.video ?: return@mapNotNull null
                val origin = (v.origins ?: group.origins).firstOrNull()?.url ?: return@mapNotNull null
                Variant(
                    candidate = VideoCandidate(
                        id = v.name,
                        codecs = video.codec.orEmpty(),
                        width = video.width,
                        height = video.height,
                        fps = video.fps.roundToInt(),
                        bitrate = (video.bitrate?.average ?: 0L).toInt(),
                        hdr = video.isHdr,
                    ),
                    url = origin.trimEnd('/') + v.url,
                    label = v.label,
                )
            }
    }

    /** The livestream URL: live variants carry no video metadata, the player's ABR picks. */
    fun liveUrl(delivery: DeliveryDto): String? = delivery.groups.firstNotNullOfOrNull { group ->
        group.variants.firstOrNull { it.enabled && it.url.isNotBlank() }?.let { v ->
            (v.origins ?: group.origins).firstOrNull()?.url?.trimEnd('/')?.plus(v.url)
        }
    }
}
