package com.xblackbytesx.slopstream.playback

import android.view.Display
import android.view.Window
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Picks the display mode whose refresh rate fits the video: the same resolution, an exact match
 * (23.976 on 23.976/24 Hz) or else the smallest whole multiple (25 fps on 50 Hz, 30 on 60).
 * Works on Android 11 TVs like the Shield via `preferredDisplayModeId`; the system only
 * switches while the window is on screen, and [reset] hands control back.
 */
object FrameRateMatcher {

    fun apply(window: Window, display: Display, fps: Float) {
        val mode = pick(display, fps) ?: return
        if (window.attributes.preferredDisplayModeId == mode.modeId) return
        window.attributes = window.attributes.also { it.preferredDisplayModeId = mode.modeId }
    }

    fun reset(window: Window) {
        if (window.attributes.preferredDisplayModeId == 0) return
        window.attributes = window.attributes.also { it.preferredDisplayModeId = 0 }
    }

    internal fun pick(display: Display, fps: Float): Display.Mode? {
        if (fps <= 1f) return null
        val current = display.mode
        val sameSize = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        return choose(sameSize.map { it to it.refreshRate }, fps)
    }

    /** Pure part, for tests: (mode, refresh) pairs to the best fit, or null to leave it. */
    internal fun <T> choose(modes: List<Pair<T, Float>>, fps: Float): T? {
        modes.filter { abs(it.second - fps) < 0.05f }.minByOrNull { abs(it.second - fps) }?.let { return it.first }
        return modes
            .mapNotNull { (m, r) ->
                val k = (r / fps).roundToInt()
                val off = abs(r / fps - k)
                if (k >= 2 && off < 0.01f) Triple(m, k, off) else null
            }
            // Fewest repeats first, then the closest fit (30 fps: 60 Hz beats 59.94 Hz).
            .minWithOrNull(compareBy({ it.second }, { it.third }))
            ?.first
    }
}
