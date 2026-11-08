package com.xblackbytesx.slopstream.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.xblackbytesx.slopstream.ui.screensaver.Blackout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Decision 29: a paused, mostly white video showed through a strip at the left edge on the
 * Shield. The activity-side layer alone must leave no pixel of what is under it lit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BlackoutTest {
    @get:Rule val rule = createComposeRule()

    @Test @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun tv() = coversEveryPixel()

    @Test @Config(qualifiers = "w411dp-h891dp-port-xxhdpi")
    fun phone() = coversEveryPixel()

    private fun coversEveryPixel() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.fillMaxSize().background(Color.White))
            Blackout()
        }
        rule.mainClock.advanceTimeBy(5_000)
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        var lit = 0
        for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
            val c = pixels[x, y]
            if (c.red > 0f || c.green > 0f || c.blue > 0f) lit++
        }
        assertEquals("lit pixels of ${pixels.width} x ${pixels.height}", 0, lit)
    }
}
