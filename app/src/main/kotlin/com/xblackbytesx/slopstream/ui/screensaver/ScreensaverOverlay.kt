package com.xblackbytesx.slopstream.ui.screensaver

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xblackbytesx.slopstream.R
import com.xblackbytesx.slopstream.screensaver.BounceField
import com.xblackbytesx.slopstream.screensaver.Screensaver
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.delay

/** Shows the screensaver over everything, dialogs included, while [Screensaver.active]. */
@Composable
fun ScreensaverHost() {
    val active by Screensaver.active.collectAsStateWithLifecycle()
    if (active) ScreensaverWindow(onWake = Screensaver::wake)
}

/**
 * A window of its own on top of every other (an open menu is a window too), black edge to edge
 * with the system bars hidden. Any key or touch wakes it and goes no further, so the press that
 * wakes never also opens a tile. Volume keys pass through and keep it up.
 */
@Composable
private fun ScreensaverWindow(onWake: () -> Unit) {
    Dialog(
        onDismissRequest = onWake,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.run {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.also {
                    it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
                WindowCompat.getInsetsController(this, decorView).run {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose { }
        }
        val focus = remember { FocusRequester() }
        val fade = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            runCatching { focus.requestFocus() }
            fade.animateTo(1f, tween(FADE_IN_MS))
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = fade.value }
                .background(Color.Black)
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    when (e.key) {
                        Key.VolumeUp, Key.VolumeDown, Key.VolumeMute -> false
                        else -> {
                            if (e.type == KeyEventType.KeyUp) onWake()
                            true
                        }
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        onWake()
                    }
                },
        ) {
            BouncingLogo()
        }
    }
}

/**
 * The SLOP logo crossing the screen diagonally, changing color at every edge and flashing white
 * when it meets a corner exactly (see [BounceField]). [frozenAt] draws one still frame at that
 * travel, for screenshots.
 */
@Composable
fun BouncingLogo(modifier: Modifier = Modifier, frozenAt: Double? = null, random: Random = Random.Default) {
    val density = LocalDensity.current
    var screen by remember { mutableStateOf(IntSize.Zero) }
    var logo by remember { mutableStateOf(IntSize.Zero) }
    val speed = with(density) { SPEED_DP_PER_SECOND.dp.toPx().toDouble() }
    val field = remember(screen, logo, speed) {
        if (screen == IntSize.Zero || logo == IntSize.Zero) null
        else BounceField(screen.width - logo.width, screen.height - logo.height, speed, random)
    }
    var travel by remember(field) { mutableDoubleStateOf(frozenAt ?: 0.0) }
    if (field != null && frozenAt == null) {
        LaunchedEffect(field) {
            val start = withFrameNanos { it }
            while (true) withFrameNanos { travel = (it - start) / 1e9 * speed }
        }
    }
    val bounces by remember(field) { derivedStateOf { field?.bounces(travel) ?: 0L } }
    val corners by remember(field) { derivedStateOf { field?.corners(travel) ?: 0L } }
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(corners) {
        if (corners == 0L) return@LaunchedEffect
        repeat(CORNER_FLASHES * 2) {
            flash = !flash
            delay(CORNER_FLASH_MS)
        }
        flash = false
    }
    val color = if (flash) Color.White else LogoColors[(bounces % LogoColors.size).toInt()]

    BoxWithConstraints(modifier.fillMaxSize().onSizeChanged { screen = it }) {
        val size = with(density) { (minOf(maxWidth, maxHeight) * LOGO_SHARE).toSp() }
        SlopLogo(
            color = color,
            size = size,
            modifier = Modifier
                .offset {
                    val f = field ?: return@offset IntOffset.Zero
                    IntOffset(f.x(travel).roundToInt(), f.y(travel).roundToInt())
                }
                .onSizeChanged { logo = it }
                .alpha(if (field == null) 0f else 1f),
        )
    }
}

/**
 * SLOP in Anton (an open-licensed Impact cut, decision 29) over a spaced-out STREAM, drawn to
 * the letters' own ink bounds: the box that bounces is exactly what you see, so a corner hit
 * touches the corner.
 */
@Composable
fun SlopLogo(color: Color, size: TextUnit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val px = with(density) { size.toPx() }
    val layout = remember(px) {
        val typeface = ResourcesCompat.getFont(context, R.font.anton_regular) ?: Typeface.DEFAULT_BOLD
        LogoLayout(typeface, px)
    }
    Canvas(modifier.size(with(density) { layout.width.toDp() }, with(density) { layout.height.toDp() })) {
        layout.draw(drawContext.canvas.nativeCanvas, color.toArgb())
    }
}

private class LogoLayout(typeface: Typeface, size: Float) {
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; textSize = size; letterSpacing = 0.02f }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; textSize = size * 0.24f; letterSpacing = 0.55f }
    private val bigInk = Rect().also { big.getTextBounds(TOP, 0, TOP.length, it) }
    private val smallInk = Rect().also { small.getTextBounds(BOTTOM, 0, BOTTOM.length, it) }
    private val gap = size * 0.12f
    val width = max(bigInk.width(), smallInk.width()).toFloat()
    val height = bigInk.height() + gap + smallInk.height()

    fun draw(canvas: android.graphics.Canvas, argb: Int) {
        big.color = argb
        small.color = argb
        canvas.drawText(TOP, (width - bigInk.width()) / 2 - bigInk.left, -bigInk.top.toFloat(), big)
        canvas.drawText(
            BOTTOM,
            (width - smallInk.width()) / 2 - smallInk.left,
            bigInk.height() + gap - smallInk.top,
            small,
        )
    }

    private companion object {
        const val TOP = "SLOP"
        const val BOTTOM = "STREAM"
    }
}

/** Bright on black, one per edge, in a fixed order like the DVD logo's. */
private val LogoColors = listOf(
    Color(0xFF5EEAD4), // the app's accent
    Color(0xFFF43F5E),
    Color(0xFFFBBF24),
    Color(0xFFA78BFA),
    Color(0xFF38BDF8),
    Color(0xFFA3E635),
    Color(0xFFFB923C),
    Color(0xFFF472B6),
)

/** About ten seconds across a TV. */
private const val SPEED_DP_PER_SECOND = 90f
/** SLOP's font size as a share of the screen's shorter side. */
private const val LOGO_SHARE = 0.17f
private const val FADE_IN_MS = 1_200
private const val CORNER_FLASHES = 4
private const val CORNER_FLASH_MS = 140L
