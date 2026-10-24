package com.xblackbytesx.slopstream.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import com.xblackbytesx.slopstream.data.db.VideoRow
import com.xblackbytesx.slopstream.ui.theme.LocalDims

/** What a tile can do; screens implement it, tests and previews fake it. */
interface VideoActions {
    fun play(row: VideoRow)
    fun menu(row: VideoRow)
    fun prefetch(row: VideoRow)
}

/** The grid key of a row, also what [VideoGrid]'s `restoreFocusKey` names. */
fun VideoRow.tileKey(): String = "$source:$remoteId"

/**
 * A grid of tiles over a paged query. Keys are stable (source:id), so a refresh that inserts new
 * uploads at the top keeps the focused tile where it was.
 *
 * [restoreFocusKey]: the tile to scroll to and focus when the grid (re)enters composition, the
 * one last played or opened. On a TV this brings the remote back where it was after the player
 * closes; without it focus falls to the first focusable thing, the navigation rail.
 */
@Composable
fun VideoGrid(
    items: LazyPagingItems<VideoRow>,
    actions: VideoActions,
    modifier: Modifier = Modifier,
    portrait: Boolean = false,
    showChannel: Boolean = true,
    state: LazyGridState = rememberLazyGridState(),
    restoreFocusKey: String? = null,
    header: (@Composable () -> Unit)? = null,
) {
    val dims = LocalDims.current
    val restore = remember { FocusRequester() }
    // Once per entry into composition: after that, focus is the user's.
    var pending by remember { mutableStateOf(restoreFocusKey != null) }
    val offset = if (header != null) 1 else 0

    LaunchedEffect(restoreFocusKey, items.itemCount) {
        val key = restoreFocusKey ?: return@LaunchedEffect
        if (!pending) return@LaunchedEffect
        // Waits for the rows to arrive; the effect reruns as the count changes.
        val index = (0 until items.itemCount).firstOrNull { items.peek(it)?.tileKey() == key } ?: return@LaunchedEffect
        val gridIndex = index + offset
        if (state.layoutInfo.visibleItemsInfo.none { it.index == gridIndex }) state.scrollToItem(gridIndex)
        // The tile is composed on the next frame if it was just scrolled to.
        withFrameNanos { }
        pending = !runCatching { restore.requestFocus() }.isSuccess
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (portrait) dims.shortTileMinWidth else dims.tileMinWidth),
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = dims.gutter, end = dims.gutter, top = 8.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(dims.gridSpacing),
        verticalArrangement = Arrangement.spacedBy(dims.gridSpacing + 4.dp),
    ) {
        if (header != null) item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
        items(count = items.itemCount, key = items.itemKey { it.tileKey() }) { i ->
            val row = items[i]
            if (row == null) {
                Box(Modifier)
            } else {
                VideoTile(
                    row = row,
                    onClick = { actions.play(row) },
                    onMenu = { actions.menu(row) },
                    modifier = if (row.tileKey() == restoreFocusKey) Modifier.focusRequester(restore) else Modifier,
                    portrait = portrait,
                    showChannel = showChannel,
                    onFocusSettled = { actions.prefetch(row) },
                )
            }
        }
    }
}
