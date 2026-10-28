package com.xblackbytesx.slopstream.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.xblackbytesx.slopstream.core.VideoKind
import com.xblackbytesx.slopstream.data.db.DownloadState
import com.xblackbytesx.slopstream.data.db.VideoRow
import com.xblackbytesx.slopstream.data.download.Downloads
import com.xblackbytesx.slopstream.ui.downloads.LocalDownloadStates
import com.xblackbytesx.slopstream.ui.theme.LocalDims
import com.xblackbytesx.slopstream.ui.theme.Palette
import com.xblackbytesx.slopstream.youtube.YoutubeIds
import kotlinx.coroutines.delay

/**
 * One video in a grid. Shows what you need to choose without opening it: thumbnail, title,
 * channel, age, length, and how far you got. Watched videos are dimmed, not hidden.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoTile(
    row: VideoRow,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    portrait: Boolean = false,
    showChannel: Boolean = true,
    onFocusSettled: () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(LocalDims.current.corner)
    LaunchedEffect(focused) {
        if (focused) {
            delay(400)
            onFocusSettled()
        }
    }

    Column(
        modifier
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onMenu)
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyUp && e.key == Key.Menu) { onMenu(); true } else false
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (portrait) 9f / 16f else 16f / 9f)
                .focusLift(interaction, shape)
                .clip(shape)
                .background(Palette.SurfaceHigh),
        ) {
            Thumbnail(row)
            val watched = row.watched == true
            if (watched) Box(Modifier.fillMaxSize().background(Color(0x99000000)))
            Badges(row)
            val progress = row.progress()
            if (!watched && progress > 0f) ProgressLine(progress, Modifier.align(Alignment.BottomStart))
            if (watched) {
                Icon(
                    Icons.Filled.CheckCircle, contentDescription = "Watched", tint = Palette.Accent,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(20.dp),
                )
            }
            val download = LocalDownloadStates.current[Downloads.key(row.source, row.remoteId)]
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (download) {
                    DownloadState.DONE -> CornerIcon(Icons.Filled.DownloadDone, "Downloaded", Palette.Accent)
                    DownloadState.QUEUED, DownloadState.RUNNING -> CornerIcon(Icons.Filled.Download, "Downloading", Color.White)
                    else -> Unit
                }
                if (row.inWatchLater) CornerIcon(Icons.Filled.WatchLater, "In Watch later", Color.White)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            row.title,
            style = MaterialTheme.typography.titleSmall,
            color = if (row.watched == true) Palette.TextDim else Palette.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            listOfNotNull(
                row.channelName.takeIf { showChannel },
                relativeTime(row.publishedAt, approximate = row.dateApproximate).takeUnless { row.dateUnknown },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Thumbnail(row: VideoRow) {
    // hq720/oar2 are missing on some uploads; hqdefault always exists (4:3, cropped to fit).
    val fallback = YoutubeIds.thumbnail(row.remoteId, YoutubeIds.ThumbSize.HIGH)
    var url by remember(row.remoteId) { mutableStateOf(row.thumbnailUrl ?: fallback) }
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
        onError = { if (url != fallback) url = fallback },
    )
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Badges(row: VideoRow) {
    val kind = runCatching { VideoKind.valueOf(row.kind) }.getOrDefault(VideoKind.VIDEO)
    val label: String?
    val color: Color
    when (kind) {
        VideoKind.LIVE -> { label = if (row.durationSec == null) "LIVE" else formatDuration(row.durationSec); color = if (row.durationSec == null) Palette.Live else Palette.Scrim }
        VideoKind.UPCOMING -> { label = "UPCOMING"; color = Palette.Scrim }
        else -> { label = row.durationSec?.let(::formatDuration); color = Palette.Scrim }
    }
    if (label != null) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .background(color, RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun CornerIcon(icon: ImageVector, label: String, tint: Color) {
    Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp).background(Palette.Scrim, RoundedCornerShape(50)).padding(2.dp))
}

@Composable
private fun ProgressLine(fraction: Float, modifier: Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .drawWithContent {
                drawRect(Color(0x66FFFFFF))
                drawRect(Palette.Accent, topLeft = Offset.Zero, size = Size(size.width * fraction, size.height))
            },
    )
}

private fun VideoRow.progress(): Float {
    val pos = positionMs ?: return 0f
    val dur = watchDurationMs?.takeIf { it > 0 } ?: durationSec?.times(1000) ?: return 0f
    return (pos.toFloat() / dur).coerceIn(0f, 1f)
}
