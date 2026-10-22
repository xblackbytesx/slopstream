package com.xblackbytesx.slopstream.ui.components

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
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xblackbytesx.slopstream.data.db.VideoRow
import com.xblackbytesx.slopstream.ui.theme.Palette

data class VideoMenuActions(
    val play: () -> Unit,
    val toggleWatchLater: () -> Unit,
    val toggleWatched: () -> Unit,
    val openChannel: (() -> Unit)?,
)

/** Long-press (or the remote's menu key) on a tile. */
@Composable
fun VideoMenuDialog(row: VideoRow, actions: VideoMenuActions, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    val guard = remember { HeldKeyGuard() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .onPreviewKeyEvent { guard.consumes(it.type, it.nativeKeyEvent.repeatCount) }
                .width(380.dp)
                .background(Palette.Surface, RoundedCornerShape(20.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(row.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(row.channelName, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            Spacer(Modifier.size(4.dp))
            MenuItem(Icons.Filled.PlayArrow, "Play", Modifier.focusRequester(first)) { onDismiss(); actions.play() }
            if (row.inWatchLater) {
                MenuItem(Icons.Filled.PlaylistRemove, "Remove from Watch later") { onDismiss(); actions.toggleWatchLater() }
            } else {
                MenuItem(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to Watch later") { onDismiss(); actions.toggleWatchLater() }
            }
            if (row.watched == true) {
                MenuItem(Icons.Filled.RemoveDone, "Mark as unwatched") { onDismiss(); actions.toggleWatched() }
            } else {
                MenuItem(Icons.Filled.CheckCircle, "Mark as watched") { onDismiss(); actions.toggleWatched() }
            }
            actions.openChannel?.let { open ->
                MenuItem(Icons.Filled.AccountCircle, "Go to ${row.channelName}") { onDismiss(); open() }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/**
 * A long press of OK opens the menu while the key is still down: its auto-repeats and its
 * release then reach the focused first item and would press Play. Everything is swallowed until
 * a fresh key press (repeat count 0) starts after the menu opened.
 */
internal class HeldKeyGuard {
    private var armed = false

    fun consumes(type: KeyEventType, repeatCount: Int): Boolean {
        if (armed) return false
        if (type == KeyEventType.KeyDown && repeatCount == 0) {
            armed = true
            return false
        }
        return true
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ListRow(modifier = modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Palette.Accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
