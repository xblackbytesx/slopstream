package com.xblackbytesx.slopstream.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.xblackbytesx.slopstream.core.VideoKind
import com.xblackbytesx.slopstream.data.AppSettings
import com.xblackbytesx.slopstream.data.FeedRepository
import com.xblackbytesx.slopstream.data.Settings
import com.xblackbytesx.slopstream.data.Sources
import com.xblackbytesx.slopstream.data.WatchRepository
import com.xblackbytesx.slopstream.data.db.VideoRow
import com.xblackbytesx.slopstream.playback.AudioPlayback
import com.xblackbytesx.slopstream.playback.MediaSources
import com.xblackbytesx.slopstream.playback.PlaybackRepository
import com.xblackbytesx.slopstream.playback.PlayerEngine
import com.xblackbytesx.slopstream.ui.comments.CommentsLoader
import com.xblackbytesx.slopstream.ui.nav.PlayerRoute
import com.xblackbytesx.slopstream.ui.nav.ShortsPlayerRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    app: Application,
    savedState: SavedStateHandle,
    playback: PlaybackRepository,
    mediaSources: MediaSources,
    watch: WatchRepository,
    feed: FeedRepository,
    settings: Settings,
    sources: Sources,
    private val audio: AudioPlayback,
) : AndroidViewModel(app) {
    val route: PlayerRoute = savedState.toRoute()

    /** The mini player's engine when it plays this very video: it carries on, no reload. */
    private val reclaimed = audio.reclaim(route.source, route.videoId)

    val engine = reclaimed ?: PlayerEngine(app, playback, mediaSources, watch, feed, settings).also { audio.stop() }
    val settings: StateFlow<AppSettings?> = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** This video's comments, loaded only when the panel opens (decision 20). Null: none here. */
    val comments: CommentsLoader? = sources[route.source].takeIf { it.capabilities.hasComments }
        ?.let { CommentsLoader(it, route.videoId, viewModelScope) }

    init {
        if (reclaimed == null) engine.load(route.source, route.videoId)
    }

    /** Leaving an audio-only play hands it to the mini player instead of ending it. */
    override fun onCleared() {
        if (engine.audioOnly && engine.state.value.error == null) audio.adopt(engine) else engine.release()
    }
}

@HiltViewModel
class ShortsPlayerViewModel @Inject constructor(
    app: Application,
    savedState: SavedStateHandle,
    private val playback: PlaybackRepository,
    mediaSources: MediaSources,
    watch: WatchRepository,
    private val feed: FeedRepository,
    settings: Settings,
    audio: AudioPlayback,
) : AndroidViewModel(app) {
    private val route: ShortsPlayerRoute = savedState.toRoute()
    val engine = PlayerEngine(app, playback, mediaSources, watch, feed, settings, loop = true).also { audio.stop() }

    private val _items = MutableStateFlow<List<VideoRow>?>(null)
    val items: StateFlow<List<VideoRow>?> = _items.asStateFlow()
    var startIndex = 0
        private set

    init {
        viewModelScope.launch {
            val list = feed.shortsList(route.source, route.channelId)
            val start = list.indexOfFirst { it.remoteId == route.videoId }
            // A Short outside the list (a shared link, an older one, an unfollowed channel)
            // plays first and the list follows; never another Short in its place.
            if (start >= 0) {
                startIndex = start
                _items.value = list
            } else {
                startIndex = 0
                _items.value = listOf(feed.row(route.source, route.videoId) ?: placeholder(route.source, route.videoId)) + list
            }
        }
    }

    /** Called when the pager settles on [index]: play it and warm up its neighbours. */
    fun onPage(index: Int) {
        val list = _items.value ?: return
        val row = list.getOrNull(index) ?: return
        if (engine.state.value.videoId != row.remoteId) engine.load(row.source, row.remoteId, startAtMs = 0)
        list.getOrNull(index + 1)?.let { playback.prefetch(it.source, it.remoteId) }
        list.getOrNull(index - 1)?.let { playback.prefetch(it.source, it.remoteId) }
    }

    override fun onCleared() = engine.release()

    private fun placeholder(source: String, videoId: String) = VideoRow(
        source = source, remoteId = videoId, channelId = "", channelName = "", title = "",
        publishedAt = 0, kind = VideoKind.SHORT.name, thumbnailUrl = null, durationSec = null,
        dateApproximate = false, dateUnknown = true, positionMs = null, watchDurationMs = null,
        watched = null, inWatchLater = false,
    )
}
