package com.xblackbytesx.slopstream.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.xblackbytesx.slopstream.core.playback.VideoCandidate
import com.xblackbytesx.slopstream.data.db.DownloadEntity
import com.xblackbytesx.slopstream.data.db.DownloadState
import com.xblackbytesx.slopstream.data.download.Downloads
import com.xblackbytesx.slopstream.ui.components.EmptyState
import com.xblackbytesx.slopstream.ui.components.ListRow
import com.xblackbytesx.slopstream.ui.components.ScreenHeader
import com.xblackbytesx.slopstream.ui.components.formatDuration
import com.xblackbytesx.slopstream.ui.theme.LocalDims
import com.xblackbytesx.slopstream.ui.theme.Palette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** A download as the list shows it, with its saved thumbnail when there is one. */
data class DownloadItem(val row: DownloadEntity, val thumbnail: File?)

data class DownloadsSummary(val items: List<DownloadItem> = emptyList(), val used: Long = 0, val free: Long = 0)

@HiltViewModel
class DownloadsViewModel @Inject constructor(private val downloads: Downloads) : ViewModel() {
    /** Recounted on every change, a progress step included: the totals move with the download. */
    val summary: StateFlow<DownloadsSummary> = downloads.all
        .map { rows -> DownloadsSummary(rows.map { DownloadItem(it, downloads.thumbnail(it)) }, downloads.usedBytes(), downloads.freeBytes()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsSummary())

    fun remove(row: DownloadEntity) { viewModelScope.launch { downloads.remove(row.source, row.remoteId) } }
    fun retry(row: DownloadEntity) { viewModelScope.launch { downloads.retry(row.source, row.remoteId) } }
    fun removeAll() { viewModelScope.launch { downloads.removeAll() } }
}

/**
 * Videos saved on this device (decision 24): what they take, what is left, and deleting one or
 * all. Reached from Watch later, next to History.
 */
@Composable
fun DownloadsScreen(play: (DownloadEntity) -> Unit, vm: DownloadsViewModel = hiltViewModel()) {
    val summary by vm.summary.collectAsStateWithLifecycle()
    var confirmAll by remember { mutableStateOf(false) }
    val dims = LocalDims.current
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Downloads",
            status = "${summary.items.size} ${if (summary.items.size == 1) "video" else "videos"} · ${bytes(summary.used)} used · ${bytes(summary.free)} free",
            actions = {
                if (summary.items.isNotEmpty()) IconButton(onClick = { confirmAll = true }) { Icon(Icons.Filled.DeleteSweep, "Delete all downloads") }
            },
        )
        if (summary.items.isEmpty()) {
            EmptyState("No downloads", "Long-press a video, or open More in the player, and pick Download to watch it without a connection.")
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(start = dims.gutter, end = dims.gutter, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(summary.items, key = { Downloads.key(it.row.source, it.row.remoteId) }) { item ->
                DownloadRow(item, onOpen = {
                    when (item.row.state) {
                        DownloadState.DONE.name -> play(item.row)
                        DownloadState.FAILED.name -> vm.retry(item.row)
                    }
                }, onDelete = { vm.remove(item.row) })
            }
        }
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Delete all downloads?") },
            text = { Text("This frees ${bytes(summary.used)} on this device. Your watch progress stays.") },
            confirmButton = { TextButton(onClick = { vm.removeAll(); confirmAll = false }) { Text("Delete all") } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
            containerColor = Palette.Surface,
        )
    }
}

@Composable
private fun DownloadRow(item: DownloadItem, onOpen: () -> Unit, onDelete: () -> Unit) {
    val r = item.row
    val state = DownloadState.valueOf(r.state)
    ListRow(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Palette.SurfaceHigh)) {
                AsyncImage(model = item.thumbnail ?: r.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(r.channelName, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1)
                val quality = VideoCandidate(r.videoStreamId, r.codecs, r.width, r.height, r.fps, 0, r.hdr).label
                val line = when (state) {
                    DownloadState.DONE -> "$quality · ${bytes(r.sizeBytes)} · ${formatDuration(r.durationSec)}"
                    DownloadState.QUEUED -> r.error ?: "Waiting · $quality"
                    DownloadState.RUNNING -> "${bytes(r.doneBytes)} of ${bytes(r.sizeBytes)} · $quality"
                    DownloadState.FAILED -> "Failed: ${r.error ?: "unknown error"} · select to try again"
                }
                Text(line, style = MaterialTheme.typography.labelMedium, color = if (state == DownloadState.FAILED) Palette.Live else Palette.TextFaint, maxLines = 2)
                if (state == DownloadState.RUNNING && r.sizeBytes > 0) {
                    LinearProgressIndicator(
                        progress = { (r.doneBytes.toFloat() / r.sizeBytes).coerceIn(0f, 1f) },
                        color = Palette.Accent,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp),
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, if (state == DownloadState.DONE) "Delete download" else "Cancel download")
            }
        }
    }
}
