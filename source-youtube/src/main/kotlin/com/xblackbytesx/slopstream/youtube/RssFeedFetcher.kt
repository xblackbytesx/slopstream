package com.xblackbytesx.slopstream.youtube

import com.xblackbytesx.slopstream.core.ChannelFeed
import com.xblackbytesx.slopstream.core.SourceBlockedException
import com.xblackbytesx.slopstream.core.SourceException
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.core.SourceUnavailableException
import com.xblackbytesx.slopstream.core.VideoItem
import com.xblackbytesx.slopstream.core.VideoKind
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * A channel's recent uploads from three RSS feeds, one per uploads playlist (see [YoutubeIds]).
 * A 404 means "no uploads of that kind", not an error: most channels have no Shorts or streams.
 * When all three are missing (some channels lack the split playlists) the plain channel feed is
 * used and Shorts are recognised by their `/shorts/` link.
 */
class RssFeedFetcher(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://www.youtube.com",
) {

    fun fetch(channelId: String): ChannelFeed {
        val byKind = YoutubeIds.Upload.entries.associateWith { upload ->
            get("/feeds/videos.xml?playlist_id=${YoutubeIds.uploadsPlaylist(channelId, upload)}")
        }
        if (byKind.values.all { it == null }) return fallback(channelId)

        val seen = HashSet<String>()
        val items = ArrayList<VideoItem>()
        var name: String? = null
        // Shorts first: an id that somehow sits in two playlists must never reach the main feed.
        for (upload in listOf(YoutubeIds.Upload.SHORTS, YoutubeIds.Upload.LIVE, YoutubeIds.Upload.VIDEOS)) {
            val feed = byKind[upload] ?: continue
            name = name ?: feed.channelName
            feed.entries.forEach { e -> if (seen.add(e.videoId)) items += e.toItem(channelId, upload.kind) }
        }
        return ChannelFeed(SourceKey.YOUTUBE, channelId, name, items.sortedByDescending { it.publishedAt })
    }

    private fun fallback(channelId: String): ChannelFeed {
        val feed = get("/feeds/videos.xml?channel_id=$channelId")
            ?: throw SourceUnavailableException("Channel $channelId has no feed (removed or terminated?)")
        val items = feed.entries.map { e ->
            e.toItem(channelId, if ("/shorts/" in e.link) VideoKind.SHORT else VideoKind.VIDEO)
        }
        return ChannelFeed(SourceKey.YOUTUBE, channelId, feed.channelName, items.sortedByDescending { it.publishedAt })
    }

    /** The parsed feed, or null on 404. Throws for anything that should keep the last good copy. */
    private fun get(path: String): RssFeed? {
        val request = Request.Builder().url(baseUrl + path).header("User-Agent", OkHttpDownloader.USER_AGENT).build()
        try {
            client.newCall(request).execute().use { r ->
                return when {
                    r.code == 404 -> null
                    r.code == 429 || r.code == 403 -> throw SourceBlockedException("YouTube refused the feed (HTTP ${r.code})")
                    !r.isSuccessful -> throw SourceException("Feed request failed (HTTP ${r.code})")
                    else -> r.body.byteStream().use(RssParser::parse)
                }
            }
        } catch (e: IOException) {
            throw SourceException("Feed request failed: ${e.message}", e)
        } catch (e: SourceException) {
            throw e
        } catch (e: Exception) {
            // Malformed XML: treat like a server error, never as "no uploads".
            throw SourceException("Feed could not be read: ${e.message}", e)
        }
    }

    private fun RssEntry.toItem(channelId: String, kind: VideoKind) = VideoItem(
        source = SourceKey.YOUTUBE,
        remoteId = videoId,
        channelId = this.channelId.ifBlank { channelId },
        channelName = channelName,
        title = title,
        publishedAt = published,
        kind = kind,
        thumbnailUrl = YoutubeIds.thumbnailFor(videoId, kind),
    )
}
