package com.xblackbytesx.slopstream.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The audio-only play that goes on after its player screen closed (decision 27): the mini
 * player shows it, and opening that video again takes the same engine back, so nothing
 * restarts. One at a time; any other play ends it.
 */
@Singleton
class AudioPlayback @Inject constructor() {
    private val _current = MutableStateFlow<PlayerEngine?>(null)
    val current: StateFlow<PlayerEngine?> = _current.asStateFlow()

    /** Keeps [engine] playing without a screen; ends whatever was there before. */
    fun adopt(engine: PlayerEngine) {
        val before = _current.value
        if (before === engine) return
        _current.value = engine
        before?.release()
    }

    /** The kept engine when it plays [videoId] of [source], handed back to a player screen. */
    fun reclaim(source: String, videoId: String): PlayerEngine? {
        val engine = _current.value ?: return null
        val s = engine.state.value
        if (s.source != source || s.videoId != videoId) return null
        _current.value = null
        return engine
    }

    /** The mini player's close, or another video starting: progress is saved on the way out. */
    fun stop() {
        val engine = _current.value ?: return
        _current.value = null
        engine.release()
    }
}
