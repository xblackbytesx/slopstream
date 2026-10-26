package com.xblackbytesx.slopstream.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xblackbytesx.slopstream.core.Channel
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.data.db.SubscriptionEntity
import com.xblackbytesx.slopstream.data.db.VideoRow
import com.xblackbytesx.slopstream.ui.comments.CommentsGate
import com.xblackbytesx.slopstream.ui.components.ChannelAvatar
import com.xblackbytesx.slopstream.ui.components.EmptyState
import com.xblackbytesx.slopstream.ui.components.PrimaryButton
import com.xblackbytesx.slopstream.ui.components.ScreenHeader
import com.xblackbytesx.slopstream.ui.components.ServiceBar
import com.xblackbytesx.slopstream.ui.components.VideoMenuActions
import com.xblackbytesx.slopstream.ui.components.VideoMenuDialog
import com.xblackbytesx.slopstream.ui.components.VideoTile
import com.xblackbytesx.slopstream.ui.components.focusLift
import com.xblackbytesx.slopstream.ui.subscriptions.ChannelResult
import com.xblackbytesx.slopstream.ui.theme.LocalDims
import com.xblackbytesx.slopstream.ui.theme.LocalIsTv
import com.xblackbytesx.slopstream.ui.theme.Palette

/** Where search results lead. YouTube results always open in the regular player. */
data class SearchNav(
    val play: (VideoRow) -> Unit,
    val playDirect: (VideoRow) -> Unit,
    val openChannel: (VideoRow) -> Unit,
    val openSubscription: (SubscriptionEntity) -> Unit,
    val signIn: (SourceKey) -> Unit = {},
    val comments: (VideoRow) -> Unit = {},
)

/** What a result tile or row does; [SearchResults] is stateless so tests can render it. */
interface SearchActions {
    fun play(row: VideoRow, global: Boolean)
    fun menu(row: VideoRow, global: Boolean)
    fun prefetch(row: VideoRow)
    fun openSubscription(s: SubscriptionEntity)
    fun toggleSubscribe(c: Channel)
}

@Composable
fun SearchScreen(nav: SearchNav, vm: SearchViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<Pair<VideoRow, Boolean>?>(null) }
    val commentsGate: CommentsGate = hiltViewModel()
    // The tile last played or opened, focused again when the screen comes back (TV).
    var lastActivated by rememberSaveable { mutableStateOf<String?>(null) }
    val isTv = LocalIsTv.current
    val actions = remember(vm, nav) {
        object : SearchActions {
            override fun play(row: VideoRow, global: Boolean) {
                lastActivated = tileKey(row, global)
                if (global) nav.playDirect(row) else nav.play(row)
            }
            override fun menu(row: VideoRow, global: Boolean) {
                lastActivated = tileKey(row, global)
                menuFor = row to global
            }
            override fun prefetch(row: VideoRow) = vm.prefetch(row)
            override fun openSubscription(s: SubscriptionEntity) = nav.openSubscription(s)
            override fun toggleSubscribe(c: Channel) { vm.toggleSubscribe(c) }
        }
    }

    val service by vm.service.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Search", status = "Your channels and the videos in your feeds" + if (state.global) ", plus all of ${state.service.label}" else "")
        ServiceBar(service, vm::selectService, nav.signIn)
        SearchBar(
            query = state.query,
            global = state.global,
            onQuery = vm::setQuery,
            onGlobal = vm::setGlobal,
            onSubmit = vm::submit,
            globalService = state.service.label.takeIf { state.canSearchGlobal },
        )
        SearchResults(state, actions, restoreFocusKey = lastActivated.takeIf { isTv })
    }

    menuFor?.let { (row, global) ->
        VideoMenuDialog(
            row = row,
            actions = VideoMenuActions(
                play = { actions.play(row, global) },
                toggleWatchLater = { vm.toggleWatchLater(row) },
                toggleWatched = { vm.toggleWatched(row) },
                // A channel page only knows followed channels' cached videos.
                openChannel = if (global) null else ({ nav.openChannel(row) }),
                comments = if (commentsGate.offers(row.source)) ({ nav.comments(row) }) else null,
            ),
            onDismiss = { menuFor = null },
        )
    }
}

@Composable
fun SearchBar(
    query: String,
    global: Boolean,
    onQuery: (String) -> Unit,
    onGlobal: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    /** The service the toggle searches; null hides it (a service without its own search). */
    globalService: String? = "YouTube",
) {
    val dims = LocalDims.current
    val isTv = LocalIsTv.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // A text field keeps Up and Down for its cursor; on a remote they must leave the field,
    // or the results can't be reached without the air cursor.
    fun leave(direction: FocusDirection): Boolean {
        keyboard?.hide()
        focus.moveFocus(direction)
        return true
    }
    val field: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            placeholder = { Text("Channel or video title") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Clear, "Clear") } }) else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                onSubmit()
                if (isTv) leave(FocusDirection.Down)
            }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Accent),
            modifier = modifier.widthIn(max = 640.dp).onPreviewKeyEvent { e ->
                if (!isTv || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> leave(FocusDirection.Down)
                    Key.DirectionUp -> leave(FocusDirection.Up)
                    else -> false
                }
            },
        )
    }
    val controls: @Composable () -> Unit = controls@{
        if (globalService == null) return@controls
        FilterChip(
            selected = global,
            onClick = { onGlobal(!global) },
            label = { Text("Also search $globalService") },
            leadingIcon = { if (global) Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = Palette.Accent,
                selectedLabelColor = Palette.OnAccent,
                selectedLeadingIconColor = Palette.OnAccent,
            ),
        )
        if (global) PrimaryButton("Search", onSubmit, enabled = query.isNotBlank())
    }
    val spacing = Arrangement.spacedBy(12.dp)
    val padding = Modifier.padding(horizontal = dims.gutter).padding(bottom = 12.dp)
    // One line on a TV; on a phone the field gets the full width and the toggle goes under it.
    if (LocalIsTv.current) {
        Row(padding, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = spacing) {
            field(Modifier.weight(1f))
            controls()
        }
    } else {
        Column(padding, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            field(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = spacing) { controls() }
        }
    }
}

@Composable
fun SearchResults(state: SearchState, actions: SearchActions, restoreFocusKey: String? = null) {
    val dims = LocalDims.current
    val restore = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (restoreFocusKey == null) return@LaunchedEffect
        // The grid keeps its scroll position itself; the tile is laid out a frame or more later.
        repeat(20) {
            withFrameNanos { }
            if (runCatching { restore.requestFocus(FocusDirection.Enter) }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    val channels = state.channels
    val global = state.globalResults
    if (channels == null && global == null) {
        EmptyState(
            "Search your library",
            "Type a channel name or words from a video title." +
                if (state.canSearchGlobal) " Turn on “Also search ${state.service.label}” to look beyond the channels you follow." else "",
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(dims.tileMinWidth),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = dims.gutter, end = dims.gutter, top = 4.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(dims.gridSpacing),
        verticalArrangement = Arrangement.spacedBy(dims.gridSpacing),
    ) {
        if (channels != null) {
            if (channels.isNotEmpty()) {
                section("Your channels", channels.size)
                // One scrolling row, so matching channels never push the videos off screen.
                item(key = "subs", span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(dims.gridSpacing)) {
                        items(channels.take(MAX_CHANNELS), key = { "${it.source}:${it.remoteId}" }) { s ->
                            SubscribedChannelCard(s) { actions.openSubscription(s) }
                        }
                    }
                }
            }
            section("In your library", state.library.size)
            if (state.library.isEmpty()) {
                note(if (channels.isEmpty()) "Nothing you follow matches." else "No cached videos match.")
            } else {
                tiles(state.library, global = false, actions, restore, restoreFocusKey)
            }
        }
        if (global != null) {
            section(state.service.label, null)
            when {
                global.loading -> item(key = "yt-loading", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = Palette.Accent) }
                }
                else -> {
                    global.error?.let { note("${state.service.label} search failed: $it") }
                    items(global.channels.take(MAX_GLOBAL_CHANNELS), key = { "ytc:${it.remoteId}" }, span = { GridItemSpan(maxLineSpan) }) { c ->
                        ChannelResult(c, c.remoteId in state.subscribed) { actions.toggleSubscribe(c) }
                    }
                    tiles(global.videos, global = true, actions, restore, restoreFocusKey)
                    if (global.error == null && global.channels.isEmpty() && global.videos.isEmpty()) note("Nothing found on ${state.service.label}.")
                }
            }
        } else if (state.global && state.query.isNotBlank()) {
            note("Press Search to look on ${state.service.label} too.")
        }
    }
}

private fun LazyGridScope.section(title: String, count: Int?) {
    item(key = "section:$title", span = { GridItemSpan(maxLineSpan) }) {
        Text(
            if (count == null) title else "$title · $count",
            style = MaterialTheme.typography.titleMedium,
            color = Palette.TextDim,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

private fun LazyGridScope.note(text: String) {
    item(key = "note:$text", span = { GridItemSpan(maxLineSpan) }) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
    }
}

/** A video can show in both sections, so the section is part of the key. */
private fun tileKey(row: VideoRow, global: Boolean) = (if (global) "global:" else "lib:") + "${row.source}:${row.remoteId}"

private fun LazyGridScope.tiles(rows: List<VideoRow>, global: Boolean, actions: SearchActions, restore: FocusRequester, restoreKey: String?) {
    items(rows, key = { tileKey(it, global) }) { row ->
        VideoTile(
            row = row,
            onClick = { actions.play(row, global) },
            onMenu = { actions.menu(row, global) },
            modifier = if (tileKey(row, global) == restoreKey) Modifier.focusRequester(restore) else Modifier,
            onFocusSettled = { actions.prefetch(row) },
        )
    }
}

@Composable
private fun SubscribedChannelCard(s: SubscriptionEntity, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(LocalDims.current.corner)
    Column(
        Modifier
            .width(128.dp)
            .focusLift(interaction, shape)
            .clip(shape)
            .background(Palette.Surface)
            .clickable(interactionSource = interaction, indication = ripple(), onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            ChannelAvatar(s.name, s.avatarUrl, 56.dp)
            if (s.favorite) {
                Icon(
                    Icons.Filled.Star, "Favorite", tint = Palette.Accent,
                    modifier = Modifier.align(Alignment.BottomEnd).size(18.dp).background(Palette.Surface, CircleShape).padding(2.dp),
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        Text(s.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

private const val MAX_CHANNELS = 24
private const val MAX_GLOBAL_CHANNELS = 6
