package com.xblackbytesx.slopstream.ui.feed

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.data.ArchiveState
import com.xblackbytesx.slopstream.data.RefreshState
import com.xblackbytesx.slopstream.data.db.VideoRow
import com.xblackbytesx.slopstream.ui.comments.CommentsGate
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
import com.xblackbytesx.slopstream.ui.components.tileKey
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
    val history: () -> Unit = {},
    val comments: (VideoRow) -> Unit = {},
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
            } else if (subs == 0 && service.capabilities.catalog) {
                EmptyState(
                    "No ${service.current.label} creators yet",
                    "Your membership covers all of them. Pick the ones you want in this feed, or add them all.",
                ) {
                    PrimaryButton("Add creators", nav.addChannel, icon = Icons.Filled.Add)
                }
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
        subHeader = {
            FlowRow(Modifier.padding(horizontal = LocalDims.current.gutter).padding(bottom = 8.dp)) {
                SecondaryButton("History", nav.history, icon = Icons.Filled.History)
            }
        },
        empty = { EmptyState("Nothing saved", "Long-press a video, or press the menu button on the remote, to keep it for later.") },
    )
}

@Composable
fun HistoryScreen(nav: ListNav, vm: HistoryViewModel = hiltViewModel()) {
    val items = vm.items.collectAsLazyPagingItems()
    VideoListScreen(
        title = "History",
        items = items,
        refresh = null,
        vm = vm,
        nav = nav,
        serviceBar = true,
        empty = { EmptyState("Nothing watched yet", "Videos you watch here, or on your other devices with a sync server, show up here.") },
    )
}

@Composable
fun ChannelScreen(nav: ListNav, onUnsubscribed: () -> Unit, vm: ChannelViewModel = hiltViewModel()) {
    val items = vm.items.collectAsLazyPagingItems()
    val tab by vm.selectedTab.collectAsStateWithLifecycle()
    val favorite by vm.favorite.collectAsStateWithLifecycle()
    val name by vm.name.collectAsStateWithLifecycle()
    val archive by vm.archiveState.collectAsStateWithLifecycle()
    VideoListScreen(
        title = name,
        items = items,
        refresh = null,
        vm = vm,
        nav = nav.copy(openChannel = {}),
        portrait = tab == ChannelTab.SHORTS,
        showChannel = false,
        listKey = tab,
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
        footer = archive?.let { a -> { LoadOlder(a, vm::loadOlder) } },
        empty = {
            EmptyState(
                "Nothing here",
                if (archive != null) "None of this channel's ${tab.label.lowercase()} are cached yet. Load older ones below."
                else "This channel has no ${tab.label.lowercase()} in the cache yet.",
            )
        },
    )
}

/**
 * The end of a channel tab: loads the next page of older uploads (decision 23). One button in
 * every state, so focus on TV stays put while it loads and after the last page.
 */
@Composable
private fun LoadOlder(state: ArchiveState, onLoad: () -> Unit) {
    // New tiles land above the button and push it down; on TV, where it holds focus, it comes
    // back into view so the remote can go on (Up reaches the new tiles).
    val view = remember { BringIntoViewRequester() }
    val isTv = LocalIsTv.current
    LaunchedEffect(state.loading) { if (isTv && !state.loading) runCatching { view.bringIntoView() } }
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).bringIntoViewRequester(view),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim) }
        SecondaryButton(
            when {
                state.loading -> "Loading older videos…"
                state.done -> "That's everything on this channel"
                state.error != null -> "Try again"
                else -> "Load older videos"
            },
            { if (!state.loading && !state.done) onLoad() },
            icon = if (state.done) null else Icons.Filled.History,
        )
    }
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
    /** What the list shows (a tab): each gets its own scroll position. */
    listKey: Any = Unit,
    subHeader: (@Composable () -> Unit)? = null,
    /** Under the last tile; also shown when the list is empty. */
    footer: (@Composable () -> Unit)? = null,
    empty: @Composable () -> Unit,
) {
    val service by vm.service.collectAsStateWithLifecycle()
    val commentsGate: CommentsGate = hiltViewModel()
    var menuFor by remember { mutableStateOf<VideoRow?>(null) }
    val isTv = LocalIsTv.current
    // Held here, not inside the grid: the grid leaves composition for a frame while the list
    // reloads after the player closes, and its own state would start over at the top.
    val gridState = key(service.current, listKey) { rememberLazyGridState() }
    // The tile last played or opened, focused again when this screen comes back (TV).
    var lastActivated by rememberSaveable { mutableStateOf<String?>(null) }
    val actions = remember(vm, nav) {
        object : VideoActions {
            override fun play(row: VideoRow) {
                lastActivated = row.tileKey()
                nav.play(row)
            }
            override fun menu(row: VideoRow) {
                lastActivated = row.tileKey()
                menuFor = row
            }
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
            if (loadedEmpty) {
                if (footer == null) {
                    empty()
                } else {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.weight(1f)) { empty() }
                        Box(Modifier.padding(bottom = 32.dp)) { footer() }
                    }
                }
            } else {
                VideoGrid(
                    items,
                    actions,
                    portrait = portrait,
                    showChannel = showChannel,
                    state = gridState,
                    restoreFocusKey = lastActivated.takeIf { isTv },
                    footer = footer,
                )
            }
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
                comments = if (commentsGate.offers(row.source)) ({ nav.comments(row) }) else null,
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
