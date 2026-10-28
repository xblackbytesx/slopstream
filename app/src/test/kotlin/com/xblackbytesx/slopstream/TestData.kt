package com.xblackbytesx.slopstream

import com.xblackbytesx.slopstream.core.ArchivePage
import com.xblackbytesx.slopstream.core.Channel
import com.xblackbytesx.slopstream.core.ChannelFeed
import com.xblackbytesx.slopstream.core.ContentSource
import com.xblackbytesx.slopstream.core.DownloadOffer
import com.xblackbytesx.slopstream.core.DownloadStream
import com.xblackbytesx.slopstream.core.SourceCapabilities
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.core.VideoItem
import com.xblackbytesx.slopstream.core.VideoKind
import com.xblackbytesx.slopstream.core.playback.DeviceProfile
import com.xblackbytesx.slopstream.core.playback.PlaybackPlan
import com.xblackbytesx.slopstream.core.playback.PlaybackPrefs
import com.xblackbytesx.slopstream.data.SecretStore
import com.xblackbytesx.slopstream.data.StoredAccount
import com.xblackbytesx.slopstream.data.db.SubscriptionEntity
import com.xblackbytesx.slopstream.data.db.VideoRow
import java.time.Instant

/** A [ContentSource] whose feeds come from a map; channels missing from it throw. */
open class FakeSource(
    var feeds: MutableMap<String, () -> ChannelFeed> = mutableMapOf(),
    override val key: SourceKey = SourceKey.YOUTUBE,
    override val capabilities: SourceCapabilities =
        SourceCapabilities(
            needsLogin = false, hasShorts = true, hasLive = true, canSearchChannels = true, enrichesDurations = true,
            archiveKinds = setOf(VideoKind.VIDEO, VideoKind.SHORT, VideoKind.LIVE),
        ),
) : ContentSource {
    val fetched = mutableListOf<String>()

    /** What [accountSubscriptions] returns, for a source that has them. */
    var account: () -> List<Channel> = { emptyList() }

    /** What [durations] returns, per channel. */
    val durationsOf = mutableMapOf<String, Map<String, Long>>()

    override suspend fun durations(channelId: String): Map<String, Long> = durationsOf[channelId].orEmpty()

    /** A channel's pages for [channelPage], newest first; the cursor is the next page's index. */
    val pagesOf = mutableMapOf<String, List<List<VideoItem>>>()
    val pageCalls = mutableListOf<Int>()

    /** Thrown by [channelPage] instead of answering, when set. */
    var pageError: Exception? = null

    override suspend fun channelPage(channelId: String, kind: VideoKind, from: ArchivePage.Cursor?): ArchivePage {
        pageError?.let { throw it }
        val index = from?.handle as? Int ?: 0
        pageCalls += index
        val pages = pagesOf[channelId].orEmpty()
        return ArchivePage(pages.getOrElse(index) { emptyList() }, if (index + 1 < pages.size) ArchivePage.Cursor(index + 1) else null)
    }

    override suspend fun accountSubscriptions(): List<Channel> = account()

    /** What [downloadOffer] answers; [downloadStream] finds its streams in it by id. */
    var offer: DownloadOffer? = null

    override suspend fun downloadOffer(videoId: String, profile: DeviceProfile, prefs: PlaybackPrefs): DownloadOffer =
        offer ?: error("no offer")

    override suspend fun downloadStream(videoId: String, streamId: String): DownloadStream =
        offer!!.let { o -> (o.videos + o.audio).first { it.id == streamId } }

    override suspend fun searchChannels(query: String): List<Channel> = emptyList()
    override suspend fun searchVideos(query: String): List<VideoItem> = emptyList()
    override suspend fun resolveChannel(input: String): Channel = Channel(key, input, input, "")
    override suspend fun fetchChannelFeed(channelId: String): ChannelFeed {
        fetched += channelId
        return (feeds[channelId] ?: error("no feed for $channelId")).invoke()
    }
    override suspend fun resolvePlayback(videoId: String, profile: DeviceProfile, prefs: PlaybackPrefs): PlaybackPlan = error("unused")
    override suspend fun switchVideo(plan: PlaybackPlan.Adaptive, candidateId: String): PlaybackPlan.Adaptive = error("unused")
}

fun item(
    id: String,
    channel: String,
    at: String,
    kind: VideoKind = VideoKind.VIDEO,
    duration: Long? = null,
    source: SourceKey = SourceKey.YOUTUBE,
) = VideoItem(
    source = source,
    remoteId = id,
    channelId = channel,
    channelName = "Channel $channel",
    title = "Video $id",
    publishedAt = Instant.parse(at),
    kind = kind,
    thumbnailUrl = null,
    durationSec = duration,
)

fun feed(channel: String, vararg items: VideoItem) =
    ChannelFeed(items.firstOrNull()?.source ?: SourceKey.YOUTUBE, channel, "Channel $channel", items.toList())

/** Floatplane-shaped: a login, account subscriptions, no Shorts. */
fun fakeFloatplane() = FakeSource(
    key = SourceKey.FLOATPLANE,
    capabilities = SourceCapabilities(
        needsLogin = true, hasShorts = false, hasLive = true, canSearchChannels = false,
        accountSubscriptions = true, prefetchPlayback = false,
    ),
)

/** Sign-ins in memory: the Keystore does not exist under Robolectric. */
class MemorySecretStore(vararg signedIn: SourceKey) : SecretStore {
    private val rows = signedIn.associateWith { StoredAccount("session-${it.id}", "user") }.toMutableMap()
    override fun read(source: SourceKey): StoredAccount? = rows[source]
    override fun write(source: SourceKey, account: StoredAccount) { rows[source] = account }
    override fun delete(source: SourceKey) { rows.remove(source) }
}

private val now = Instant.parse("2026-10-22T12:00:00Z").toEpochMilli()

/** Tiles as the screenshot tests show them: a realistic spread of states. */
val sampleRows: List<VideoRow> = listOf(
    row("a1", "Hard Drives are Expensive… What if I Compress my Windows folder instead?", "Linus Tech Tips", 2 * 3_600_000L, 1_014),
    row("a2", "The ultimate home server build for 2026", "Wolfgang's Channel", 9 * 3_600_000L, 1_476, positionMs = 600_000, watchMs = 1_476_000),
    row("a3", "Why trains in Japan are always on time", "Not Just Bikes", 26 * 3_600_000L, 1_802, watched = true),
    row("a4", "WAN Show October 18, 2026", "Linus Tech Tips", 4 * 24 * 3_600_000L, null, kind = VideoKind.LIVE),
    row("a5", "I tried every budget mechanical keyboard so you don't have to", "Hipyo Tech", 5 * 24 * 3_600_000L, 925, inLater = true),
    row("a6", "A gentle introduction to Rust lifetimes", "No Boilerplate", 6 * 24 * 3_600_000L, 712),
    row("a7", "Premiere: The Making of a Synth", "Look Mum No Computer", -3_600_000L, null, kind = VideoKind.UPCOMING),
    row("a8", "Building the rarest PC", "Linus Tech Tips", 9 * 24 * 3_600_000L, 2_431),
)

val sampleShorts: List<VideoRow> = (1..8).map { i ->
    row("s$i", "Short number $i with a caption that runs long", "Linus Tech Tips", i * 7_200_000L, 42, kind = VideoKind.SHORT)
}

private fun row(
    id: String,
    title: String,
    channel: String,
    ageMs: Long,
    durationSec: Long?,
    kind: VideoKind = VideoKind.VIDEO,
    positionMs: Long? = null,
    watchMs: Long? = null,
    watched: Boolean = false,
    inLater: Boolean = false,
) = VideoRow(
    source = "yt",
    remoteId = id,
    channelId = "UC$id",
    channelName = channel,
    title = title,
    publishedAt = now - ageMs,
    kind = kind.name,
    thumbnailUrl = "test://$id",
    durationSec = durationSec,
    dateApproximate = false,
    dateUnknown = false,
    positionMs = positionMs ?: if (watched) 1_000_000 else null,
    watchDurationMs = watchMs ?: if (watched) 1_000_000 else null,
    watched = watched,
    inWatchLater = inLater,
)

val sampleSubscriptions: List<SubscriptionEntity> = listOf(
    "Linus Tech Tips" to true, "Wolfgang's Channel" to true, "Hipyo Tech" to false, "Look Mum No Computer" to false,
    "No Boilerplate" to false, "Not Just Bikes" to true, "Techmoan" to false, "Technology Connections" to false,
).sortedBy { it.first.lowercase() }.mapIndexed { i, (name, fav) ->
    SubscriptionEntity("yt", "UC$i", name, null, 0, 0, null, "dev", favorite = fav, lastRefreshAt = now - (i + 1) * 600_000L)
}
