package com.xblackbytesx.slopstream.screensaver

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.random.Random

/**
 * Where the screensaver logo is after travelling [t] pixels diagonally (decision 29), bouncing
 * off the edges like the old DVD players did.
 *
 * Moving at 45 degrees, the logo meets a corner exactly when its travel is a multiple of both
 * ranges at once. With raw screen sizes that may take hours, or never come; so the ranges are
 * shrunk by a few invisible pixels (black on black) until a corner comes every
 * [MIN_CORNER_SECONDS] to [MAX_CORNER_SECONDS], and the start is placed so it surely comes.
 * Position is continuous (smooth motion), counts are exact.
 *
 * [spanX] and [spanY] are the screen size minus the logo size, in pixels.
 */
class BounceField(spanX: Int, spanY: Int, pixelsPerSecond: Double, random: Random = Random.Default) {
    val rangeX: Int
    val rangeY: Int
    /** Travel between two corner hits. */
    val cornerEvery: Long
    /** Black margin left and top, half of what the ranges gave up, so the path stays centered. */
    val insetX: Int
    val insetY: Int
    private val phaseX: Long
    private val phaseY: Long
    /** Travel at the first corner. */
    val firstCorner: Long

    init {
        val sx = spanX.coerceAtLeast(1)
        val sy = spanY.coerceAtLeast(1)
        val (rx, ry) = pickRanges(sx, sy, pixelsPerSecond)
        rangeX = rx
        rangeY = ry
        insetX = (sx - rx) / 2
        insetY = (sy - ry) / 2
        cornerEvery = lcm(rx.toLong(), ry.toLong())
        // The first corner somewhere in the first third to whole cycle: not straight away.
        firstCorner = cornerEvery / 3 + random.nextLong(cornerEvery - cornerEvery / 3)
        phaseX = Math.floorMod(-firstCorner, 2L * rx)
        phaseY = Math.floorMod(-firstCorner, 2L * ry)
    }

    fun x(t: Double): Double = insetX + triangle(t + phaseX, rangeX)
    fun y(t: Double): Double = insetY + triangle(t + phaseY, rangeY)

    /** Edges hit so far; a corner counts twice. Drives the color change. */
    fun bounces(t: Double): Long = edges(t + phaseX, rangeX) - edges(phaseX.toDouble(), rangeX) +
        edges(t + phaseY, rangeY) - edges(phaseY.toDouble(), rangeY)

    /** Corners hit so far. */
    fun corners(t: Double): Long = if (t < firstCorner) 0 else ((t - firstCorner) / cornerEvery).toLong() + 1

    companion object {
        const val MIN_CORNER_SECONDS = 4 * 60.0
        const val MAX_CORNER_SECONDS = 12 * 60.0
        private const val TARGET_CORNER_SECONDS = 7 * 60.0
        /** At most this share of each span is given up to make the corners come. */
        private const val MAX_SHRINK = 0.08

        private fun triangle(s: Double, range: Int): Double {
            val m = s.mod(2.0 * range)
            return if (m <= range) m else 2.0 * range - m
        }

        private fun edges(s: Double, range: Int): Long = floor(s / range).toLong()

        internal fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
        internal fun lcm(a: Long, b: Long): Long = a / gcd(a, b) * b

        /**
         * The largest ranges whose corner period falls in the wanted window; failing that, the
         * pair whose period is closest to it. Ties go to the period nearest the target.
         */
        internal fun pickRanges(sx: Int, sy: Int, pixelsPerSecond: Double): Pair<Int, Int> {
            val min = MIN_CORNER_SECONDS * pixelsPerSecond
            val max = MAX_CORNER_SECONDS * pixelsPerSecond
            val target = TARGET_CORNER_SECONDS * pixelsPerSecond
            val lowX = (sx - (sx * MAX_SHRINK).toInt()).coerceAtLeast(1)
            val lowY = (sy - (sy * MAX_SHRINK).toInt()).coerceAtLeast(1)
            var best = sx to sy
            var bestKey = Double.MAX_VALUE to Double.MAX_VALUE
            for (rx in sx downTo lowX) for (ry in sy downTo lowY) {
                val period = lcm(rx.toLong(), ry.toLong()).toDouble()
                val outside = when {
                    period < min -> ln(min / period)
                    period > max -> ln(period / max)
                    else -> 0.0
                }
                // Inside the window: give up as little as possible. Outside: get closest to it.
                val key = if (outside == 0.0) 0.0 to (sx - rx + sy - ry) + abs(ln(period / target)) / 10
                else outside to 0.0
                if (key.first < bestKey.first || (key.first == bestKey.first && key.second < bestKey.second)) {
                    best = rx to ry
                    bestKey = key
                }
            }
            return best
        }
    }
}
