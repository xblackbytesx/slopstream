@file:OptIn(ExperimentalCoroutinesApi::class)

package com.xblackbytesx.slopstream.ui.feed

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.core.VideoKind
import com.xblackbytesx.slopstream.data.FeedRepository
import com.xblackbytesx.slopstream.data.RefreshState
import com.xblackbytesx.slopstream.data.ServiceState
import com.xblackbytesx.slopstream.data.Services
import com.xblackbytesx.slopstream.data.Settings
import com.xblackbytesx.slopstream.data.Sources
import com.xblackbytesx.slopstream.data.SubscriptionRepository
import com.xblackbytesx.slopstream.data.WatchRepository
import com.xblackbytesx.slopstream.data.db.VideoRow
import com.xblackbytesx.slopstream.playback.PlaybackRepository
import com.xblackbytesx.slopstream.ui.nav.ChannelRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Tile actions every list screen shares: prefetch on focus, Watch later, watched marks, and
 * the service the screen shows.
 */
abstract class VideoListViewModel(
    protected val feed: FeedRepository,
    private val watch: WatchRepository,
    private val playback: PlaybackRepository,
    private val services: Services,
) : ViewModel() {
    val refresh: StateFlow<RefreshState> = feed.state
    val service: StateFlow<ServiceState> = services.state

    fun selectService(source: SourceKey) {
        viewModelScope.launch { services.select(source) }
    }

    fun prefetch(row: VideoRow) = playback.prefetch(row.source, row.remoteId)

    fun toggleWatchLater(row: VideoRow) {
        viewModelScope.launch { watch.toggleWatchLater(row) }
    }

    fun toggleWatched(row: VideoRow) {
        viewModelScope.launch { watch.setWatched(row.source, row.remoteId, watched = row.watched != true) }
    }

    fun refreshNow() {
        viewModelScope.launch { feed.refreshNow() }
    }
}

@HiltViewModel
class VideosViewModel @Inject constructor(
    feed: FeedRepository,
    watch: WatchRepository,
    playback: PlaybackRepository,
    settings: Settings,
    subscriptions: SubscriptionRepository,
    services: Services,
) : VideoListViewModel(feed, watch, playback, services) {
    val subscriptionCount: StateFlow<Int?> = services.current
        .flatMapLatest { subscriptions.observeCount(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val items: Flow<PagingData<VideoRow>> = combine(services.current, settings.settings.map { it.showLiveInFeed }) { s, live -> s to live }
        .distinctUntilChanged()
        .flatMapLatest { (source, live) -> feed.videos(source, includeLive = live) }
        .cachedIn(viewModelScope)
}

@HiltViewModel
class ShortsViewModel @Inject constructor(
    feed: FeedRepository,
    watch: WatchRepository,
    playback: PlaybackRepository,
    services: Services,
) : VideoListViewModel(feed, watch, playback, services) {
    val items: Flow<PagingData<VideoRow>> = services.current.flatMapLatest { feed.shorts(it) }.cachedIn(viewModelScope)
}

@HiltViewModel
class WatchLaterViewModel @Inject constructor(
    feed: FeedRepository,
    watch: WatchRepository,
    playback: PlaybackRepository,
    services: Services,
) : VideoListViewModel(feed, watch, playback, services) {
    val items: Flow<PagingData<VideoRow>> = services.current.flatMapLatest { watch.watchLater(it) }.cachedIn(viewModelScope)
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    feed: FeedRepository,
    watch: WatchRepository,
    playback: PlaybackRepository,
    services: Services,
) : VideoListViewModel(feed, watch, playback, services) {
    val items: Flow<PagingData<VideoRow>> = services.current.flatMapLatest { watch.history(it) }.cachedIn(viewModelScope)
}

@HiltViewModel
class ChannelViewModel @Inject constructor(
    savedState: SavedStateHandle,
    feed: FeedRepository,
    watch: WatchRepository,
    playback: PlaybackRepository,
    private val subscriptions: SubscriptionRepository,
    services: Services,
    sources: Sources,
) : VideoListViewModel(feed, watch, playback, services) {
    val route: ChannelRoute = savedState.toRoute()
    private val capabilities = sources[route.source].capabilities

    /** The service's own tabs: Floatplane has no Shorts. */
    val tabs: List<ChannelTab> = ChannelTab.entries.filter { t ->
        when (t) {
            ChannelTab.VIDEOS -> true
            ChannelTab.SHORTS -> capabilities.hasShorts
            ChannelTab.LIVE -> capabilities.hasLive
        }
    }

    /** Account subscriptions are managed on the service's site. */
    val canUnsubscribe: Boolean = !capabilities.accountSubscriptions

    private val tab = kotlinx.coroutines.flow.MutableStateFlow(ChannelTab.VIDEOS)
    val selectedTab: StateFlow<ChannelTab> = tab

    val items: Flow<PagingData<VideoRow>> = tab
        .flatMapLatest { t -> feed.channel(route.source, route.channelId, t.kinds) }
        .cachedIn(viewModelScope)

    val favorite: StateFlow<Boolean> = subscriptions.observe(route.source, route.channelId)
        .map { it != null && it.deletedAt == null && it.favorite }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * The followed channel's own name: a tile opens its channel with the name on the tile,
     * which on Floatplane is the sub-channel (TechLinked) rather than the creator.
     */
    val name: StateFlow<String> = subscriptions.observe(route.source, route.channelId)
        .map { it?.name ?: route.name }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), route.name)

    fun select(t: ChannelTab) { tab.value = t }

    fun setFavorite(on: Boolean) { viewModelScope.launch { subscriptions.setFavorite(route.source, route.channelId, on) } }

    fun unsubscribe(onDone: () -> Unit) {
        viewModelScope.launch {
            subscriptions.unsubscribe(route.source, route.channelId)
            onDone()
        }
    }
}

enum class ChannelTab(val label: String, val kinds: List<VideoKind>) {
    VIDEOS("Videos", listOf(VideoKind.VIDEO)),
    SHORTS("Shorts", listOf(VideoKind.SHORT)),
    LIVE("Live", listOf(VideoKind.LIVE, VideoKind.UPCOMING)),
}
