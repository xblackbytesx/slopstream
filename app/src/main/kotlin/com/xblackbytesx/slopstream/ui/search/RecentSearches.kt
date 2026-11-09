package com.xblackbytesx.slopstream.ui.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xblackbytesx.slopstream.data.PastSearch
import com.xblackbytesx.slopstream.data.SearchHistoryRules
import com.xblackbytesx.slopstream.ui.components.HeldKeyGuard
import com.xblackbytesx.slopstream.ui.components.MenuItem
import com.xblackbytesx.slopstream.ui.components.focusLift
import com.xblackbytesx.slopstream.ui.theme.LocalDims
import com.xblackbytesx.slopstream.ui.theme.LocalIsTv
import com.xblackbytesx.slopstream.ui.theme.Palette

/**
 * Earlier searches as one row of chips right under the search field (decision 32): one press
 * runs one again. On a remote it is a single stop between the field and the results (Down from
 * the field lands on the newest), and it never moves under the focus: a chip that was run
 * stays where it is, marked as the current search. Removing is behind a long press, so a
 * stray OK can only ever run a search.
 */
@Composable
fun RecentSearches(
    suggestions: List<PastSearch>,
    query: String,
    typing: Boolean,
    onRun: (PastSearch) -> Unit,
    onMenu: (PastSearch) -> Unit,
    /** On the first chip: where focus goes after one is removed, so it never drops to the rail. */
    firstFocus: FocusRequester? = null,
) {
    if (suggestions.isEmpty()) return
    val dims = LocalDims.current
    val isTv = LocalIsTv.current
    val keyboard = LocalSoftwareKeyboardController.current
    val current = SearchHistoryRules.key(query)
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            buildString {
                append(if (typing && suggestions.none { SearchHistoryRules.key(it.query) == current }) "Searched before" else "Recent searches")
                append(if (isTv) " · hold OK to remove" else " · hold to remove")
            },
            style = MaterialTheme.typography.labelMedium,
            color = Palette.TextFaint,
            modifier = Modifier.padding(horizontal = dims.gutter),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = dims.gutter, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(suggestions, key = { "${it.source}:${SearchHistoryRules.key(it.query)}" }) { past ->
                PastSearchChip(
                    past,
                    modifier = if (firstFocus != null && past === suggestions.first()) Modifier.focusRequester(firstFocus) else Modifier,
                    current = SearchHistoryRules.key(past.query) == current,
                    onClick = { keyboard?.hide(); onRun(past) },
                    onMenu = { onMenu(past) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PastSearchChip(past: PastSearch, modifier: Modifier, current: Boolean, onClick: () -> Unit, onMenu: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .focusLift(interaction, shape)
            .background(if (current) Palette.SurfaceHigh else Palette.Surface, shape)
            .border(1.dp, if (current) Palette.Accent else Color.Transparent, shape)
            .combinedClickable(interactionSource = interaction, indication = ripple(), onClick = onClick, onLongClick = onMenu)
            .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyUp && e.key == Key.Menu) { onMenu(); true } else false }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (past.global) Icons.Filled.Public else Icons.Filled.History,
            if (past.global) "Also searched beyond your library" else null,
            tint = if (current) Palette.Accent else Palette.TextDim,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            past.query,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 260.dp),
        )
    }
}

/** What a long press on an earlier search offers. The first, focused item only runs it. */
@Composable
fun PastSearchMenu(
    past: PastSearch,
    service: String,
    onRun: () -> Unit,
    onRemove: () -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
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
            Text("“${past.query}”", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (past.global) Text("Also searched $service itself", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            Spacer(Modifier.size(4.dp))
            MenuItem(Icons.Filled.Search, "Search again", Modifier.focusRequester(first)) { onDismiss(); onRun() }
            MenuItem(Icons.Filled.RemoveCircleOutline, "Remove from recent searches") { onDismiss(); onRemove() }
            MenuItem(Icons.Filled.DeleteSweep, "Clear all recent $service searches") { onDismiss(); onClearAll() }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
