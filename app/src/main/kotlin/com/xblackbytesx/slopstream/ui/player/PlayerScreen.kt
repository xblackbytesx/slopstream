package com.xblackbytesx.slopstream.ui.player

import android.content.res.Configuration
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.xblackbytesx.slopstream.core.playback.PlaybackPlan
import com.xblackbytesx.slopstream.core.playback.chosenQuality
import com.xblackbytesx.slopstream.core.playback.qualityOptions
import com.xblackbytesx.slopstream.playback.EngineState
import com.xblackbytesx.slopstream.playback.FrameRateMatcher
import com.xblackbytesx.slopstream.playback.PlayerEngine
import com.xblackbytesx.slopstream.playback.PlayerSession
import com.xblackbytesx.slopstream.ui.comments.CommentsList
import com.xblackbytesx.slopstream.ui.comments.CommentsLoader
import com.xblackbytesx.slopstream.ui.components.ListRow
import com.xblackbytesx.slopstream.ui.components.MenuItem
import com.xblackbytesx.slopstream.ui.components.PrimaryButton
import com.xblackbytesx.slopstream.ui.components.SecondaryButton
import com.xblackbytesx.slopstream.ui.components.formatPosition
import com.xblackbytesx.slopstream.ui.downloads.DownloadActions
import com.xblackbytesx.slopstream.ui.downloads.DownloadMenu
import com.xblackbytesx.slopstream.ui.downloads.DownloadSheetHost
import com.xblackbytesx.slopstream.ui.theme.LocalIsTv
import com.xblackbytesx.slopstream.ui.theme.Palette
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    enterPip: () -> Unit,
    vm: PlayerViewModel = hiltViewModel(),
    downloads: DownloadActions = hiltViewModel(),
) {
    val state by vm.engine.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    // Read so the menu follows the download as it starts, finishes or goes.
    val downloadStates by downloads.states.collectAsStateWithLifecycle()
    val title = (state.plan?.title ?: vm.route.title).ifBlank { "Loading…" }
    val live = (state.plan as? PlaybackPlan.Manifest)?.live == true
    val download = remember(downloadStates, live, title) {
        if (live) null else downloads.menu(vm.route.source, vm.route.videoId, title)
    }
    PlayerChrome(
        engine = vm.engine,
        state = state,
        title = title,
        subtitle = vm.route.channelName,
        matchFrameRate = settings?.matchFrameRate == true,
        onBack = onBack,
        enterPip = enterPip,
        comments = vm.comments.takeIf { settings?.showComments == true },
        download = download,
    )
    DownloadSheetHost(downloads)
}

/**
 * The video surface plus everything drawn over it. Remote: OK plays/pauses, left/right seek
 * (faster the longer you hold), up/down reveal the controls, back hides them, then leaves.
 * Touch: tap toggles controls, double-tap the sides to seek; on phones in landscape, swipe up or
 * down on the left half for brightness and on the right half for volume (not in Shorts, whose
 * pager swipes vertically).
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerChrome(
    engine: PlayerEngine,
    state: EngineState,
    title: String,
    subtitle: String,
    matchFrameRate: Boolean,
    onBack: () -> Unit,
    enterPip: () -> Unit,
    portraitShorts: Boolean = false,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    /** Non-null: Comments under More, and the side panel it opens. */
    comments: CommentsLoader? = null,
    /** Non-null: Download (or cancel, remove) under More. */
    download: DownloadMenu? = null,
) {
    val isTv = LocalIsTv.current
    var showComments by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    val player = engine.player
    val inPip by PlayerSession.inPip.collectAsStateWithLifecycle()
    var visible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var rootFocused by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val swipe = rememberSwipeAdjust()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val swipeEnabled = !isTv && landscape && !portraitShorts && !inPip && !showComments

    fun poke() { lastInteraction = System.currentTimeMillis(); visible = true }
    fun togglePlay() { if (player.isPlaying) player.pause() else { if (state.ended) player.seekTo(0); player.play() }; poke() }
    fun seekBy(ms: Long) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        player.seekTo((player.currentPosition + ms).coerceIn(0, duration))
        poke()
    }

    ImmersiveMode()
    FrameRateEffect(enabled = isTv && matchFrameRate, fps = state.frameRate)
    PipSessionEffect(state)

    LaunchedEffect(visible, state.playing, lastInteraction) {
        if (visible && state.playing) {
            delay(4_000)
            visible = false
            runCatching { rootFocus.requestFocus() }
        }
    }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    BackHandler(enabled = visible && !rootFocused) {
        visible = false
        runCatching { rootFocus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onFocusChanged { rootFocused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { e -> handleKey(e, rootFocused, ::togglePlay, ::seekBy, ::poke, playFocus, onPrevious, onNext) }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (visible) visible = false else poke() },
                    onDoubleTap = { o ->
                        when {
                            o.x < size.width / 3f -> seekBy(-10_000)
                            o.x > size.width * 2f / 3f -> seekBy(10_000)
                            else -> togglePlay()
                        }
                    },
                )
            }
            .swipeAdjust(swipe, swipeEnabled),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    isFocusable = false
                }
            },
            update = { it.player = player },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        if (state.resolving || (state.buffering && !state.ended)) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = Palette.Accent, strokeWidth = 3.dp)
        }

        SwipeAdjustIndicator(swipe, Modifier.align(Alignment.Center))

        if (!inPip) {
            AnimatedVisibility(visible = visible || state.ended || state.error != null, enter = fadeIn(), exit = fadeOut()) {
                Controls(
                    engine = engine,
                    state = state,
                    title = title,
                    subtitle = subtitle,
                    playFocus = playFocus,
                    onBack = onBack,
                    onTogglePlay = ::togglePlay,
                    onSeek = ::seekBy,
                    onQuality = { showQuality = true },
                    onMore = if (comments != null || download != null) ({ showMore = true }) else null,
                    onPip = if (!isTv) enterPip else null,
                    onInteract = ::poke,
                    compact = portraitShorts,
                )
            }
        }

        if (showComments && comments != null && !inPip) {
            CommentsPanel(comments, isTv, Modifier.align(if (isTv) Alignment.CenterEnd else Alignment.BottomCenter)) {
                showComments = false
                runCatching { rootFocus.requestFocus() }
            }
        }

        state.error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).widthIn(max = 520.dp).background(Palette.Surface, RoundedCornerShape(16.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Can't play this video", style = MaterialTheme.typography.titleMedium)
                Text(msg, color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Try again", engine::retry)
                    SecondaryButton("Back", onBack)
                }
            }
        }
    }

    // Declared last, so Back closes the panel before anything else.
    BackHandler(enabled = showComments) {
        showComments = false
        runCatching { rootFocus.requestFocus() }
    }

    if (showMore) {
        MoreDialog(
            onComments = comments?.let { c -> { c.start(); showComments = true } },
            download = download,
            onDismiss = { showMore = false },
        )
    }

    if (showQuality) {
        state.plan?.takeIf { it.qualityOptions.size > 1 }?.let { plan ->
            QualityDialog(plan, onPick = { engine.selectVideo(it); showQuality = false }, onDismiss = { showQuality = false })
        }
    }
}

private fun handleKey(
    e: KeyEvent,
    rootFocused: Boolean,
    togglePlay: () -> Unit,
    seekBy: (Long) -> Unit,
    poke: () -> Unit,
    playFocus: FocusRequester,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val repeat = e.nativeKeyEvent.repeatCount
    // Hold to seek faster: 10 s steps, then 30 s, then a minute.
    val step = when { repeat < 3 -> 10_000L; repeat < 12 -> 30_000L; else -> 60_000L }
    when (e.key) {
        Key.MediaPlayPause -> { togglePlay(); return true }
        Key.MediaFastForward -> { seekBy(step); return true }
        Key.MediaRewind -> { seekBy(-step); return true }
        Key.MediaNext -> { onNext?.invoke(); return onNext != null }
        Key.MediaPrevious -> { onPrevious?.invoke(); return onPrevious != null }
    }
    if (!rootFocused) return false
    return when (e.key) {
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar -> { if (repeat == 0) togglePlay(); true }
        Key.DirectionLeft -> { seekBy(-step); true }
        Key.DirectionRight -> { seekBy(step); true }
        Key.DirectionUp -> {
            if (onPrevious != null) onPrevious() else { poke(); runCatching { playFocus.requestFocus() } }
            true
        }
        Key.DirectionDown -> {
            if (onNext != null) onNext() else { poke(); runCatching { playFocus.requestFocus() } }
            true
        }
        else -> false
    }
}

@Composable
private fun Controls(
    engine: PlayerEngine,
    state: EngineState,
    title: String,
    subtitle: String,
    playFocus: FocusRequester,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onQuality: () -> Unit,
    onMore: (() -> Unit)?,
    onPip: (() -> Unit)?,
    onInteract: () -> Unit,
    compact: Boolean,
) {
    val player = engine.player
    var position by remember { mutableLongStateOf(player.currentPosition) }
    var buffered by remember { mutableLongStateOf(player.bufferedPosition) }
    var duration by remember { mutableLongStateOf(player.duration) }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            buffered = player.bufferedPosition
            duration = player.duration
            delay(250)
        }
    }
    val live = (state.plan as? PlaybackPlan.Manifest)?.live == true
    val gutter = if (LocalIsTv.current) 48.dp else 16.dp

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().align(Alignment.TopStart)
                .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                .padding(horizontal = gutter, vertical = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!LocalIsTv.current) {
                    RoundButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack, onInteract)
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(
                        subtitle.takeIf { it.isNotBlank() },
                        state.plan?.chosenQuality?.label,
                        if (live) "LIVE" else null,
                        if (state.plan is PlaybackPlan.Local) "Downloaded" else null,
                    ).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomStart)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                .padding(horizontal = gutter, vertical = 20.dp),
        ) {
            if (!live) {
                SeekBar(position, buffered, duration, onSeekTo = { player.seekTo(it); onInteract() }, onStep = onSeek)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(formatPosition(position), style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
                    Spacer(Modifier.weight(1f))
                    if (duration != C.TIME_UNSET) Text(formatPosition(duration), style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!live && !compact) RoundButton(Icons.Filled.Replay10, "Back 10 seconds", { onSeek(-10_000) }, onInteract)
                RoundButton(
                    when {
                        state.ended -> Icons.Filled.Replay
                        state.playing -> Icons.Filled.Pause
                        else -> Icons.Filled.PlayArrow
                    },
                    if (state.playing) "Pause" else "Play",
                    onTogglePlay,
                    onInteract,
                    Modifier.focusRequester(playFocus),
                    large = true,
                )
                if (!live && !compact) RoundButton(Icons.Filled.Forward10, "Forward 10 seconds", { onSeek(10_000) }, onInteract)
                Spacer(Modifier.weight(1f))
                if (state.plan?.qualityOptions.orEmpty().size > 1) {
                    RoundButton(Icons.Filled.HighQuality, "Quality", onQuality, onInteract)
                }
                onMore?.let { RoundButton(Icons.Filled.MoreVert, "More", it, onInteract) }
                onPip?.let { RoundButton(Icons.Filled.PictureInPictureAlt, "Picture in picture", it, onInteract) }
            }
        }
    }
}

/** Comments beside the video (TV: a side sheet; phone: the lower half), which keeps playing. */
@Composable
private fun CommentsPanel(loader: CommentsLoader, isTv: Boolean, modifier: Modifier, onClose: () -> Unit) {
    val state by loader.state.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    val close = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { close.requestFocus() } }
    LaunchedEffect(state.nodes.isNotEmpty()) { if (state.nodes.isNotEmpty()) runCatching { first.requestFocus() } }
    Column(
        modifier
            .then(if (isTv) Modifier.fillMaxHeight().width(460.dp) else Modifier.fillMaxWidth().fillMaxHeight(0.6f))
            .background(Palette.Surface.copy(alpha = 0.96f), RoundedCornerShape(if (isTv) 0.dp else 16.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Comments", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.focusRequester(close)) { Icon(Icons.Filled.Close, "Close comments") }
        }
        CommentsList(state, loader::more, loader::toggleReplies, loader::moreReplies, firstFocus = first)
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    onInteract: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onInteract() }
    val size = if (large) 56.dp else 44.dp
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (focused) Palette.Accent else Color(0x33FFFFFF))
            .clickable(interactionSource = interaction, indication = null) { onClick(); onInteract() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (focused) Palette.OnAccent else Color.White, modifier = Modifier.size(size * 0.55f))
    }
}

/** Focusable on TV (left/right step), draggable and tappable on touch screens. */
@Composable
private fun SeekBar(position: Long, buffered: Long, duration: Long, onSeekTo: (Long) -> Unit, onStep: (Long) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val total = duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 1L
    Box(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .focusable(interactionSource = interaction)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val step = if (e.nativeKeyEvent.repeatCount < 3) 10_000L else 30_000L
                when (e.key) {
                    Key.DirectionLeft -> { onStep(-step); true }
                    Key.DirectionRight -> { onStep(step); true }
                    else -> false
                }
            }
            .pointerInput(total) {
                detectTapGestures { o -> onSeekTo((o.x / size.width * total).toLong()) }
            }
            .drawBehind {
                val h = if (focused) 6.dp.toPx() else 4.dp.toPx()
                val y = (size.height - h) / 2
                val r = CornerRadius(h / 2)
                drawRoundRect(Color(0x40FFFFFF), Offset(0f, y), Size(size.width, h), r)
                drawRoundRect(Color(0x66FFFFFF), Offset(0f, y), Size(size.width * (buffered.toFloat() / total).coerceIn(0f, 1f), h), r)
                val px = size.width * (position.toFloat() / total).coerceIn(0f, 1f)
                drawRoundRect(Palette.Accent, Offset(0f, y), Size(px, h), r)
                drawCircle(Palette.Accent, radius = if (focused) 9.dp.toPx() else 6.dp.toPx(), center = Offset(px, size.height / 2))
            },
    )
}

/** What the player keeps out of its control row: comments and downloading. */
@Composable
private fun MoreDialog(onComments: (() -> Unit)?, download: DownloadMenu?, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(340.dp).background(Palette.Surface, RoundedCornerShape(20.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            onComments?.let { open ->
                MenuItem(Icons.AutoMirrored.Filled.Comment, "Comments", Modifier.focusRequester(first)) { onDismiss(); open() }
            }
            download?.let { d ->
                MenuItem(d.icon, d.label, if (onComments == null) Modifier.focusRequester(first) else Modifier) { onDismiss(); d.onClick() }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun QualityDialog(plan: PlaybackPlan, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val chosen = plan.chosenQuality?.id
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(340.dp).background(Palette.Surface, RoundedCornerShape(20.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Quality", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
            plan.qualityOptions.forEach { c ->
                ListRow(onClick = { onPick(c.id) }) {
                    Text(
                        c.label + if (c.id == chosen) "  ✓" else "",
                        color = if (c.id == chosen) Palette.Accent else Palette.Text,
                    )
                }
            }
        }
    }
}

/** Full screen while the player is open; bars come back on swipe and when it closes. */
@Composable
private fun ImmersiveMode() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun FrameRateEffect(enabled: Boolean, fps: Float?) {
    val activity = LocalActivity.current ?: return
    LaunchedEffect(enabled, fps) {
        val display = activity.display
        if (enabled && fps != null && display != null) FrameRateMatcher.apply(activity.window, display, fps)
    }
    DisposableEffect(activity) { onDispose { FrameRateMatcher.reset(activity.window) } }
}

@Composable
private fun PipSessionEffect(state: EngineState) {
    LaunchedEffect(state.playing, state.videoWidth, state.videoHeight) {
        PlayerSession.update(
            PlayerSession.Info(
                active = true,
                playing = state.playing,
                width = state.videoWidth.takeIf { it > 0 } ?: 16,
                height = state.videoHeight.takeIf { it > 0 } ?: 9,
            ),
        )
    }
    DisposableEffect(Unit) { onDispose { PlayerSession.clear() } }
}
