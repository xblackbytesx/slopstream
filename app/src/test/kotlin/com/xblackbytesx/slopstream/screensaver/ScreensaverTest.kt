package com.xblackbytesx.slopstream.screensaver

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BounceFieldTest {
    /** Spans (screen minus logo, px) and speeds (px/s) from a 1080p and a 4K TV and phones. */
    private val screens = listOf(
        Triple(1920 - 380, 1080 - 230, 180.0),
        Triple(3840 - 760, 2160 - 460, 360.0),
        Triple(1080 - 300, 2400 - 190, 247.0),
        Triple(2400 - 300, 1080 - 190, 247.0),
        Triple(1013, 601, 180.0),
        Triple(900, 900, 180.0),
    )

    @Test
    fun aCornerComesEveryFewMinutes() {
        for ((sx, sy, speed) in screens) {
            val f = BounceField(sx, sy, speed, Random(1))
            val seconds = f.cornerEvery / speed
            assertTrue("$sx x $sy: a corner every $seconds s", seconds in BounceField.MIN_CORNER_SECONDS..BounceField.MAX_CORNER_SECONDS)
            assertTrue("$sx x $sy gave up too much", f.rangeX >= sx * 0.92 && f.rangeY >= sy * 0.92)
        }
    }

    @Test
    fun theCornerIsExact() {
        for ((sx, sy, speed) in screens) {
            val f = BounceField(sx, sy, speed, Random(2))
            for (k in 0..2) {
                val t = (f.firstCorner + k * f.cornerEvery).toDouble()
                val x = f.x(t) - f.insetX
                val y = f.y(t) - f.insetY
                assertTrue("x=$x at corner $k", x == 0.0 || x == f.rangeX.toDouble())
                assertTrue("y=$y at corner $k", y == 0.0 || y == f.rangeY.toDouble())
                assertEquals(k.toLong(), f.corners(t - 1))
                assertEquals(k + 1L, f.corners(t))
            }
            assertTrue("not straight away", f.firstCorner >= f.cornerEvery / 3)
        }
    }

    @Test
    fun staysOnScreenAndCountsEdges() {
        val f = BounceField(1540, 850, 180.0, Random(3))
        var last = f.bounces(0.0)
        var t = 0.0
        while (t < 20_000.0) {
            assertTrue(f.x(t) in 0.0..1540.0 && f.y(t) in 0.0..850.0)
            val b = f.bounces(t)
            assertTrue(b >= last)
            last = b
            t += 7.3
        }
        // About one edge every range's worth of travel on each axis.
        assertEquals(20_000.0 / f.rangeX + 20_000.0 / f.rangeY, last.toDouble(), 2.0)
        assertEquals(0L, f.bounces(0.0))
    }

    @Test
    fun noRoomStillWorks() {
        val f = BounceField(0, -20, 180.0, Random(4))
        assertTrue(f.x(500.0) in 0.0..1.0 && f.y(500.0) in 0.0..1.0)
    }
}

class IdleTimerTest {
    private val timeout = 180_000L

    @Test
    fun comesUpAfterTheTimeoutWithoutInput() {
        val t = IdleTimer()
        assertFalse(t.tick(0, timeout, heldOff = false))
        assertFalse(t.tick(179_000, timeout, heldOff = false))
        assertTrue(t.tick(180_000, timeout, heldOff = false))
    }

    @Test
    fun inputPostponesItButDoesNotWakeIt() {
        val t = IdleTimer()
        t.tick(0, timeout, false)
        t.input(100_000)
        assertFalse(t.tick(270_000, timeout, false))
        assertTrue(t.tick(280_000, timeout, false))
        t.input(281_000)
        assertTrue("only wake() takes it down", t.tick(282_000, timeout, false))
        t.wake(283_000)
        assertFalse(t.tick(284_000, timeout, false))
    }

    @Test
    fun aPlayingVideoHoldsItOffAndTheCountStartsAtThePause() {
        val t = IdleTimer()
        t.tick(0, timeout, false)
        // A two-hour film without a key press.
        var now = 0L
        while (now <= 7_200_000) { assertFalse(t.tick(now, timeout, heldOff = true)); now += 1_000 }
        // Paused (or ended) at 7 200 s: the full timeout from there.
        assertFalse(t.tick(7_200_000 + 179_000, timeout, false))
        assertTrue(t.tick(7_200_000 + 180_000, timeout, false))
        // Playing again (a remote's Resume) takes it down.
        assertFalse(t.tick(7_200_000 + 181_000, timeout, heldOff = true))
    }

    @Test
    fun offNeverComesUp() {
        val t = IdleTimer()
        assertFalse(t.tick(0, 0, false))
        assertFalse(t.tick(10_000_000, 0, false))
    }
}
