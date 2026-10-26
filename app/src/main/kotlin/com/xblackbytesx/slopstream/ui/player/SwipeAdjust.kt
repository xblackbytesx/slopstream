package com.xblackbytesx.slopstream.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xblackbytesx.slopstream.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

enum class SwipeKind { Brightness, Volume }

/**
 * Brightness and volume by swiping (phones): the left half of the video sets this window's
 * brightness, the right half the media volume, as in NewPipe and Grayjay. A swipe over 90% of
 * the height covers the whole range. The brightness holds until the player closes, and the
 * next player opened in this process starts from it; it is never saved.
 */
class SwipeAdjustState internal constructor(private val activity: Activity?, private val audio: AudioManager?) {
    var kind by mutableStateOf<SwipeKind?>(null)
        private set
    var level by mutableFloatStateOf(0f)
        private set
    var dragging by mutableStateOf(false)
        private set

    internal fun begin(kind: SwipeKind): Boolean {
        val start = when (kind) {
            SwipeKind.Brightness -> currentBrightness() ?: return false
            SwipeKind.Volume -> {
                val a = audio?.takeUnless { it.isVolumeFixed } ?: return false
                a.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            }
        }
        this.kind = kind
        level = start
        dragging = true
        return true
    }

    internal fun adjust(delta: Float) {
        level = (level + delta).coerceIn(0f, 1f)
        when (kind) {
            SwipeKind.Brightness -> applyBrightness(level).also { sessionBrightness = level }
            SwipeKind.Volume -> audio?.let { a ->
                val index = (level * a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).roundToInt()
                if (index != a.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                    runCatching { a.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0) }
                }
            }
            null -> {}
        }
    }

    internal fun end() { dragging = false }

    internal fun hide() { kind = null }

    internal fun restore() { sessionBrightness?.let(::applyBrightness) }

    internal fun reset() = applyBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)

    private fun currentBrightness(): Float? {
        val activity = activity ?: return null
        activity.window.attributes.screenBrightness.takeIf { it >= 0f }?.let { return it }
        val system = runCatching { Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()
        return (system ?: 128) / 255f
    }

    private fun applyBrightness(value: Float) {
        val window = activity?.window ?: return
        // 0 would be as dark as the panel goes; keep the picture visible at the bottom of the swipe.
        window.attributes = window.attributes.apply { screenBrightness = if (value < 0f) value else value.coerceAtLeast(0.01f) }
    }

    private companion object {
        var sessionBrightness: Float? = null
    }
}

@Composable
fun rememberSwipeAdjust(): SwipeAdjustState {
    val activity = LocalActivity.current
    val context = LocalContext.current
    val state = remember(activity) { SwipeAdjustState(activity, context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager) }
    DisposableEffect(state) {
        state.restore()
        onDispose { state.reset() }
    }
    LaunchedEffect(state.dragging) {
        if (!state.dragging) {
            delay(700)
            state.hide()
        }
    }
    return state
}

/** Vertical swipes, left half brightness, right half volume; the top and bottom tenth are left to the system bars. */
fun Modifier.swipeAdjust(state: SwipeAdjustState, enabled: Boolean): Modifier = pointerInput(state, enabled) {
    if (!enabled) return@pointerInput
    var active = false
    detectVerticalDragGestures(
        onDragStart = { o ->
            val edge = size.height * 0.1f
            active = o.y > edge && o.y < size.height - edge &&
                state.begin(if (o.x < size.width / 2f) SwipeKind.Brightness else SwipeKind.Volume)
        },
        onDragEnd = { if (active) state.end(); active = false },
        onDragCancel = { if (active) state.end(); active = false },
        onVerticalDrag = { change, dy ->
            if (active) {
                change.consume()
                state.adjust(-dy / (size.height * 0.9f))
            }
        },
    )
}

/** The level being swiped, centred over the video; fades shortly after the finger lifts. */
@Composable
fun SwipeAdjustIndicator(state: SwipeAdjustState, modifier: Modifier = Modifier) {
    var last by remember { mutableStateOf(SwipeKind.Volume) }
    state.kind?.let { last = it }
    AnimatedVisibility(state.kind != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        val level = state.level
        val icon = when (last) {
            SwipeKind.Brightness -> when {
                level < 0.34f -> Icons.Filled.BrightnessLow
                level < 0.67f -> Icons.Filled.BrightnessMedium
                else -> Icons.Filled.BrightnessHigh
            }
            SwipeKind.Volume -> when {
                level <= 0f -> Icons.AutoMirrored.Filled.VolumeOff
                level < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
                else -> Icons.AutoMirrored.Filled.VolumeUp
            }
        }
        Row(
            Modifier.background(Color(0xB3000000), RoundedCornerShape(24.dp)).padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, if (last == SwipeKind.Brightness) "Brightness" else "Volume", tint = Color.White, modifier = Modifier.size(24.dp))
            Box(Modifier.width(140.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(level).background(Palette.Accent))
            }
            Text("${(level * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.width(40.dp))
        }
    }
}
