package com.xblackbytesx.slopstream.ui.downloads

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xblackbytesx.slopstream.core.DownloadOffer
import com.xblackbytesx.slopstream.core.DownloadStream
import com.xblackbytesx.slopstream.data.db.DownloadState
import com.xblackbytesx.slopstream.data.download.Downloads
import com.xblackbytesx.slopstream.data.download.NotEnoughSpaceException
import com.xblackbytesx.slopstream.ui.components.ListRow
import com.xblackbytesx.slopstream.ui.theme.LocalIsTv
import com.xblackbytesx.slopstream.ui.theme.Palette
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Every download's state by [Downloads.key], for the tile badges. Provided at the app root. */
val LocalDownloadStates = compositionLocalOf<Map<String, DownloadState>> { emptyMap() }

/** What a video's menus offer for downloading, by where it stands. */
data class DownloadMenu(val state: DownloadState?, val onClick: () -> Unit) {
    val label: String
        get() = when (state) {
            null, DownloadState.FAILED -> "Download…"
            DownloadState.QUEUED, DownloadState.RUNNING -> "Cancel download"
            DownloadState.DONE -> "Remove download"
        }
    val icon: ImageVector
        get() = when (state) {
            null, DownloadState.FAILED -> Icons.Filled.Download
            DownloadState.QUEUED, DownloadState.RUNNING -> Icons.Filled.Close
            DownloadState.DONE -> Icons.Filled.Delete
        }
}

/** The quality picker, while it is open: loading, the offer, or why not. */
data class DownloadSheet(
    val source: String,
    val videoId: String,
    val title: String,
    val offer: DownloadOffer? = null,
    val free: Long = 0,
    val error: String? = null,
    val starting: Boolean = false,
)

/** Download, cancel and remove from any screen's video menu or the player (decision 24). */
@HiltViewModel
class DownloadActions @Inject constructor(private val downloads: Downloads) : ViewModel() {
    val states = downloads.states

    private val _sheet = MutableStateFlow<DownloadSheet?>(null)
    val sheet: StateFlow<DownloadSheet?> = _sheet.asStateFlow()

    /** Null where the service has no downloads (Floatplane, Sauce+ for now). */
    fun menu(source: String, videoId: String, title: String): DownloadMenu? {
        if (!downloads.offers(source)) return null
        val state = states.value[Downloads.key(source, videoId)]
        return DownloadMenu(state) {
            when (state) {
                null, DownloadState.FAILED -> open(source, videoId, title)
                else -> viewModelScope.launch { downloads.remove(source, videoId) }
            }
        }
    }

    fun open(source: String, videoId: String, title: String) {
        _sheet.value = DownloadSheet(source, videoId, title)
        viewModelScope.launch {
            val next = try {
                DownloadSheet(source, videoId, title, offer = downloads.offer(source, videoId), free = downloads.freeBytes())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DownloadSheet(source, videoId, title, error = e.message ?: "Could not get the qualities")
            }
            if (_sheet.value?.videoId == videoId) _sheet.value = next
        }
    }

    /** [stream] null: the audio alone. */
    fun pick(stream: DownloadStream?) {
        val s = _sheet.value ?: return
        val offer = s.offer ?: return
        _sheet.value = s.copy(starting = true)
        viewModelScope.launch {
            try {
                downloads.start(s.source, offer, stream)
                _sheet.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotEnoughSpaceException) {
                _sheet.value = s.copy(error = "Not enough space: this needs ${bytes(e.needed)} and ${bytes(e.free)} is free.")
            } catch (e: Exception) {
                _sheet.value = s.copy(error = e.message ?: "Could not start the download")
            }
        }
    }

    fun dismiss() { _sheet.value = null }
}

/** The quality picker for a download, best first. Shown while [DownloadActions.sheet] is set. */
@Composable
fun DownloadSheetHost(actions: DownloadActions) {
    val sheet by actions.sheet.collectAsStateWithLifecycle()
    val s = sheet ?: return
    // Phones ask once for notifications, so the progress and "Downloaded" lines show. The
    // download starts either way.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val context = LocalContext.current
    val needsAsk = !LocalIsTv.current && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = actions::dismiss) {
        Column(
            Modifier.width(400.dp).background(Palette.Surface, RoundedCornerShape(20.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Download", style = MaterialTheme.typography.titleMedium)
            Text(s.title, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val offer = s.offer
            when {
                s.error != null -> Text(s.error, color = Palette.Live, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                offer == null || s.starting -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = Palette.Accent, strokeWidth = 2.dp)
                }
                else -> {
                    Text("${bytes(s.free)} free on this device", style = MaterialTheme.typography.labelMedium, color = Palette.TextFaint, modifier = Modifier.padding(bottom = 6.dp))
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val choose = { v: DownloadStream? ->
                            if (needsAsk) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                            actions.pick(v)
                        }
                        offer.videos.forEachIndexed { i, v ->
                            ListRow(modifier = if (i == 0) Modifier.focusRequester(first) else Modifier, onClick = { choose(v) }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(v.video?.label.orEmpty(), modifier = Modifier.weight(1f), color = if (i == 0) Palette.Accent else Palette.Text)
                                    Text(size(v, offer.audio), style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
                                }
                            }
                        }
                        // Plays like a podcast: in the background, with the screen off.
                        ListRow(onClick = { choose(null) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Headphones, null, tint = Palette.TextDim, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Audio only", modifier = Modifier.weight(1f))
                                Text(offer.audioOnlySize?.let { (if (offer.audio?.sizeExact == true) "" else "~") + bytes(it) }.orEmpty(), style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(s.offer != null) { if (s.offer != null) runCatching { first.requestFocus() } }
}

/** "412 MB", or "~412 MB" when part of it is estimated from the bitrate. */
private fun size(video: DownloadStream, audio: DownloadStream?): String {
    val total = (video.sizeBytes ?: return "") + (audio?.sizeBytes ?: 0)
    return (if (video.sizeExact && audio?.sizeExact != false) "" else "~") + bytes(total)
}

/** 1.2 GB, 412 MB, 900 KB. */
fun bytes(n: Long): String = when {
    n >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> "${n shr 20} MB"
    else -> "${(n shr 10).coerceAtLeast(1)} KB"
}
