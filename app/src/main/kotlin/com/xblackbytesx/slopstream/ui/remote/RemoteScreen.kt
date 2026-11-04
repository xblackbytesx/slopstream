package com.xblackbytesx.slopstream.ui.remote

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import coil3.compose.AsyncImage
import com.xblackbytesx.slopstream.data.remote.NowPlaying
import com.xblackbytesx.slopstream.data.remote.PlayState
import com.xblackbytesx.slopstream.data.remote.RemoteAction
import com.xblackbytesx.slopstream.data.remote.ControlRequest
import com.xblackbytesx.slopstream.data.sync.SyncEngine
import com.xblackbytesx.slopstream.data.sync.SyncException
import com.xblackbytesx.slopstream.data.sync.SyncRefusedException
import com.xblackbytesx.slopstream.ui.components.EmptyState
import com.xblackbytesx.slopstream.ui.components.ScreenHeader
import com.xblackbytesx.slopstream.ui.components.SecondaryButton
import com.xblackbytesx.slopstream.ui.components.formatPosition
import com.xblackbytesx.slopstream.ui.nav.RemoteRoute
import com.xblackbytesx.slopstream.ui.theme.LocalDims
import com.xblackbytesx.slopstream.ui.theme.Palette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** [receivedAt]: elapsed-realtime of the last status, to move a playing position on from. */
data class RemoteUiState(
    val loaded: Boolean = false,
    val online: Boolean = true,
    val now: NowPlaying? = null,
    val receivedAt: Long = 0,
    val error: String? = null,
) {
    /** Where the TV is now, as far as this phone can tell. */
    fun positionAt(elapsed: Long): Long {
        val n = now ?: return 0
        val moved = if (n.state == PlayState.PLAYING) elapsed - receivedAt else 0
        val p = n.positionMs + moved
        return if (n.durationMs > 0) p.coerceIn(0, n.durationMs) else p.coerceAtLeast(0)
    }
}

/** Follows what a TV plays (the server's long-poll) and steers it (decision 28). */
@HiltViewModel
class RemoteViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val engine: SyncEngine,
) : ViewModel() {
    val route: RemoteRoute = savedState.toRoute()
    private val _state = MutableStateFlow(RemoteUiState())
    val state: StateFlow<RemoteUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            var since: Long? = null
            while (isActive) {
                val api = engine.remote()
                if (api == null) {
                    _state.update { it.copy(loaded = true, error = "Not connected to a sync server") }
                    return@launch
                }
                try {
                    val s = api.status(route.target, since)
                    since = s.generation
                    _state.value = RemoteUiState(loaded = true, online = s.online, now = s.nowPlaying, receivedAt = SystemClock.elapsedRealtime())
                } catch (e: SyncRefusedException) {
                    // Unshared, disconnected, or a server that has no remote.
                    _state.update { it.copy(loaded = true, online = false, now = null, error = "${route.name} is no longer available.") }
                    delay(RETRY_MS)
                } catch (e: SyncException) {
                    _state.update { it.copy(error = e.message) }
                    delay(RETRY_MS)
                }
            }
        }
    }

    fun control(action: RemoteAction, positionMs: Long? = null) {
        val session = _state.value.now?.session ?: return
        viewModelScope.launch {
            try {
                engine.remote()?.control(route.target, ControlRequest(session, action, positionMs))
                _state.update { it.copy(error = null) }
            } catch (e: SyncException) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    private companion object {
        const val RETRY_MS = 5_000L
    }
}

@Composable
fun RemoteScreen(onBack: () -> Unit, vm: RemoteViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val now = state.now?.takeIf { !it.state.final }
    val status = when {
        !state.loaded -> null
        !state.online -> "Not open in Slopstream right now"
        now == null -> "Nothing playing"
        else -> when (now.state) {
            PlayState.STARTING -> "Starting…"
            PlayState.BUFFERING -> "Loading…"
            PlayState.PLAYING -> "Playing"
            else -> "Paused"
        } + if (now.guest) " · your progress" else ""
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(vm.route.name, status = status)
        if (now == null) {
            if (state.loaded) {
                EmptyState(
                    title = if (state.online) "Nothing playing" else "${vm.route.name} is closed",
                    body = state.error ?: "Send a video with Play on TV… in a video's menu or the player's More.",
                ) { SecondaryButton("Back", onBack) }
            }
        } else {
            NowPlayingPanel(state, now, vm::control)
        }
    }
}

@Composable
private fun NowPlayingPanel(state: RemoteUiState, now: NowPlaying, control: (RemoteAction, Long?) -> Unit) {
    val dims = LocalDims.current
    var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(now.state) {
        while (now.state == PlayState.PLAYING) {
            delay(500)
            tick = SystemClock.elapsedRealtime()
        }
    }
    val position = state.positionAt(tick)
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = dims.gutter).widthIn(max = 640.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        now.item.thumbnailUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)),
            )
        }
        Text(now.item.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(now.item.channelName, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
        if (now.durationMs > 0) {
            Slider(
                value = dragging ?: (position.toFloat() / now.durationMs).coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { control(RemoteAction.SEEK, (it * now.durationMs).toLong()) }
                    dragging = null
                },
                colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent),
            )
            Row {
                val shown = dragging?.let { (it * now.durationMs).toLong() } ?: position
                Text(formatPosition(shown), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, modifier = Modifier.weight(1f))
                Text(formatPosition(now.durationMs), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val seekable = now.durationMs > 0
            IconButton(onClick = { control(RemoteAction.SEEK, (position - 10_000).coerceAtLeast(0)) }, enabled = seekable) {
                Icon(Icons.Filled.Replay10, "Back 10 seconds", Modifier.size(32.dp))
            }
            val playing = now.state == PlayState.PLAYING || now.state == PlayState.BUFFERING
            FilledIconButton(
                onClick = { control(if (playing) RemoteAction.PAUSE else RemoteAction.RESUME, null) },
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
            ) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(40.dp))
            }
            IconButton(onClick = { control(RemoteAction.SEEK, (position + 10_000).coerceAtMost(now.durationMs)) }, enabled = seekable) {
                Icon(Icons.Filled.Forward10, "Forward 10 seconds", Modifier.size(32.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            SecondaryButton("Stop on the TV", { control(RemoteAction.STOP, null) }, icon = Icons.Filled.Stop)
        }
        state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Live) }
        Spacer(Modifier.height(24.dp))
    }
}
