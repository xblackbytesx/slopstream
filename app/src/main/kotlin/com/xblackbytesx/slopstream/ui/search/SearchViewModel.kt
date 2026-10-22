package com.xblackbytesx.slopstream.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xblackbytesx.slopstream.core.Channel
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.core.VideoItem
import com.xblackbytesx.slopstream.data.FeedRepository
import com.xblackbytesx.slopstream.data.ServiceState
import com.xblackbytesx.slopstream.data.Services
import com.xblackbytesx.slopstream.data.Sources
import com.xblackbytesx.slopstream.data.SubscriptionRepository
import com.xblackbytesx.slopstream.data.WatchRepository
import com.xblackbytesx.slopstream.data.db.SubscriptionEntity
import com.xblackbytesx.slopstream.data.db.VideoRow
import com.xblackbytesx.slopstream.playback.PlaybackRepository
import com.xblackbytesx.slopstream.ui.components.matchesWords
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** YouTube-wide results, for the query they were asked for. */
data class GlobalResults(
    val query: String,
    val loading: Boolean = true,
    val channels: List<Channel> = emptyList(),
    val videos: List<VideoRow> = emptyList(),
    val error: String? = null,
)

data class SearchState(
    /** The service searched: its library, and its own search when [canSearchGlobal]. */
    val service: SourceKey = SourceKey.YOUTUBE,
    val canSearchGlobal: Boolean = true,
    val query: String = "",
    /** "Also search YouTube". Off by default; never saved, so every visit starts local. */
    val global: Boolean = false,
    /** Null until a query is long enough to search. */
    val channels: List<SubscriptionEntity>? = null,
    val library: List<VideoRow> = emptyList(),
    val globalResults: GlobalResults? = null,
    val subscribed: Set<String> = emptySet(),
)

/**
 * The library (followed channels, their cached videos, Watch later) is searched as you type.
 * YouTube itself only when "Also search YouTube" is on and the search is submitted, so typing
 * never sends a request per keystroke.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val feed: FeedRepository,
    private val watch: WatchRepository,
    private val subscriptions: SubscriptionRepository,
    private val sources: Sources,
    private val playback: PlaybackRepository,
    private val services: Services,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()
    val service: StateFlow<ServiceState> = services.state
    private var libraryJob: Job? = null
    private var globalJob: Job? = null

    init {
        // Another service, another library: results never mix services (decision 12).
        viewModelScope.launch {
            services.state.collect { s ->
                if (s.current == _state.value.service && s.capabilities.canSearchVideos == _state.value.canSearchGlobal) return@collect
                globalJob?.cancel()
                _state.update {
                    it.copy(
                        service = s.current,
                        canSearchGlobal = s.capabilities.canSearchVideos,
                        global = it.global && s.capabilities.canSearchVideos,
                        globalResults = null,
                        subscribed = emptySet(),
                    )
                }
                searchLibrary(debounceMs = 0)
            }
        }
    }

    fun selectService(source: SourceKey) { viewModelScope.launch { services.select(source) } }

    fun setQuery(q: String) {
        _state.update { s -> s.copy(query = q, globalResults = s.globalResults?.takeIf { it.query == q.trim() }) }
        searchLibrary(debounceMs = 250)
    }

    fun setGlobal(on: Boolean) {
        _state.update { it.copy(global = on, globalResults = if (on) it.globalResults else null) }
        if (on) submit()
    }

    /** The keyboard's search key or the Search button. */
    fun submit() {
        searchLibrary(debounceMs = 0)
        val q = _state.value.query.trim()
        if (!_state.value.global || !_state.value.canSearchGlobal || q.isEmpty()) return
        globalJob?.cancel()
        globalJob = viewModelScope.launch {
            _state.update { it.copy(globalResults = GlobalResults(q)) }
            val yt = sources[_state.value.service]
            val (channels, videos) = coroutineScope {
                // Distinct: result pages repeat items now and then, and the grid keys by id.
                val c = async { runCatching { yt.searchChannels(q).distinctBy { it.remoteId } } }
                val v = async { runCatching { yt.searchVideos(q).distinctBy { it.remoteId }.map { it.toRow() }.let { watch.annotate(it) } } }
                c.await() to v.await()
            }
            val found = channels.getOrDefault(emptyList())
            val subscribed = found.filter { subscriptions.isSubscribed(it.source, it.remoteId) }.map { it.remoteId }.toSet()
            val error = (videos.exceptionOrNull() ?: channels.exceptionOrNull())?.let { it.message ?: "Search failed" }
            _state.update {
                it.copy(
                    globalResults = GlobalResults(q, loading = false, channels = found, videos = videos.getOrDefault(emptyList()), error = error),
                    subscribed = it.subscribed + subscribed,
                )
            }
        }
    }

    private fun searchLibrary(debounceMs: Long) {
        libraryJob?.cancel()
        libraryJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val q = _state.value.query.trim()
            val source = _state.value.service
            if (q.length < MIN_QUERY) {
                _state.update { it.copy(channels = null, library = emptyList()) }
                return@launch
            }
            val channels = subscriptions.observe(source).first().filter { matchesWords(it.name, q) }
            val library = feed.searchLibrary(source, q)
            _state.update { if (it.query.trim() == q && it.service == source) it.copy(channels = channels, library = library) else it }
        }
    }

    fun prefetch(row: VideoRow) = playback.prefetch(row.source, row.remoteId)

    fun toggleWatchLater(row: VideoRow) = viewModelScope.launch { watch.toggleWatchLater(row); refreshMarks() }

    fun toggleWatched(row: VideoRow) = viewModelScope.launch {
        watch.setWatched(row.source, row.remoteId, watched = row.watched != true)
        refreshMarks()
    }

    fun toggleSubscribe(channel: Channel) = viewModelScope.launch {
        if (channel.remoteId in _state.value.subscribed) {
            subscriptions.unsubscribe(channel.source.id, channel.remoteId)
            _state.update { it.copy(subscribed = it.subscribed - channel.remoteId) }
        } else {
            subscriptions.subscribe(listOf(channel))
            _state.update { it.copy(subscribed = it.subscribed + channel.remoteId) }
            feed.refreshChannels(channel.source, listOf(channel.remoteId))
        }
    }

    /** Watched and Watch later marks after a menu action; the lists are snapshots, not flows. */
    private suspend fun refreshMarks() {
        searchLibrary(debounceMs = 0)
        val global = _state.value.globalResults ?: return
        val videos = watch.annotate(global.videos)
        _state.update { s -> s.copy(globalResults = s.globalResults?.takeIf { it.query == global.query }?.copy(videos = videos)) }
    }

    private fun VideoItem.toRow() = VideoRow(
        source = source.id,
        remoteId = remoteId,
        channelId = channelId,
        channelName = channelName,
        title = title,
        publishedAt = publishedAt.toEpochMilli(),
        kind = kind.name,
        thumbnailUrl = thumbnailUrl,
        durationSec = durationSec,
        dateApproximate = dateApproximate,
        positionMs = null,
        watchDurationMs = null,
        watched = null,
        inWatchLater = false,
    )

    companion object {
        const val MIN_QUERY = 2
    }
}
