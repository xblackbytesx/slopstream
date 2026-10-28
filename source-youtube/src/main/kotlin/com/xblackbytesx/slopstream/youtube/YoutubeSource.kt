package com.xblackbytesx.slopstream.youtube

import com.xblackbytesx.slopstream.core.ArchivePage
import com.xblackbytesx.slopstream.core.Channel
import com.xblackbytesx.slopstream.core.ChannelFeed
import com.xblackbytesx.slopstream.core.Comment
import com.xblackbytesx.slopstream.core.CommentsPage
import com.xblackbytesx.slopstream.core.ContentSource
import com.xblackbytesx.slopstream.core.SourceBlockedException
import com.xblackbytesx.slopstream.core.SourceCapabilities
import com.xblackbytesx.slopstream.core.SourceException
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.core.SourceUnavailableException
import com.xblackbytesx.slopstream.core.VideoItem
import com.xblackbytesx.slopstream.core.VideoKind
import com.xblackbytesx.slopstream.core.playback.DeviceProfile
import com.xblackbytesx.slopstream.core.playback.PlaybackPlan
import com.xblackbytesx.slopstream.core.playback.PlaybackPrefs
import com.xblackbytesx.slopstream.core.playback.StreamSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.time.Instant
import java.util.Locale

/**
 * YouTube without an account. Subscriptions are local; feeds come from RSS (see [RssFeedFetcher])
 * with the extractor's channel tabs as a fallback; playback uses NewPipeExtractor plus the
 * app's PoToken provider.
 */
class YoutubeSource(
    client: OkHttpClient,
    locale: Locale = Locale.getDefault(),
    poTokenProvider: PoTokenProvider? = null,
    private val rss: RssFeedFetcher = RssFeedFetcher(client),
) : ContentSource {

    private val service = ServiceList.YouTube
    private val streams = StreamPlanner()

    init {
        val localization = Localization.fromLocale(locale)
        val country = locale.country.takeIf { it.length == 2 }?.let(::ContentCountry) ?: ContentCountry.DEFAULT
        NewPipe.init(OkHttpDownloader(client), localization, country)
        YoutubeStreamExtractor.setPoTokenProvider(poTokenProvider)
    }

    override val key = SourceKey.YOUTUBE

    override val capabilities = SourceCapabilities(
        needsLogin = false, hasShorts = true, hasLive = true, canSearchChannels = true, canSearchVideos = true,
        importsSubscriptions = true, hasComments = true, enrichesDurations = true,
        archiveKinds = setOf(VideoKind.VIDEO, VideoKind.SHORT, VideoKind.LIVE),
    )

    override suspend fun searchChannels(query: String): List<Channel> = io {
        val handler = service.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.CHANNELS), "")
        SearchInfo.getInfo(service, handler).relatedItems
            .filterIsInstance<ChannelInfoItem>()
            .mapNotNull { item ->
                val id = YoutubeIds.channelIdFrom(item.url) ?: return@mapNotNull null
                Channel(
                    source = key,
                    remoteId = id,
                    name = item.name,
                    url = YoutubeIds.channelUrl(id),
                    avatarUrl = item.thumbnails.maxByOrNull { it.height }?.url,
                    subscriberCount = item.subscriberCount.takeIf { it >= 0 },
                )
            }
    }

    /** Shorts come back marked as such; live and upcoming the same way the channel tabs do. */
    override suspend fun searchVideos(query: String): List<VideoItem> = io {
        val handler = service.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        SearchInfo.getInfo(service, handler).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { item ->
                val channelId = item.uploaderUrl?.let(YoutubeIds::channelIdFrom) ?: return@mapNotNull null
                item.toVideoItem(channelId, item.uploaderName.orEmpty(), VideoKind.VIDEO)
            }
    }

    override suspend fun resolveChannel(input: String): Channel = io {
        val s = input.trim()
        val url = when {
            YoutubeIds.isChannelId(s) -> YoutubeIds.channelUrl(s)
            s.startsWith("@") -> "https://www.youtube.com/$s"
            s.startsWith("http://") || s.startsWith("https://") -> s
            "youtube.com/" in s -> "https://$s"
            else -> "https://www.youtube.com/@$s"
        }
        val info = ChannelInfo.getInfo(service, url)
        val id = YoutubeIds.channelIdFrom(info.id) ?: YoutubeIds.channelIdFrom(info.url)
            ?: throw SourceUnavailableException("Not a channel: $input")
        Channel(
            source = key,
            remoteId = id,
            name = info.name,
            url = YoutubeIds.channelUrl(id),
            avatarUrl = info.avatars.maxByOrNull { it.height }?.url,
            subscriberCount = info.subscriberCount.takeIf { it >= 0 },
        )
    }

    override suspend fun fetchChannelFeed(channelId: String): ChannelFeed = io {
        try {
            rss.fetch(channelId)
        } catch (e: SourceBlockedException) {
            throw e
        } catch (e: SourceUnavailableException) {
            throw e
        } catch (e: SourceException) {
            // RSS is down or malformed: the channel tabs are heavier and dated "3 days ago",
            // but keep the feed moving.
            fetchFromTabs(channelId) ?: throw e
        }
    }

    override suspend fun resolvePlayback(videoId: String, profile: DeviceProfile, prefs: PlaybackPrefs): PlaybackPlan = io {
        val info = StreamInfo.getInfo(service, YoutubeIds.videoUrl(videoId))
        streams.plan(info, profile, prefs)
    }

    override suspend fun switchVideo(plan: PlaybackPlan.Adaptive, candidateId: String): PlaybackPlan.Adaptive = io {
        val info = StreamInfo.getInfo(service, YoutubeIds.videoUrl(plan.videoId))
        streams.switch(info, plan, candidateId)
    }

    /**
     * The Videos and Live tabs list durations, which RSS lacks: one channel page and two tab
     * requests. Shorts are left out (YouTube lists no durations there).
     */
    override suspend fun durations(channelId: String): Map<String, Long> = io {
        val info = ChannelInfo.getInfo(service, YoutubeIds.channelUrl(channelId))
        info.tabs.filter { it.contentFilters.firstOrNull() in setOf(ChannelTabs.VIDEOS, ChannelTabs.LIVESTREAMS) }
            .flatMap { tab -> runCatching { ChannelTabInfo.getInfo(service, tab).relatedItems }.getOrDefault(emptyList()) }
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { item -> YoutubeIds.videoIdFrom(item.url)?.let { id -> item.duration.takeIf { it > 0 }?.let { id to it } } }
            .toMap()
    }

    /**
     * The channel's Videos, Shorts or Live tab, a continuation page at a time (about 30 items).
     * Dates on these tabs are relative ("3 years ago"), so the items are [VideoItem.dateApproximate].
     */
    override suspend fun channelPage(channelId: String, kind: VideoKind, from: ArchivePage.Cursor?): ArchivePage = io {
        val at = from?.handle as? TabCursor
        if (at == null) {
            val info = ChannelInfo.getInfo(service, YoutubeIds.channelUrl(channelId))
            val filter = TAB_KINDS.entries.firstOrNull { it.value == kind }?.key
            val tab = info.tabs.firstOrNull { it.contentFilters.firstOrNull() == filter } ?: return@io ArchivePage(emptyList(), null)
            val first = ChannelTabInfo.getInfo(service, tab)
            archivePage(first.relatedItems, first.nextPage, tab, channelId, info.name, kind)
        } else {
            val more = ChannelTabInfo.getMoreItems(service, at.tab, at.page)
            archivePage(more.items, more.nextPage, at.tab, channelId, at.channelName, kind)
        }
    }

    private fun archivePage(found: List<*>, next: Page?, tab: ListLinkHandler, channelId: String, channelName: String, kind: VideoKind) =
        ArchivePage(
            found.filterIsInstance<StreamInfoItem>().mapNotNull { it.toVideoItem(channelId, channelName, kind) },
            next?.takeIf { Page.isValid(it) }?.let { ArchivePage.Cursor(TabCursor(tab, it, channelName)) },
        )

    private class TabCursor(val tab: ListLinkHandler, val page: Page, val channelName: String)

    private fun fetchFromTabs(channelId: String): ChannelFeed? {
        val info = ChannelInfo.getInfo(service, YoutubeIds.channelUrl(channelId))
        val items = info.tabs.flatMap { tab ->
            val kind = TAB_KINDS[tab.contentFilters.firstOrNull()] ?: return@flatMap emptyList()
            runCatching { ChannelTabInfo.getInfo(service, tab).relatedItems }.getOrDefault(emptyList())
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { it.toVideoItem(channelId, info.name, kind) }
        }
        if (items.isEmpty()) return null
        return ChannelFeed(key, channelId, info.name, items.distinctBy { it.remoteId }.sortedByDescending { it.publishedAt })
    }

    private fun StreamInfoItem.toVideoItem(channelId: String, channelName: String, tabKind: VideoKind): VideoItem? {
        val id = YoutubeIds.videoIdFrom(url) ?: return null
        val date = uploadDate
        val kind = when {
            tabKind == VideoKind.SHORT || isShortFormContent -> VideoKind.SHORT
            streamType == StreamType.LIVE_STREAM -> VideoKind.LIVE
            date != null && date.instant.isAfter(Instant.now()) -> VideoKind.UPCOMING
            else -> tabKind
        }
        return VideoItem(
            source = key,
            remoteId = id,
            channelId = channelId,
            channelName = uploaderName ?: channelName,
            title = name,
            publishedAt = date?.instant ?: Instant.now(),
            kind = kind,
            thumbnailUrl = YoutubeIds.thumbnailFor(id, kind),
            durationSec = duration.takeIf { it > 0 },
            dateApproximate = date?.isApproximation ?: true,
            dateUnknown = date == null,
        )
    }

    override suspend fun comments(videoId: String, from: CommentsPage.Cursor?): CommentsPage = io {
        val url = YoutubeIds.videoUrl(videoId)
        if (from == null) {
            val info = CommentsInfo.getInfo(service, url)
            CommentsPage(info.relatedItems.map(::comment), cursor(info.nextPage), disabled = info.isCommentsDisabled)
        } else {
            val page = CommentsInfo.getMoreItems(service, url, from.handle as Page)
            CommentsPage(page.items.map(::comment), cursor(page.nextPage))
        }
    }

    private fun cursor(page: Page?): CommentsPage.Cursor? = page?.takeIf { Page.isValid(it) }?.let(CommentsPage::Cursor)

    private fun comment(c: CommentsInfoItem) = Comment(
        id = c.commentId.orEmpty(),
        author = c.uploaderName.orEmpty(),
        // Shown at about 40 dp: the smallest rendition that is still sharp.
        avatarUrl = c.uploaderAvatars.filter { it.url.isNotBlank() }
            .let { all -> all.filter { it.height >= 88 }.minByOrNull { it.height } ?: all.maxByOrNull { it.height } }?.url,
        text = CommentText.plain(c.commentText.content.orEmpty()),
        likes = c.likeCount.takeIf { it >= 0 },
        published = c.textualUploadDate?.takeIf { it.isNotBlank() },
        replyCount = c.replyCount.coerceAtLeast(0),
        pinned = c.isPinned,
        heartedByUploader = c.isHeartedByUploader,
        byUploader = c.isChannelOwner,
        replies = cursor(c.replies),
    )

    /** Runs extractor work on the IO pool and maps its exceptions onto the core contract. */
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: SourceException) {
            throw e
        } catch (e: ReCaptchaException) {
            throw SourceBlockedException("YouTube asked for a captcha; try again later", e)
        } catch (e: SignInConfirmNotBotException) {
            throw SourceBlockedException("YouTube wants a sign-in to prove this is not a bot; try again later", e)
        } catch (e: ContentNotAvailableException) {
            throw SourceUnavailableException(e.message ?: "Not available", e)
        } catch (e: Exception) {
            throw SourceException(e.message ?: e.javaClass.simpleName, e)
        }
    }

    private companion object {
        /** The channel tabs we read, and the kind their items are. */
        val TAB_KINDS = mapOf(
            ChannelTabs.VIDEOS to VideoKind.VIDEO,
            ChannelTabs.SHORTS to VideoKind.SHORT,
            ChannelTabs.LIVESTREAMS to VideoKind.LIVE,
        )
    }
}
