package com.xblackbytesx.slopstream.data

import com.xblackbytesx.slopstream.youtube.innertube.ResolveReport
import org.junit.Assert.assertEquals
import org.junit.Test

class ExtractorStatsTest {
    private fun ours(path: String, ms: Long) = ResolveReport("v", 0, "play", ms, path, null, emptyList())
    private fun fallback(ownMs: Long, newpipeMs: Long, error: String? = null) =
        ResolveReport("v", 0, "play", ownMs, null, "Failed: x", emptyList(), newpipeMs, error)

    @Test
    fun countsOursRescuesFallbacksAndFailures() {
        val s = ExtractorStats.of(
            listOf(
                ours("dash", 200), ours("dash", 300), ours("dash-new-visitor", 500), ours("hls", 700), ours("hls-live", 100),
                fallback(400, 1200), fallback(400, 900, error = "gone"),
            ),
        )
        assertEquals(7, s.plays)
        assertEquals(5, s.ours)
        assertEquals("a new visitor and HLS are rescues; live is not", 2, s.rescued)
        assertEquals(1, s.fallbacks)
        assertEquals(1, s.failed)
        assertEquals(300L, s.oursMedianMs)
        assertEquals("ours trying plus NewPipe", 1600L, s.fallbackMedianMs)
        assertEquals(mapOf("dash" to 2, "dash-new-visitor" to 1, "hls" to 1, "hls-live" to 1), s.paths)
    }

    @Test
    fun emptyIsEmpty() {
        val s = ExtractorStats.of(emptyList())
        assertEquals(0, s.plays)
        assertEquals(null, s.oursMedianMs)
    }
}
