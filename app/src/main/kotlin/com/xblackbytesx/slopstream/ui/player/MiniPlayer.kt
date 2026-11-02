package com.xblackbytesx.slopstream.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import coil3.compose.AsyncImage
import com.xblackbytesx.slopstream.core.playback.PlaybackPlan
import com.xblackbytesx.slopstream.playback.AudioPlayback
import com.xblackbytesx.slopstream.playback.PlayerEngine
import com.xblackbytesx.slopstream.ui.theme.Palette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import java.io.File
import javax.inject.Inject

@HiltViewModel
class MiniPlayerViewModel @Inject constructor(private val audio: AudioPlayback) : ViewModel() {
    val current = audio.current

    fun close() = audio.stop()
}

/**
 * An audio-only play that left its player screen (decision 27): artwork, title, play/pause
 * and close, above the navigation. Selecting it opens the full player on the same engine.
 */
@Composable
fun MiniPlayer(engine: PlayerEngine, onOpen: (source: String, videoId: String, title: String, channel: String) -> Unit, onClose: () -> Unit) {
    val state by engine.state.collectAsStateWithLifecycle()
    val plan = state.plan as? PlaybackPlan.Local ?: return
    val source = state.source ?: return
    val videoId = state.videoId ?: return
    val player = engine.player
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(player) {
        while (true) {
            val duration = player.duration
            progress = if (duration == C.TIME_UNSET || duration <= 0) 0f else (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
            delay(500)
        }
    }
    Column(Modifier.fillMaxWidth().background(Palette.SurfaceHigh)) {
        LinearProgressIndicator(progress = { progress }, color = Palette.Accent, trackColor = Palette.Surface, modifier = Modifier.fillMaxWidth().height(2.dp))
        Row(
            Modifier.fillMaxWidth().clickable { onOpen(source, videoId, plan.title, plan.channelName) }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(64.dp).height(36.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Surface)) {
                AsyncImage(model = plan.artworkPath?.let(::File), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(plan.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (plan.channelName.isNotBlank()) {
                    Text(plan.channelName, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = {
                when {
                    state.ended -> { player.seekTo(0); player.play() }
                    player.isPlaying -> player.pause()
                    else -> player.play()
                }
            }) {
                Icon(
                    when {
                        state.ended -> Icons.Filled.Replay
                        state.playing -> Icons.Filled.Pause
                        else -> Icons.Filled.PlayArrow
                    },
                    if (state.playing) "Pause" else "Play",
                )
            }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Stop and close") }
        }
    }
}
