package com.xblackbytesx.slopstream.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the activity needs to know about the player for picture-in-picture: whether a video is
 * on screen and playing, and its shape. The player screen writes it, MainActivity reads it.
 */
object PlayerSession {
    data class Info(val active: Boolean = false, val playing: Boolean = false, val width: Int = 16, val height: Int = 9)

    private val _info = MutableStateFlow(Info())
    val info: StateFlow<Info> = _info.asStateFlow()

    private val _inPip = MutableStateFlow(false)
    val inPip: StateFlow<Boolean> = _inPip.asStateFlow()

    fun update(info: Info) { _info.value = info }
    fun clear() { _info.value = Info() }
    fun setInPip(on: Boolean) { _inPip.value = on }
}
