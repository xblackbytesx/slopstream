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
import androidx.compose.ui.Modifier
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

/**
 * A grid of tiles over a paged query. Keys are stable (source:id), so a refresh that inserts new
 * uploads at the top keeps the focused tile where it was.
 */
@Composable
fun VideoGrid(
    items: LazyPagingItems<VideoRow>,
    actions: VideoActions,
    modifier: Modifier = Modifier,
    portrait: Boolean = false,
    showChannel: Boolean = true,
    state: LazyGridState = rememberLazyGridState(),
    header: (@Composable () -> Unit)? = null,
) {
    val dims = LocalDims.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (portrait) dims.shortTileMinWidth else dims.tileMinWidth),
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = dims.gutter, end = dims.gutter, top = 8.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(dims.gridSpacing),
        verticalArrangement = Arrangement.spacedBy(dims.gridSpacing + 4.dp),
    ) {
        if (header != null) item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
        items(count = items.itemCount, key = items.itemKey { "${it.source}:${it.remoteId}" }) { i ->
            val row = items[i]
            if (row == null) {
                Box(Modifier)
            } else {
                VideoTile(
                    row = row,
                    onClick = { actions.play(row) },
                    onMenu = { actions.menu(row) },
                    portrait = portrait,
                    showChannel = showChannel,
                    onFocusSettled = { actions.prefetch(row) },
                )
            }
        }
    }
}
