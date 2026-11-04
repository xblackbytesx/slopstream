package com.xblackbytesx.slopstream.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.data.FeedRepository
import com.xblackbytesx.slopstream.data.WatchRepository
import com.xblackbytesx.slopstream.data.db.VideoRow
import com.xblackbytesx.slopstream.data.remote.RemoteItem
import com.xblackbytesx.slopstream.data.remote.SendRequest
import com.xblackbytesx.slopstream.data.remote.Target
import com.xblackbytesx.slopstream.data.sync.SyncEngine
import com.xblackbytesx.slopstream.data.sync.SyncException
import com.xblackbytesx.slopstream.data.sync.SyncRefusedException
import com.xblackbytesx.slopstream.ui.components.ListRow
import com.xblackbytesx.slopstream.ui.components.SecondaryButton
import com.xblackbytesx.slopstream.ui.theme.Palette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Play on TV…" (decision 28): [startMs] null starts where this person left off; the player
 * passes its own position. [fromPlayer]: the player closes once the TV has it.
 */
data class PlayOnRequest(val item: RemoteItem, val startMs: Long? = null, val fromPlayer: Boolean = false)

fun VideoRow.toRemoteItem() = RemoteItem(
    source = source,
    videoId = remoteId,
    title = title,
    channelName = channelName,
    channelId = channelId.ifBlank { null },
    thumbnailUrl = thumbnailUrl,
    kind = kind,
)

data class PlayOnState(
    val loading: Boolean = true,
    val targets: List<Target> = emptyList(),
    val sending: String? = null,
    val error: String? = null,
)

@HiltViewModel
class PlayOnViewModel @Inject constructor(
    private val engine: SyncEngine,
    private val watch: WatchRepository,
    private val feed: FeedRepository,
) : ViewModel() {
    /** The menus offer Play on only with a sync server. */
    val available: StateFlow<Boolean> =
        engine.config.map { it.connected }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _state = MutableStateFlow(PlayOnState())
    val state: StateFlow<PlayOnState> = _state.asStateFlow()

    fun load() {
        _state.value = PlayOnState()
        viewModelScope.launch {
            val api = engine.remote()
            _state.value = try {
                PlayOnState(loading = false, targets = api?.targets()?.targets.orEmpty())
            } catch (e: SyncRefusedException) {
                PlayOnState(loading = false, error = "This sync server is too old to play on a TV. Update it.")
            } catch (e: SyncException) {
                PlayOnState(loading = false, error = e.message)
            }
        }
    }

    /** Sends [request] to [target]; [onSent] once the TV has it. */
    fun send(request: PlayOnRequest, target: Target, onSent: () -> Unit) {
        if (_state.value.sending != null) return
        _state.value = _state.value.copy(sending = target.id, error = null)
        viewModelScope.launch {
            val i = request.item
            // The player knows less about the video than the library: fill in what it has.
            val row = feed.row(i.source, i.videoId)
            val item = i.copy(
                channelId = i.channelId ?: row?.channelId?.ifBlank { null },
                thumbnailUrl = i.thumbnailUrl ?: row?.thumbnailUrl,
                kind = i.kind ?: row?.kind,
            )
            val start = request.startMs ?: watch.resumePositionMs(i.source, i.videoId)
            try {
                val api = engine.remote() ?: throw SyncException("Not connected to a sync server")
                api.send(target.id, SendRequest(item, start))
                _state.value = _state.value.copy(sending = null)
                onSent()
            } catch (e: SyncException) {
                _state.value = _state.value.copy(sending = null, error = e.message)
            }
        }
    }
}

/**
 * The TVs open in Slopstream that this person may play on: their own and the shared ones. A TV
 * playing something also offers its remote.
 */
@Composable
fun PlayOnDialog(
    request: PlayOnRequest,
    vm: PlayOnViewModel,
    onDismiss: () -> Unit,
    onSent: (Target) -> Unit,
    onRemote: (Target) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    LaunchedEffect(request) { vm.load() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(400.dp).background(Palette.Surface, RoundedCornerShape(20.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Play on", style = MaterialTheme.typography.titleMedium)
            Text(request.item.title, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.size(4.dp))
            when {
                state.loading -> CircularProgressIndicator(Modifier.size(28.dp).align(Alignment.CenterHorizontally), color = Palette.Accent, strokeWidth = 3.dp)
                state.targets.isEmpty() && state.error == null -> Text(
                    "No TV is open in Slopstream right now. Open it on the TV, connected to the same sync server.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextDim,
                )
                else -> state.targets.forEachIndexed { i, t ->
                    TargetRow(
                        target = t,
                        item = request.item,
                        sending = state.sending == t.id,
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                        onClick = { vm.send(request, t) { onSent(t) } },
                        onRemote = { onRemote(t) },
                    )
                }
            }
            state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Live) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!state.loading) SecondaryButton("Look again", vm::load)
                SecondaryButton("Cancel", onDismiss)
            }
        }
    }
    LaunchedEffect(state.targets.isNotEmpty()) { if (state.targets.isNotEmpty()) runCatching { first.requestFocus() } }
}

@Composable
private fun TargetRow(target: Target, item: RemoteItem, sending: Boolean, modifier: Modifier, onClick: () -> Unit, onRemote: () -> Unit) {
    val playing = target.nowPlaying?.takeIf { !it.state.final }
    val missing = item.source !in target.services
    Row(verticalAlignment = Alignment.CenterVertically) {
        ListRow(modifier = modifier.weight(1f), onClick = if (missing) null else onClick) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Tv, null, tint = if (missing) Palette.TextFaint else Palette.Accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(target.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val line = when {
                        missing -> "Not signed in to ${SourceKey.entries.firstOrNull { it.id == item.source }?.label ?: "this service"}"
                        playing != null -> "Playing: ${playing.item.title}"
                        !target.own -> "Shared TV: your progress stays yours"
                        else -> null
                    }
                    line?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (sending) CircularProgressIndicator(Modifier.size(20.dp), color = Palette.Accent, strokeWidth = 2.dp)
            }
        }
        if (playing != null) {
            IconButton(onClick = onRemote) { Icon(Icons.Filled.SettingsRemote, "Remote for ${target.name}", tint = Palette.Text) }
        }
    }
}
