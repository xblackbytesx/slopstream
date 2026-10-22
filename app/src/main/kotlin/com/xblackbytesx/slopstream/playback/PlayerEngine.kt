package com.xblackbytesx.slopstream.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.xblackbytesx.slopstream.core.playback.PlaybackPlan
import com.xblackbytesx.slopstream.data.FeedRepository
import com.xblackbytesx.slopstream.data.Settings
import com.xblackbytesx.slopstream.data.WatchRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class EngineState(
    val source: String? = null,
    val videoId: String? = null,
    val resolving: Boolean = false,
    val buffering: Boolean = false,
    val playing: Boolean = false,
    val ended: Boolean = false,
    val error: String? = null,
    val plan: PlaybackPlan? = null,
    /** The frame rate of the video track, once known: drives display mode matching. */
    val frameRate: Float? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
)

/**
 * One ExoPlayer and the bookkeeping around it: resolve, resume where you left off, save the
 * position every few seconds and on every pause, mark watched, and recover once from an
 * expired stream URL (HTTP 403) by resolving again at the same position.
 */
@OptIn(UnstableApi::class)
class PlayerEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val playback: PlaybackRepository,
    private val mediaSources: MediaSources,
    private val watch: WatchRepository,
    private val feed: FeedRepository,
    private val settings: Settings,
    loop: Boolean = false,
) {
    private val trackSelector = DefaultTrackSelector(context).apply {
        parameters = buildUponParameters().setForceHighestSupportedBitrate(true).build()
    }

    val player: ExoPlayer = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
        .setTrackSelector(trackSelector)
        // 4K VP9 runs 20+ Mbit/s: a deeper buffer rides out Wi-Fi dips without rebuffering.
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 120_000, 1_500, 3_000).build())
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()
        .also { it.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Position saves must outlive [scope]: the last one happens while the screen is torn down. */
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loadJob: Job? = null
    private var lastRecoveryAt = 0L

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(playing = isPlaying) }
                if (!isPlaying) saveNow()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.update { it.copy(buffering = playbackState == Player.STATE_BUFFERING, ended = playbackState == Player.STATE_ENDED) }
                if (playbackState == Player.STATE_ENDED) saveNow(ended = true)
            }

            override fun onTracksChanged(tracks: Tracks) {
                val video = player.videoFormat
                _state.update {
                    it.copy(
                        frameRate = video?.frameRate?.takeIf { r -> r > 0 },
                        videoWidth = video?.width ?: 0,
                        videoHeight = video?.height ?: 0,
                    )
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause
                val expired = cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 403
                val now = System.currentTimeMillis()
                val s = _state.value
                if (expired && now - lastRecoveryAt > 60_000 && s.source != null && s.videoId != null) {
                    lastRecoveryAt = now
                    playback.invalidate()
                    load(s.source, s.videoId, startAtMs = player.currentPosition)
                } else {
                    _state.update { it.copy(error = error.localizedMessage ?: "Playback failed (${error.errorCodeName})") }
                }
            }
        })
        scope.launch {
            while (isActive) {
                delay(10_000)
                if (player.isPlaying) saveNow()
            }
        }
    }

    /** Plays [videoId]; [startAtMs] null means "resume where the user left off". */
    fun load(source: String, videoId: String, startAtMs: Long? = null) {
        if (_state.value.videoId != videoId) saveNow()
        loadJob?.cancel()
        _state.value = EngineState(source = source, videoId = videoId, resolving = true)
        loadJob = scope.launch {
            try {
                val plan = playback.resolve(source, videoId)
                val start = startAtMs ?: watch.resumePositionMs(source, videoId)
                applyCapForManifests(plan)
                player.setMediaSource(mediaSources.build(plan), start)
                player.prepare()
                player.playWhenReady = true
                _state.update { it.copy(resolving = false, plan = plan) }
                if (plan.durationSec > 0) feed.rememberDuration(source, videoId, plan.durationSec)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(resolving = false, error = e.message ?: "Could not load this video") }
            }
        }
    }

    fun retry() {
        val s = _state.value
        if (s.source != null && s.videoId != null) {
            playback.invalidate()
            load(s.source, s.videoId, startAtMs = player.currentPosition.takeIf { it > 0 })
        }
    }

    /**
     * Switches quality in place, keeping the position. A manifest plan already holds every
     * variant's playlist; an adaptive one asks its service for the new track.
     */
    fun selectVideo(candidateId: String) {
        val s = _state.value
        val source = s.source ?: return
        val position = player.currentPosition
        val current = s.plan
        if (current is PlaybackPlan.Manifest) {
            val next = current.withVariant(candidateId)
            if (next === current) return
            player.setMediaSource(mediaSources.build(next), position)
            player.prepare()
            _state.update { it.copy(plan = next) }
            return
        }
        val plan = current as? PlaybackPlan.Adaptive ?: return
        scope.launch {
            runCatching { playback.switchVideo(source, plan, candidateId) }
                .onSuccess { next ->
                    player.setMediaSource(mediaSources.build(next), position)
                    player.prepare()
                    _state.update { it.copy(plan = next) }
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    /**
     * Manifests with their own ladder (livestreams) get the cap in the track selector. A plan
     * that already picked its variant within the cap needs none.
     */
    private suspend fun applyCapForManifests(plan: PlaybackPlan) {
        val cap = settings.current().cap.maxLines
        val ladder = plan is PlaybackPlan.Manifest && plan.chosen == null
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setMaxVideoSize(Int.MAX_VALUE, if (ladder && cap != null) cap else Int.MAX_VALUE)
            .build()
    }

    fun saveNow(ended: Boolean = false) {
        val s = _state.value
        val source = s.source ?: return
        val id = s.videoId ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        if ((s.plan as? PlaybackPlan.Manifest)?.live == true) return
        val position = player.currentPosition
        persistScope.launch { watch.savePosition(source, id, position, duration, ended) }
    }

    fun release() {
        saveNow()
        player.release()
    }
}
