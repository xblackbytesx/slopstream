package com.xblackbytesx.slopstream.data

import androidx.compose.ui.input.key.KeyEventType
import com.xblackbytesx.slopstream.playback.FrameRateMatcher
import com.xblackbytesx.slopstream.ui.components.HeldKeyGuard
import com.xblackbytesx.slopstream.ui.components.formatDuration
import com.xblackbytesx.slopstream.ui.components.matchesWords
import com.xblackbytesx.slopstream.ui.components.relativeTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    @Test
    fun watchedAtNinetyPercentOrTheEndScreen() {
        assertFalse(WatchRepository.isWatched(500_000, 1_000_000))
        assertTrue(WatchRepository.isWatched(900_000, 1_000_000))
        // A 20-minute video with 25 s left: the end screen.
        assertTrue(WatchRepository.isWatched(1_175_000, 1_200_000))
        // A 40-second Short is not "watched" just because it is short.
        assertFalse(WatchRepository.isWatched(5_000, 40_000))
    }

    @Test
    fun frameRateMatchPrefersExactThenTheSmallestMultiple() {
        val modes = listOf("60" to 60f, "50" to 50f, "24" to 24f, "23.976" to 23.976f, "59.94" to 59.94f)
        assertEquals("23.976", FrameRateMatcher.choose(modes, 23.976f))
        assertEquals("24", FrameRateMatcher.choose(modes, 24f))
        assertEquals("50", FrameRateMatcher.choose(modes, 25f))
        assertEquals("60", FrameRateMatcher.choose(modes, 30f))
        assertEquals("59.94", FrameRateMatcher.choose(modes, 29.97f))
        assertNull(FrameRateMatcher.choose(listOf("60" to 60f), 23.976f))
    }

    @Test
    fun labels() {
        assertEquals("4:05", formatDuration(245))
        assertEquals("1:02:33", formatDuration(3753))
        val now = 1_800_000_000_000L
        assertEquals("12 min ago", relativeTime(now - 12 * 60_000, now))
        assertEquals("3 h ago", relativeTime(now - 3 * 3_600_000, now))
        assertEquals("Yesterday", relativeTime(now - 30 * 3_600_000L, now))
        assertEquals("~4 days ago", relativeTime(now - 4 * 86_400_000L, now, approximate = true))
    }

    @Test
    fun menuIgnoresTheHeldKeyThatOpenedIt() {
        val g = HeldKeyGuard()
        // The long press that opened the menu: auto-repeats, then its release.
        assertTrue(g.consumes(KeyEventType.KeyDown, 7))
        assertTrue(g.consumes(KeyEventType.KeyDown, 8))
        assertTrue(g.consumes(KeyEventType.KeyUp, 0))
        // A fresh press works, and so does everything after it.
        assertFalse(g.consumes(KeyEventType.KeyDown, 0))
        assertFalse(g.consumes(KeyEventType.KeyUp, 0))
        assertFalse(g.consumes(KeyEventType.KeyDown, 3))
    }

    @Test
    fun channelFilterIgnoresCaseAccentsAndWordOrder() {
        assertTrue(matchesWords("Café Racer Garage", "garage cafe"))
        assertTrue(matchesWords("Linus Tech Tips", ""))
        assertFalse(matchesWords("Linus Tech Tips", "tech linked"))
    }
}
