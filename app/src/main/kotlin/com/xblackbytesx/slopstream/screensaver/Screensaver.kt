package com.xblackbytesx.slopstream.screensaver

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * When the screensaver comes up (decision 29): after [tick]s that find no input for the
 * timeout, anywhere in the app. A video that plays holds it off; the countdown starts again when
 * it pauses, ends or closes. Times are elapsed-realtime milliseconds, passed in for tests.
 */
class IdleTimer {
    var active: Boolean = false
        private set
    private var lastActivity = Long.MIN_VALUE

    /** A key, touch or remote command. Does not wake an active screensaver: [wake] does that. */
    fun input(now: Long) { lastActivity = now }

    fun wake(now: Long) {
        active = false
        lastActivity = now
    }

    /** [timeoutMs] 0 is Off. [heldOff]: a video plays on screen (or in picture-in-picture). */
    fun tick(now: Long, timeoutMs: Long, heldOff: Boolean): Boolean {
        if (lastActivity == Long.MIN_VALUE) lastActivity = now
        if (heldOff || timeoutMs <= 0) wake(now)
        else if (!active && now - lastActivity >= timeoutMs) active = true
        return active
    }
}

/** The app's one [IdleTimer]. MainActivity feeds it input and ticks; the overlay reads [active]. */
object Screensaver {
    private val timer = IdleTimer()
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun input() = synchronized(timer) { timer.input(SystemClock.elapsedRealtime()) }

    fun wake() = synchronized(timer) {
        timer.wake(SystemClock.elapsedRealtime())
        _active.value = false
    }

    fun tick(timeoutMs: Long, heldOff: Boolean) = synchronized(timer) {
        _active.value = timer.tick(SystemClock.elapsedRealtime(), timeoutMs, heldOff)
    }
}
