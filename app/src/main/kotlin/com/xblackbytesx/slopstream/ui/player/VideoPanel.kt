package com.xblackbytesx.slopstream.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xblackbytesx.slopstream.ui.comments.CommentsList
import com.xblackbytesx.slopstream.ui.comments.CommentsLoader
import com.xblackbytesx.slopstream.ui.comments.CommentsState
import com.xblackbytesx.slopstream.ui.components.focusLift
import com.xblackbytesx.slopstream.ui.components.formatPosition
import com.xblackbytesx.slopstream.ui.theme.Palette

/** The tabs of the player's side panel. */
enum class PanelTab(val label: String) { DESCRIPTION("Description"), COMMENTS("Comments") }

/** What the Description tab shows above the creator's text. */
data class PanelHeader(val title: String, val meta: String)

/**
 * Beside the video (TV: a side sheet; phone: the lower part), which keeps playing: the creator's
 * description and, when comments are on, the comments, one tab each. Back or Close shuts it.
 */
@Composable
fun VideoPanel(
    tab: PanelTab,
    onTab: (PanelTab) -> Unit,
    header: PanelHeader,
    description: List<DescriptionLine>,
    comments: CommentsLoader?,
    isTv: Boolean,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = comments?.state?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(tab) { if (tab == PanelTab.COMMENTS) comments?.start() }
    VideoPanelContent(
        tab = tab,
        onTab = onTab,
        header = header,
        description = description,
        comments = state,
        onMoreComments = { comments?.more() },
        onReplies = { comments?.toggleReplies(it) },
        onMoreReplies = { comments?.moreReplies(it) },
        isTv = isTv,
        onSeek = onSeek,
        onClose = onClose,
        modifier = modifier,
    )
}

/** [VideoPanel] without the loader, for screenshots. [comments] null: no Comments tab. */
@Composable
fun VideoPanelContent(
    tab: PanelTab,
    onTab: (PanelTab) -> Unit,
    header: PanelHeader,
    description: List<DescriptionLine>,
    comments: CommentsState?,
    onMoreComments: () -> Unit,
    onReplies: (String) -> Unit,
    onMoreReplies: (String) -> Unit,
    isTv: Boolean,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = remember { FocusRequester() }
    val close = remember { FocusRequester() }
    val shown = if (comments == null) PanelTab.DESCRIPTION else tab
    // Into the tab's content: its first line, or the first comment once there is one.
    LaunchedEffect(shown) { runCatching { if (shown == PanelTab.DESCRIPTION) first.requestFocus() else close.requestFocus() } }
    val commentsReady = comments?.nodes?.isNotEmpty() == true
    LaunchedEffect(shown, commentsReady) { if (shown == PanelTab.COMMENTS && commentsReady) runCatching { first.requestFocus() } }

    Column(
        modifier
            .then(if (isTv) Modifier.fillMaxHeight().width(460.dp) else Modifier.fillMaxWidth().fillMaxHeight(0.6f))
            .background(Palette.Surface.copy(alpha = 0.96f), RoundedCornerShape(if (isTv) 0.dp else 16.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (comments == null) {
                Text(PanelTab.DESCRIPTION.label, style = MaterialTheme.typography.titleMedium)
            } else {
                PanelTab.entries.forEach { t -> TabChip(t.label, selected = t == shown) { onTab(t) } }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.focusRequester(close)) { Icon(Icons.Filled.Close, "Close") }
        }
        when (shown) {
            PanelTab.DESCRIPTION -> DescriptionList(header, description, isTv, onSeek, first)
            PanelTab.COMMENTS -> CommentsList(requireNotNull(comments), onMoreComments, onReplies, onMoreReplies, firstFocus = first)
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        color = if (selected) Palette.OnAccent else Palette.TextDim,
        modifier = Modifier
            .focusLift(interaction, shape)
            .background(if (selected) Palette.Accent else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/**
 * The title, who and when, then the creator's text a line at a time. Every line takes focus on
 * TV, so the remote scrolls through a long description; a chapter line jumps the video there.
 */
@Composable
private fun DescriptionList(header: PanelHeader, lines: List<DescriptionLine>, isTv: Boolean, onSeek: (Long) -> Unit, first: FocusRequester) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        item(key = "header") {
            Column(
                Modifier.fillMaxWidth().focusRequester(first).focusable().padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(header.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (header.meta.isNotBlank()) Text(header.meta, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
        }
        if (lines.isEmpty()) {
            item(key = "empty") { Text("The creator wrote no description.", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium) }
        }
        itemsIndexed(lines) { _, line ->
            when (line) {
                DescriptionLine.Gap -> Spacer(Modifier.height(12.dp))
                is DescriptionLine.Chapter -> ChapterRow(line, onSeek)
                is DescriptionLine.Text -> Text(
                    if (isTv) AnnotatedString(line.text) else linked(line),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Text,
                    modifier = Modifier.fillMaxWidth().then(if (isTv) Modifier.focusable() else Modifier).padding(vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun ChapterRow(line: DescriptionLine.Chapter, onSeek: (Long) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .focusLift(interaction, shape, scale = 1.02f)
            .background(Palette.SurfaceHigh.copy(alpha = 0.6f), shape)
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple()) { onSeek(line.atMs) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(formatPosition(line.atMs), style = MaterialTheme.typography.labelLarge, color = Palette.Accent)
        Text(line.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(4.dp))
}

/** Web addresses tappable on a phone (they open in the browser); a TV has none to open them in. */
private fun linked(line: DescriptionLine.Text): AnnotatedString = buildAnnotatedString {
    append(line.text)
    val style = TextLinkStyles(SpanStyle(color = Palette.Accent, textDecoration = TextDecoration.Underline))
    line.links.forEach { r -> addLink(LinkAnnotation.Url(line.text.substring(r), style), r.first, r.last + 1) }
}
