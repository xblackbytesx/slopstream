package com.xblackbytesx.slopstream.youtube

import com.xblackbytesx.slopstream.core.VideoKind

/**
 * YouTube identifiers. A channel id `UC<22>` maps to its uploads playlists by swapping the
 * prefix: `UULF` regular videos, `UUSH` Shorts, `UULV` livestreams. Their RSS feeds give exact
 * timestamps and split the kinds for us, so the feed never has to guess what is a Short.
 */
object YoutubeIds {
    private val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val CHANNEL_IN_URL = Regex("/channel/(UC[A-Za-z0-9_-]{22})")
    private val VIDEO_IN_URL = Regex("(?:[?&]v=|youtu\\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})")

    fun isChannelId(s: String): Boolean = CHANNEL_ID.matches(s)
    fun isVideoId(s: String): Boolean = VIDEO_ID.matches(s)

    /** The `UC…` id inside a `/channel/` URL, or the input itself when it already is one. */
    fun channelIdFrom(input: String): String? {
        val s = input.trim()
        if (isChannelId(s)) return s
        return CHANNEL_IN_URL.find(s)?.groupValues?.get(1)
    }

    fun videoIdFrom(input: String): String? {
        val s = input.trim()
        if (isVideoId(s)) return s
        return VIDEO_IN_URL.find(s)?.groupValues?.get(1)
    }

    fun channelUrl(channelId: String) = "https://www.youtube.com/channel/$channelId"
    fun videoUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

    enum class Upload(val prefix: String, val kind: VideoKind) {
        VIDEOS("UULF", VideoKind.VIDEO),
        SHORTS("UUSH", VideoKind.SHORT),
        LIVE("UULV", VideoKind.LIVE),
    }

    fun uploadsPlaylist(channelId: String, upload: Upload): String {
        require(isChannelId(channelId)) { "not a channel id: $channelId" }
        return upload.prefix + channelId.substring(2)
    }

    fun playlistFeedUrl(playlistId: String) = "https://www.youtube.com/feeds/videos.xml?playlist_id=$playlistId"
    fun channelFeedUrl(channelId: String) = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"

    /**
     * Thumbnails from the stable i.ytimg.com scheme. MEDIUM and HD720 are 16:9; HIGH is 4:3 with
     * letterbox bars that a 16:9 centre crop removes exactly. HD720 is missing on some old
     * uploads, so callers fall back to HIGH.
     */
    fun thumbnail(videoId: String, size: ThumbSize) = "https://i.ytimg.com/vi/$videoId/${size.file}"

    enum class ThumbSize(val file: String, val width: Int) {
        MEDIUM("mqdefault.jpg", 320),
        HIGH("hqdefault.jpg", 480),
        HD720("hq720.jpg", 1280),
        MAX("maxresdefault.jpg", 1280),

        /** The 9:16 frame YouTube shows on Shorts shelves; not every upload has one. */
        PORTRAIT("oar2.jpg", 720),
    }

    fun thumbnailFor(videoId: String, kind: VideoKind) =
        thumbnail(videoId, if (kind == VideoKind.SHORT) ThumbSize.PORTRAIT else ThumbSize.HD720)
}
