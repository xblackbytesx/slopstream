package com.xblackbytesx.slopstream.playback

import androidx.media3.common.C
import com.xblackbytesx.slopstream.core.playback.PlaybackPlan
import com.xblackbytesx.slopstream.data.WatchRepository
import com.xblackbytesx.slopstream.data.remote.PlayState
import com.xblackbytesx.slopstream.data.remote.RemoteAction
import com.xblackbytesx.slopstream.data.remote.RemoteCommand
import com.xblackbytesx.slopstream.data.remote.RemoteReceiver
import com.xblackbytesx.slopstream.data.remote.StateReport
import com.xblackbytesx.slopstream.screensaver.Screensaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A player on this TV playing a session sent from another device (decision 28): reports how it
 * plays (on every change, every few seconds while playing) and takes the sender's pause, resume,
 * seek and stop. [scope] is the player screen's, on the main thread like the player.
 */
class RemoteSessionLink(
    private val engine: PlayerEngine,
    private val receiver: RemoteReceiver,
    private val session: String,
    scope: CoroutineScope,
) {
    private val _stop = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The sender pressed Stop: the player screen closes. */
    val stop: SharedFlow<Unit> = _stop.asSharedFlow()

    init {
        scope.launch { receiver.controls(session).collect(::apply) }
        scope.launch {
            while (isActive) {
                val last = phase()
                report(last)
                withTimeoutOrNull(if (last == PlayState.PLAYING) PLAYING_EVERY_MS else IDLE_EVERY_MS) {
                    engine.state.first { phase() != last }
                }
            }
        }
    }

    private fun apply(c: RemoteCommand) {
        // The sender's remote is input too: a seek while paused should be seen.
        Screensaver.wake()
        val player = engine.player
        when (c.action) {
            RemoteAction.PAUSE -> player.pause()
            RemoteAction.RESUME -> {
                if (engine.state.value.ended) player.seekTo(0)
                player.play()
            }
            RemoteAction.SEEK -> c.positionMs?.let {
                player.seekTo(it)
                // A seek while paused may change nothing the loop watches.
                report(phase())
            }
            RemoteAction.STOP -> _stop.tryEmit(Unit)
            RemoteAction.PLAY -> Unit
        }
    }

    /** The player is closing: the session's last report. */
    fun close() = report(PlayState.STOPPED)

    private fun phase(): PlayState {
        val s = engine.state.value
        return when {
            s.error != null -> PlayState.ERROR
            s.ended -> PlayState.ENDED
            s.resolving || s.buffering -> PlayState.BUFFERING
            s.playing -> PlayState.PLAYING
            else -> PlayState.PAUSED
        }
    }

    private fun report(state: PlayState) {
        val player = engine.player
        val s = engine.state.value
        val live = (s.plan as? PlaybackPlan.Manifest)?.live == true
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 && !live } ?: 0
        val position = player.currentPosition.coerceAtLeast(0)
        receiver.report(
            StateReport(
                session = session,
                state = state,
                positionMs = position,
                durationMs = duration,
                watched = WatchRepository.isWatched(position, duration),
                error = s.error,
            ),
        )
    }

    private companion object {
        const val PLAYING_EVERY_MS = 5_000L
        const val IDLE_EVERY_MS = 30_000L
    }
}
