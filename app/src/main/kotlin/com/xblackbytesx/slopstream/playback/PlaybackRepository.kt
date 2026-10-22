package com.xblackbytesx.slopstream.playback

import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.core.playback.PlaybackPlan
import com.xblackbytesx.slopstream.data.Clock
import com.xblackbytesx.slopstream.data.Settings
import com.xblackbytesx.slopstream.data.Sources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves playback plans, and pre-resolves the one under the cursor so pressing OK starts
 * instantly. Plans are kept for [TTL_MS]: stream URLs live for hours, but settings may change.
 */
@Singleton
class PlaybackRepository @Inject constructor(
    private val sources: Sources,
    private val settings: Settings,
    private val clock: Clock,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = LinkedHashMap<String, Pair<Long, Deferred<PlaybackPlan>>>()

    private fun key(source: String, id: String) = "$source:$id"

    /**
     * Starts resolving in the background; a later [resolve] for the same video reuses it.
     * Skipped for services that rate limit resolving (Floatplane).
     */
    fun prefetch(source: String, videoId: String) {
        if (sources[source].capabilities.prefetchPlayback) start(source, videoId)
    }

    suspend fun resolve(source: String, videoId: String): PlaybackPlan {
        val d = start(source, videoId)
        return try {
            d.await()
        } catch (e: Exception) {
            synchronized(inFlight) { inFlight.remove(key(source, videoId)) }
            throw e
        }
    }

    suspend fun switchVideo(source: String, plan: PlaybackPlan.Adaptive, candidateId: String): PlaybackPlan.Adaptive =
        sources[SourceKey.fromId(source)].switchVideo(plan, candidateId)

    /** Forget cached plans, e.g. after the quality cap changed. */
    fun invalidate() = synchronized(inFlight) { inFlight.clear() }

    private fun start(source: String, videoId: String): Deferred<PlaybackPlan> = synchronized(inFlight) {
        val k = key(source, videoId)
        val now = clock.now()
        inFlight[k]?.let { (at, d) ->
            // A failed Deferred counts as cancelled: resolve again instead of replaying the error.
            val failed = d.isCancelled
            if (now - at < TTL_MS && !failed) return d
        }
        val d = scope.async {
            val s = settings.current()
            sources[SourceKey.fromId(source)].resolvePlayback(videoId, s.effectiveProfile, s.prefs)
        }
        inFlight[k] = now to d
        while (inFlight.size > MAX_CACHED) inFlight.remove(inFlight.keys.first())
        d
    }

    private companion object {
        const val TTL_MS = 20 * 60_000L
        const val MAX_CACHED = 12
    }
}
