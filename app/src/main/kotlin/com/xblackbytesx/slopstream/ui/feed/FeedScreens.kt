package com.xblackbytesx.slopstream.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.data.RefreshState
import com.xblackbytesx.slopstream.data.db.VideoRow
import com.xblackbytesx.slopstream.ui.components.EmptyState
import com.xblackbytesx.slopstream.ui.components.PrimaryButton
import com.xblackbytesx.slopstream.ui.components.ScreenHeader
import com.xblackbytesx.slopstream.ui.components.ServiceBar
import com.xblackbytesx.slopstream.ui.components.SecondaryButton
import com.xblackbytesx.slopstream.ui.components.VideoActions
import com.xblackbytesx.slopstream.ui.components.VideoGrid
import com.xblackbytesx.slopstream.ui.components.VideoMenuActions
import com.xblackbytesx.slopstream.ui.components.VideoMenuDialog
import com.xblackbytesx.slopstream.ui.components.relativeTime
import com.xblackbytesx.slopstream.ui.theme.LocalDims
import com.xblackbytesx.slopstream.ui.theme.LocalIsTv
import com.xblackbytesx.slopstream.ui.theme.Palette

/** Navigation out of a list screen. */
data class ListNav(
    val play: (VideoRow) -> Unit,
    val openChannel: (VideoRow) -> Unit,
    val addChannel: () -> Unit,
    val import: () -> Unit,
    /** Phones reach Search from the list headers; TVs from the rail. */
    val search: () -> Unit = {},
    val signIn: (SourceKey) -> Unit = {},
)

@Composable
fun VideosScreen(nav: ListNav, vm: VideosViewModel = hiltViewModel()) {
    val items = vm.items.collectAsLazyPagingItems()
    val refresh by vm.refresh.collectAsStateWithLifecycle()
    val subs by vm.subscriptionCount.collectAsStateWithLifecycle()
    val service by vm.service.collectAsStateWithLifecycle()
    VideoListScreen(
        title = "Videos",
        items = items,
        refresh = refresh,
        vm = vm,
        nav = nav,
        serviceBar = true,
        empty = {
            if (subs == 0 && service.capabilities.accountSubscriptions) {
                EmptyState(
                    "No ${service.current.label} subscriptions",
                    "They come from your ${service.current.label} account. Subscribe on their site, then refresh here.",
                )
            } else if (subs == 0) {
                EmptyState(
                    "No subscriptions yet",
                    "Import them from NewPipe or Google Takeout, or search for a channel. Only channels you follow appear here, newest first.",
                ) {
                    PrimaryButton("Import", nav.import, icon = Icons.Filled.Download)
                    SecondaryButton("Add channel", nav.addChannel, icon = Icons.Filled.Add)
                }
            } else {
                EmptyState("Nothing here yet", if (refresh.running) "Fetching your channels…" else "Refresh to fetch the latest uploads.")
            }
        },
    )
}

@Composable
fun ShortsScreen(nav: ListNav, vm: ShortsViewModel = hiltViewModel()) {
    val items = vm.items.collectAsLazyPagingItems()
    val refresh by vm.refresh.collectAsStateWithLifecycle()
    val service by vm.service.collectAsStateWithLifecycle()
    VideoListScreen(
        title = "Shorts",
        items = items,
        refresh = refresh,
        vm = vm,
        nav = nav,
        portrait = true,
        serviceBar = true,
        empty = {
            if (service.capabilities.hasShorts) {
                EmptyState("No Shorts", "Shorts from the channels you follow show up here, and only here.")
            } else {
                EmptyState("No Shorts on ${service.current.label}", "Its videos are all under Videos.")
            }
        },
    )
}

@Composable
fun WatchLaterScreen(nav: ListNav, vm: WatchLaterViewModel = hiltViewModel()) {
    val items = vm.items.collectAsLazyPagingItems()
    VideoListScreen(
        title = "Watch later",
        items = items,
        refresh = null,
        vm = vm,
        nav = nav,
        serviceBar = true,
        empty = { EmptyState("Nothing saved", "Long-press a video, or press the menu button on the remote, to keep it for later.") },
    )
}

@Composable
fun ChannelScreen(nav: ListNav, onUnsubscribed: () -> Unit, vm: ChannelViewModel = hiltViewModel()) {
    val items = vm.items.collectAsLazyPagingItems()
    val tab by vm.selectedTab.collectAsStateWithLifecycle()
    val favorite by vm.favorite.collectAsStateWithLifecycle()
    val name by vm.name.collectAsStateWithLifecycle()
    VideoListScreen(
        title = name,
        items = items,
        refresh = null,
        vm = vm,
        nav = nav.copy(openChannel = {}),
        portrait = tab == ChannelTab.SHORTS,
        showChannel = false,
        subHeader = {
            // Wraps on a phone: three tabs and two buttons do not fit one line there.
            FlowRow(
                Modifier.padding(horizontal = LocalDims.current.gutter).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                vm.tabs.forEach { t ->
                    FilterChip(
                        selected = t == tab,
                        onClick = { vm.select(t) },
                        label = { Text(t.label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.Accent, selectedLabelColor = Palette.OnAccent),
                    )
                }
                SecondaryButton(
                    if (favorite) "Favorite" else "Add to favorites",
                    { vm.setFavorite(!favorite) },
                    icon = if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                )
                if (vm.canUnsubscribe) SecondaryButton("Unsubscribe", { vm.unsubscribe(onUnsubscribed) })
            }
        },
        empty = { EmptyState("Nothing here", "This channel has no ${tab.label.lowercase()} in the cache yet.") },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoListScreen(
    title: String,
    items: LazyPagingItems<VideoRow>,
    refresh: RefreshState?,
    vm: VideoListViewModel,
    nav: ListNav,
    portrait: Boolean = false,
    showChannel: Boolean = true,
    /** Top-level screens pick their service; a channel page belongs to one already. */
    serviceBar: Boolean = false,
    subHeader: (@Composable () -> Unit)? = null,
    empty: @Composable () -> Unit,
) {
    val service by vm.service.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<VideoRow?>(null) }
    val isTv = LocalIsTv.current
    val actions = remember(vm, nav) {
        object : VideoActions {
            override fun play(row: VideoRow) = nav.play(row)
            override fun menu(row: VideoRow) { menuFor = row }
            override fun prefetch(row: VideoRow) = vm.prefetch(row)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = title,
            status = refresh?.let(::refreshStatus),
            actions = {
                if (!isTv) IconButton(onClick = nav.search) { Icon(Icons.Filled.Search, "Search") }
                if (refresh != null && isTv) {
                    IconButton(onClick = vm::refreshNow, enabled = !refresh.running) { Icon(Icons.Filled.Refresh, "Refresh") }
                }
            },
        )
        if (serviceBar) ServiceBar(service, vm::selectService, nav.signIn)
        subHeader?.invoke()
        val loadedEmpty = items.itemCount == 0 && items.loadState.refresh is LoadState.NotLoading
        val body: @Composable () -> Unit = {
            if (loadedEmpty) empty() else VideoGrid(items, actions, portrait = portrait, showChannel = showChannel)
        }
        if (refresh != null && !isTv) {
            PullToRefreshBox(isRefreshing = refresh.running, onRefresh = vm::refreshNow, modifier = Modifier.fillMaxSize()) { body() }
        } else {
            body()
        }
    }

    menuFor?.let { row ->
        VideoMenuDialog(
            row = row,
            actions = VideoMenuActions(
                play = { nav.play(row) },
                toggleWatchLater = { vm.toggleWatchLater(row) },
                toggleWatched = { vm.toggleWatched(row) },
                openChannel = if (showChannel) ({ nav.openChannel(row) }) else null,
            ),
            onDismiss = { menuFor = null },
        )
    }
}

internal fun refreshStatus(s: RefreshState): String? = when {
    s.running -> "Refreshing ${s.done} of ${s.total} channels…"
    s.blocked != null -> "Stopped: ${s.blocked}"
    s.finishedAt != null && s.failedChannels > 0 -> "Updated ${relativeTime(s.finishedAt).lowercase()} · ${s.failedChannels} channels failed"
    s.finishedAt != null -> "Updated ${relativeTime(s.finishedAt).lowercase()}"
    else -> null
}
