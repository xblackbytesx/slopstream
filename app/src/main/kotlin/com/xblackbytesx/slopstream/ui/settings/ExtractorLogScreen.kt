package com.xblackbytesx.slopstream.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xblackbytesx.slopstream.data.ExtractorLog
import com.xblackbytesx.slopstream.data.ExtractorStats
import com.xblackbytesx.slopstream.ui.components.ListRow
import com.xblackbytesx.slopstream.ui.components.ScreenHeader
import com.xblackbytesx.slopstream.ui.components.SecondaryButton
import com.xblackbytesx.slopstream.ui.theme.LocalDims
import com.xblackbytesx.slopstream.ui.theme.Palette
import com.xblackbytesx.slopstream.youtube.innertube.ResolveReport
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class ExtractorLogViewModel @Inject constructor(private val log: ExtractorLog) : ViewModel() {
    val reports = log.reports
    fun clear() = log.clear()
}

/**
 * Every lookup with the experimental extractor on (decision 30), for a play or a prefetch: how ours found it, how long it
 * took, or why it failed and how NewPipe did instead. Selecting a play shows ours's steps.
 */
@Composable
fun ExtractorLogScreen(vm: ExtractorLogViewModel = hiltViewModel()) {
    val reports by vm.reports.collectAsStateWithLifecycle()
    ExtractorLogContent(reports, vm::clear)
}

@Composable
fun ExtractorLogContent(reports: List<ResolveReport>, onClear: () -> Unit) {
    val stats = remember(reports) { ExtractorStats.of(reports) }
    val dims = LocalDims.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = dims.gutter, end = dims.gutter, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { ScreenHeader("Extractor log", modifier = Modifier.padding(start = 0.dp)) }
        item { Summary(stats) }
        if (reports.isNotEmpty()) {
            item { SecondaryButton("Clear the log", onClear, icon = Icons.Filled.DeleteSweep) }
        }
        items(reports, key = { "${it.at}-${it.videoId}-${it.kind}" }) { PlayRow(it) }
    }
}

@Composable
private fun Summary(s: ExtractorStats) {
    val text = if (s.plays == 0) {
        "Nothing yet. With the experimental extractor on, every YouTube video it looks up is listed here: when you play it, ahead of time when its tile is selected, and when you download it."
    } else buildString {
        append("${s.plays} lookups: ours ${s.ours}")
        if (s.rescued > 0) append(" (${s.rescued} rescued)")
        append(", NewPipe fallback ${s.fallbacks}")
        if (s.failed > 0) append(", failed ${s.failed}")
        append(".")
        s.oursMedianMs?.let { append(" Ours takes $it ms (median)") }
        s.fallbackMedianMs?.let { append("; a fallback $it ms in all") }
        append(".")
        if (s.paths.isNotEmpty()) append(" Paths: " + s.paths.entries.joinToString { "${it.key} ${it.value}" } + ".")
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
}

@Composable
private fun PlayRow(r: ResolveReport) {
    var open by remember { mutableStateOf(false) }
    ListRow(onClick = { open = !open }) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row {
                Text(
                    r.title.ifBlank { r.videoId },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(TIME.format(Instant.ofEpochMilli(r.at)), style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
            }
            val (line, color) = when {
                r.ownOk -> "Ours, ${r.ownPath}, ${r.ownMs} ms" to Palette.Accent
                r.played -> "NewPipe after ours failed: ${r.ownMs} + ${r.newpipeMs} ms" to Palette.TextDim
                else -> "Neither worked" to Palette.Live
            }
            val what = when (r.kind) {
                "switch" -> "Quality switch. "
                "download" -> "Download. "
                "download-renew" -> "Download, a fresh URL. "
                else -> ""
            }
            Text(what + line, style = MaterialTheme.typography.bodySmall, color = color)
            r.ownError?.let { Text("Ours: $it", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim) }
            r.newpipeError?.let { Text("NewPipe: $it", style = MaterialTheme.typography.bodySmall, color = Palette.Live) }
            if (open) {
                Text(r.videoId, style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
                r.ownSteps.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint) }
            }
        }
    }
}

private val TIME = DateTimeFormatter.ofPattern("d MMM HH:mm:ss").withZone(ZoneId.systemDefault())
