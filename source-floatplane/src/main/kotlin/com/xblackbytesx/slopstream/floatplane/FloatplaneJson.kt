package com.xblackbytesx.slopstream.floatplane

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The parts of Floatplane's API we read, after the community specification
 * (github.com/Jman012/FloatplaneAPI). Everything optional is nullable or defaulted: the API is
 * undocumented by Floatplane and adds fields freely.
 */
internal val FloatplaneJsonFormat = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
data class ImageDto(
    val width: Int = 0,
    val height: Int = 0,
    val path: String,
    val childImages: List<ImageDto>? = null,
) {
    /** The smallest rendition at least [minWidth] wide, else the largest there is. */
    fun pick(minWidth: Int): String {
        val all = listOf(this) + childImages.orEmpty()
        return (all.filter { it.width >= minWidth }.minByOrNull { it.width } ?: all.maxBy { it.width }).path
    }
}

@Serializable
data class SelfDto(val id: String, val username: String)

@Serializable
data class PlanDto(val id: String, val title: String)

@Serializable
data class SubscriptionDto(
    val creator: String,
    val plan: PlanDto,
    val endDate: String? = null,
    val paymentCancelled: Boolean? = null,
)

@Serializable
data class LiveStreamDto(
    val id: String,
    val title: String = "",
    val thumbnail: ImageDto? = null,
    val channel: String? = null,
    /** An object with the offline title and thumbnail; its meaning for "live now" is unverified. */
    val offline: JsonElement? = null,
)

@Serializable
data class CreatorDto(
    val id: String,
    val title: String,
    val urlname: String,
    val icon: ImageDto? = null,
    val liveStream: LiveStreamDto? = null,
)

@Serializable
data class ChannelDto(
    val id: String,
    val creator: String,
    val title: String,
    val urlname: String,
    val icon: ImageDto? = null,
    val order: Int? = null,
)

@Serializable
data class PostMetadataDto(
    val hasVideo: Boolean = false,
    val videoCount: Int = 0,
    val videoDuration: Double = 0.0,
    val hasAudio: Boolean = false,
    val hasPicture: Boolean = false,
    val hasGallery: Boolean = false,
)

/**
 * A post in a creator's list. [channel] is a channel object or a bare id depending on the
 * endpoint; [videoAttachments] are ids here and objects in [PostDetailDto].
 */
@Serializable
data class PostDto(
    val id: String,
    val title: String,
    val channel: JsonElement? = null,
    val metadata: PostMetadataDto = PostMetadataDto(),
    val releaseDate: String,
    val thumbnail: ImageDto? = null,
    val isAccessible: Boolean = true,
    val attachmentOrder: List<String> = emptyList(),
    val videoAttachments: List<String> = emptyList(),
)

@Serializable
data class VideoAttachmentDto(
    val id: String,
    val title: String = "",
    val duration: Double = 0.0,
    val isProcessing: Boolean = false,
    val isAccessible: Boolean = true,
    val thumbnail: ImageDto? = null,
)

@Serializable
data class PostDetailDto(
    val id: String,
    val title: String,
    val releaseDate: String,
    val isAccessible: Boolean = true,
    val attachmentOrder: List<String> = emptyList(),
    val videoAttachments: List<VideoAttachmentDto> = emptyList(),
    /** The creator object; its `owner` is a user id or a user object. */
    val creator: JsonElement? = null,
    val thumbnail: ImageDto? = null,
)

@Serializable
data class CommentUserDto(val id: String, val username: String, val profileImage: ImageDto? = null)

@Serializable
data class InteractionCountsDto(val like: Int = 0, val dislike: Int = 0)

/**
 * A comment or a reply. A top-level comment carries its first few [replies] (3 in the spec)
 * and [totalReplies]; a reply carries neither.
 */
@Serializable
data class CommentDto(
    val id: String,
    val user: CommentUserDto,
    val text: String = "",
    val postDate: String? = null,
    val pinDate: String? = null,
    val likes: Int? = null,
    val interactionCounts: InteractionCountsDto? = null,
    val totalReplies: Int = 0,
    val replies: List<CommentDto>? = null,
)

@Serializable
data class BitrateDto(val average: Long? = null, val maximum: Long? = null)

@Serializable
data class VideoMetaDto(
    val codec: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Double = 0.0,
    val isHdr: Boolean = false,
    val bitrate: BitrateDto? = null,
    val mimeType: String? = null,
)

@Serializable
data class AudioMetaDto(val codec: String? = null, val bitrate: BitrateDto? = null, val mimeType: String? = null)

@Serializable
data class VariantMetaDto(val video: VideoMetaDto? = null, val audio: AudioMetaDto? = null, val live: JsonElement? = null)

@Serializable
data class OriginDto(val url: String)

@Serializable
data class VariantDto(
    val name: String,
    val label: String,
    /** Relative to an origin, with the access token in its query. Empty when not allowed. */
    val url: String,
    val mimeType: String? = null,
    val order: Long? = null,
    val enabled: Boolean = true,
    val hidden: Boolean = false,
    /** Some variants carry their own origins instead of the group's. */
    val origins: List<OriginDto>? = null,
    val meta: VariantMetaDto? = null,
)

@Serializable
data class DeliveryGroupDto(val origins: List<OriginDto> = emptyList(), val variants: List<VariantDto> = emptyList())

@Serializable
data class DeliveryDto(val groups: List<DeliveryGroupDto> = emptyList())

@Serializable
data class ProgressDto(val id: String, val progress: Int)
