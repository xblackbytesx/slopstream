package com.xblackbytesx.slopstream.ui.subscriptions

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.xblackbytesx.slopstream.core.Channel
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.data.FeedRepository
import com.xblackbytesx.slopstream.data.ImportSummary
import com.xblackbytesx.slopstream.data.RefreshState
import com.xblackbytesx.slopstream.data.ServiceState
import com.xblackbytesx.slopstream.data.Services
import com.xblackbytesx.slopstream.data.Sources
import com.xblackbytesx.slopstream.data.SubscriptionRepository
import com.xblackbytesx.slopstream.data.db.SubscriptionEntity
import com.xblackbytesx.slopstream.data.importer.Importer
import com.xblackbytesx.slopstream.data.importer.LanImportServer
import com.xblackbytesx.slopstream.ui.nav.AddChannelRoute
import com.xblackbytesx.slopstream.ui.components.matchesWords
import com.xblackbytesx.slopstream.youtube.YoutubeIds
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SubscriptionsViewModel @Inject constructor(
    private val subscriptions: SubscriptionRepository,
    private val feed: FeedRepository,
    private val services: Services,
) : ViewModel() {
    val service: StateFlow<ServiceState> = services.state
    val channels: StateFlow<List<SubscriptionEntity>?> = services.current
        .flatMapLatest { subscriptions.observe(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val refresh: StateFlow<RefreshState> = feed.state

    fun selectService(source: SourceKey) { viewModelScope.launch { services.select(source) } }

    fun refreshAll() { viewModelScope.launch { feed.refreshNow() } }
    fun unsubscribe(s: SubscriptionEntity) { viewModelScope.launch { subscriptions.unsubscribe(s.source, s.remoteId) } }
    fun setFavorite(s: SubscriptionEntity, favorite: Boolean) { viewModelScope.launch { subscriptions.setFavorite(s.source, s.remoteId, favorite) } }
}

data class AddChannelState(
    val service: SourceKey = SourceKey.YOUTUBE,
    /** Picking from the service's full list (Sauce+): [query] filters it as you type. */
    val catalog: Boolean = false,
    val query: String = "",
    val searching: Boolean = false,
    val results: List<Channel> = emptyList(),
    val subscribed: Set<String> = emptySet(),
    val error: String? = null,
) {
    /** What is shown: a catalog filtered by [query], or search results as they came. */
    val shown: List<Channel> get() = if (catalog) results.filter { matchesWords(it.name, query) } else results
}

@HiltViewModel
class AddChannelViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val sources: Sources,
    private val subscriptions: SubscriptionRepository,
    private val feed: FeedRepository,
    services: Services,
) : ViewModel() {
    private val service = services.current.value
    private val source = sources[service]
    private val _state = MutableStateFlow(
        AddChannelState(service = service, catalog = source.capabilities.catalog, query = savedState.toRoute<AddChannelRoute>().query),
    )
    val state: StateFlow<AddChannelState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        if (_state.value.catalog) loadCatalog() else if (_state.value.query.isNotBlank()) search()
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    private fun loadCatalog() {
        job = viewModelScope.launch {
            _state.update { it.copy(searching = true, error = null) }
            val result = runCatching { source.catalog() }
            val subscribed = subscribedIds(result.getOrDefault(emptyList()))
            _state.update {
                it.copy(
                    searching = false,
                    results = result.getOrDefault(emptyList()),
                    subscribed = subscribed,
                    error = result.exceptionOrNull()?.let { e -> e.message ?: "Could not load the list" },
                )
            }
        }
    }

    private suspend fun subscribedIds(channels: List<Channel>): Set<String> =
        channels.filter { subscriptions.isSubscribed(it.source, it.remoteId) }.map { it.remoteId }.toSet()

    /** Follows everything shown (the whole catalog, or what the filter leaves). */
    fun addAll() {
        val missing = _state.value.shown.filter { it.remoteId !in _state.value.subscribed }
        if (missing.isEmpty()) return
        viewModelScope.launch {
            subscriptions.subscribe(missing)
            _state.update { it.copy(subscribed = it.subscribed + missing.map { c -> c.remoteId }) }
            feed.refreshChannels(service, missing.map { it.remoteId })
        }
    }

    /** A pasted URL, @handle or id resolves to one channel; anything else is a search. */
    fun search() {
        val q = _state.value.query.trim()
        if (q.isEmpty() || _state.value.catalog) return
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(searching = true, error = null) }
            val direct = q.startsWith("@") || "youtube.com/" in q || "youtu.be/" in q || YoutubeIds.isChannelId(q)
            val result = runCatching {
                if (direct) listOf(source.resolveChannel(q)) else source.searchChannels(q)
            }
            val subscribed = subscribedIds(result.getOrDefault(emptyList()))
            _state.update {
                it.copy(
                    searching = false,
                    results = result.getOrDefault(emptyList()),
                    subscribed = subscribed,
                    error = result.exceptionOrNull()?.let { e -> e.message ?: "Search failed" }
                        ?: if (result.getOrNull().isNullOrEmpty()) "No channels found" else null,
                )
            }
        }
    }

    fun toggle(channel: Channel) {
        viewModelScope.launch {
            if (channel.remoteId in _state.value.subscribed) {
                subscriptions.unsubscribe(channel.source.id, channel.remoteId)
                _state.update { it.copy(subscribed = it.subscribed - channel.remoteId) }
            } else {
                subscriptions.subscribe(listOf(channel))
                _state.update { it.copy(subscribed = it.subscribed + channel.remoteId) }
                feed.refreshChannels(channel.source, listOf(channel.remoteId))
            }
        }
    }
}

data class ImportState(
    val busy: Boolean = false,
    val progress: String? = null,
    val result: ImportSummary? = null,
    val error: String? = null,
    val lanAddress: String? = null,
)

@HiltViewModel
class ImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importer: Importer,
    private val subscriptions: SubscriptionRepository,
    private val feed: FeedRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()

    // The LAN server outlives a single upload but not the screen.
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = LanImportServer(context) { bytes -> importBytes(bytes) }

    fun startLan() {
        if (_state.value.lanAddress != null) return
        val address = runCatching { server.start(serverScope) }.getOrNull()
        _state.update { it.copy(lanAddress = address, error = if (address == null) "No network address for the LAN import page" else it.error) }
    }

    fun importUri(uri: Uri) {
        viewModelScope.launch {
            val bytes = runCatching {
                withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            }.getOrNull()
            if (bytes == null) _state.update { it.copy(error = "Could not read that file") } else importBytes(bytes)
        }
    }

    /** Returns a one-line summary (shown on the LAN page too). */
    private suspend fun importBytes(bytes: ByteArray): String {
        _state.update { it.copy(busy = true, error = null, result = null, progress = "Reading export…") }
        return try {
            val parsed = importer.parse(bytes)
            val (summary, added) = subscriptions.import(parsed.subscriptions, parsed.unresolved) { done, total ->
                _state.update { it.copy(progress = "Looking up channels without an id: $done of $total") }
            }
            _state.update { it.copy(busy = false, progress = null, result = summary) }
            // Fetch the new channels in the background; the feed fills in as they arrive.
            serverScope.launch { delay(300); feed.refreshChannels(SourceKey.YOUTUBE, added.map { it.remoteId }) }
            buildString {
                append("Imported ${summary.added} new channels")
                if (summary.alreadySubscribed > 0) append(", ${summary.alreadySubscribed} already followed")
                if (summary.failed.isNotEmpty()) append(", ${summary.failed.size} not found")
                append('.')
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Import failed"
            _state.update { it.copy(busy = false, progress = null, error = msg) }
            "Import failed: $msg"
        }
    }

    override fun onCleared() {
        server.stop()
        // serverScope keeps the post-import refresh running; FeedRepository is app-scoped.
    }
}
