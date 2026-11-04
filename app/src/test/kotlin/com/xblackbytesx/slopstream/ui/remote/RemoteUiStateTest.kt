package com.xblackbytesx.slopstream.ui.remote

import com.xblackbytesx.slopstream.data.remote.NowPlaying
import com.xblackbytesx.slopstream.data.remote.PlayState
import com.xblackbytesx.slopstream.data.remote.RemoteItem
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteUiStateTest {
    private fun at(state: PlayState, position: Long, duration: Long = 100_000) = RemoteUiState(
        loaded = true,
        now = NowPlaying("s", RemoteItem("yt", "v", "T", "C"), state, position, duration, at = 0),
        receivedAt = 1_000,
    )

    @Test
    fun aPlayingTvMovesOnFromItsLastReportAndStopsAtTheEnd() {
        assertEquals(13_000L, at(PlayState.PLAYING, 10_000).positionAt(4_000))
        assertEquals(100_000L, at(PlayState.PLAYING, 99_000).positionAt(60_000))
        assertEquals(10_000L, at(PlayState.PAUSED, 10_000).positionAt(60_000))
        // A live stream has no end to stop at.
        assertEquals(70_000L, at(PlayState.PLAYING, 10_000, duration = 0).positionAt(61_000))
    }
}
