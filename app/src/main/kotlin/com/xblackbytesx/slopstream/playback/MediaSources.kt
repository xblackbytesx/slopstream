package com.xblackbytesx.slopstream.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.xblackbytesx.slopstream.core.playback.ManifestType
import com.xblackbytesx.slopstream.core.playback.PlaybackPlan
import com.xblackbytesx.slopstream.core.playback.Track
import com.xblackbytesx.slopstream.youtube.StreamRequests
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a [PlaybackPlan] into Media3 sources. googlevideo.com requests are reshaped by
 * [StreamRequests] (range as a query parameter, POST body, client headers). A manifest plan's
 * [PlaybackPlan.Manifest.authHeaders] go to its [PlaybackPlan.Manifest.authHosts] only (the HLS
 * key server); every other URL (playlists, segments, thumbnails) passes through untouched.
 */
@OptIn(UnstableApi::class)
@Singleton
class MediaSources @Inject constructor(okHttp: OkHttpClient) {

    private val requestNumber = AtomicLong()
    private val http = OkHttpDataSource.Factory(okHttp)

    private val youtube = ResolvingDataSource.Factory(http) { spec: DataSpec ->
        val url = spec.uri.toString()
        if (!StreamRequests.isVideoPlayback(url)) return@Factory spec
        val length = if (spec.length == C.LENGTH_UNSET.toLong()) -1L else spec.length
        val shaped = StreamRequests.shape(url, spec.position, length, requestNumber.getAndIncrement())
        spec.buildUpon()
            .setUri(Uri.parse(shaped.url))
            // The range now lives in the URL; the response starts at the requested byte.
            .setPosition(0)
            .setLength(C.LENGTH_UNSET.toLong())
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(shaped.body)
            .setHttpRequestHeaders(spec.httpRequestHeaders + shaped.headers)
            .build()
    }

    fun build(plan: PlaybackPlan): MediaSource = when (plan) {
        is PlaybackPlan.Adaptive -> MergingMediaSource(
            /* adjustPeriodTimeOffsets = */ true,
            /* clipDurations = */ true,
            dash(plan.video),
            dash(plan.audio),
        )
        is PlaybackPlan.Manifest -> {
            val item = MediaItem.Builder()
                .setUri(plan.url)
                .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(LIVE_EDGE_MS).build())
                .build()
            val factory = authorized(plan)
            when (plan.type) {
                ManifestType.HLS -> HlsMediaSource.Factory(factory).setAllowChunklessPreparation(true).createMediaSource(item)
                ManifestType.DASH -> DashMediaSource.Factory(DefaultDashChunkSource.Factory(factory), factory).createMediaSource(item)
            }
        }
        is PlaybackPlan.Progressive -> ProgressiveMediaSource.Factory(youtube)
            .createMediaSource(MediaItem.fromUri(plan.url))
    }

    private fun authorized(plan: PlaybackPlan.Manifest): DataSource.Factory {
        if (plan.authHeaders.isEmpty()) return http
        return ResolvingDataSource.Factory(http) { spec: DataSpec ->
            if (spec.uri.host in plan.authHosts && spec.uri.scheme == "https") {
                spec.withAdditionalHeaders(plan.authHeaders)
            } else {
                spec
            }
        }
    }

    private fun dash(track: Track): MediaSource {
        val text = requireNotNull(track.dashManifest) { "track without a manifest" }
        val manifest = DashManifestParser().parse(Uri.parse(track.url), text.byteInputStream())
        return DashMediaSource.Factory(DefaultDashChunkSource.Factory(youtube), youtube)
            .createMediaSource(
                manifest,
                MediaItem.Builder().setUri(track.url).setCustomCacheKey(track.cacheKey).build(),
            )
    }

    private companion object {
        const val LIVE_EDGE_MS = 10_000L
    }
}
