package com.xblackbytesx.slopstream.device

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.view.Display
import com.xblackbytesx.slopstream.core.playback.DecoderCaps
import com.xblackbytesx.slopstream.core.playback.DeviceProfile
import com.xblackbytesx.slopstream.core.playback.PlaybackPlan
import com.xblackbytesx.slopstream.core.playback.PlaybackPrefs
import com.xblackbytesx.slopstream.core.playback.QualityCap
import com.xblackbytesx.slopstream.core.playback.VideoCodec
import com.xblackbytesx.slopstream.data.Settings
import com.xblackbytesx.slopstream.data.Sources
import com.xblackbytesx.slopstream.youtube.StreamRequests
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import javax.inject.Inject
import javax.inject.Singleton

data class DeviceCheckResult(
    val profile: DeviceProfile,
    val recommendedCap: QualityCap,
    val isTv: Boolean,
)

/**
 * Works out what this device plays well: hardware decoders per codec and size, the display's
 * resolution and HDR support, and the real download speed from YouTube's servers. Runs on
 * first start and from Settings; the result drives stream selection.
 */
@Singleton
class DeviceCheck @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sources: Sources,
    private val settings: Settings,
    private val okHttp: OkHttpClient,
) {
    enum class Step { DECODERS, DISPLAY, NETWORK, DONE }

    suspend fun run(onStep: (Step) -> Unit = {}): DeviceCheckResult {
        onStep(Step.DECODERS)
        val decoders = withContext(Dispatchers.Default) { probeDecoders() }
        onStep(Step.DISPLAY)
        val (displayLines, hdrDisplay) = probeDisplay()
        val tv = isTv(context)
        val partial = DeviceProfile(decoders, hdrDisplay, displayLines, throughputKbps = null, matchFrameRate = tv)

        onStep(Step.NETWORK)
        val kbps = runCatching { measureThroughput(partial) }.getOrNull()
        val profile = partial.copy(throughputKbps = kbps)

        val displayCap = when {
            displayLines >= 2160 -> QualityCap.BEST
            displayLines >= 1440 -> QualityCap.P1440
            displayLines >= 1080 -> QualityCap.P1080
            else -> QualityCap.P720
        }
        val netCap = QualityCap.recommendedFor(kbps)
        // The stricter of the two; BEST has no ceiling.
        val recommended = listOf(displayCap, netCap).maxBy { it.ordinal }
        settings.saveDeviceCheck(profile, recommended)
        onStep(Step.DONE)
        return DeviceCheckResult(profile, recommended, tv)
    }

    private fun probeDecoders(): List<DecoderCaps> {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        return MIME.mapNotNull { (codec, mime) ->
            val hw = infos.filter { info ->
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) } && info.isHardwareAccelerated && !info.isSoftwareOnly
            }
            if (hw.isEmpty()) return@mapNotNull null
            val caps = hw.map { it.getCapabilitiesForType(mime) }
            val fpsByLines = LADDER.associate { (w, h) ->
                val best = listOf(60, 30, 24).firstOrNull { fps ->
                    caps.any { c -> c.videoCapabilities?.areSizeAndRateSupported(w, h, fps.toDouble()) == true }
                } ?: 0
                h to best
            }
            val hdr = caps.any { c -> c.profileLevels.any { it.profile in (HDR_PROFILES[codec] ?: emptySet()) } }
            DecoderCaps(codec, hardware = true, maxFpsByLines = fpsByLines, hdr = hdr)
        }
    }

    private fun probeDisplay(): Pair<Int, Boolean> {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ?: return 1080 to false
        val lines = display.supportedModes.maxOfOrNull { minOf(it.physicalWidth, it.physicalHeight) } ?: 1080
        @Suppress("DEPRECATION")
        val hdr = display.isHdr || (display.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true)
        return lines to hdr
    }

    /**
     * Downloads the start of a real 4K stream for a few seconds, over the same request shape
     * the player uses, and reports the sustained rate. Null when nothing could be fetched.
     */
    private suspend fun measureThroughput(profile: DeviceProfile): Int? = withContext(Dispatchers.IO) {
        val plan = sources.youtube.resolvePlayback(PROBE_VIDEO, profile, PlaybackPrefs(QualityCap.BEST))
        val url = (plan as? PlaybackPlan.Adaptive)?.video?.url ?: return@withContext null
        var bytes = 0L
        var chunk = 0L
        val started = System.nanoTime()
        while (System.nanoTime() - started < PROBE_NANOS && bytes < PROBE_BYTES) {
            val shaped = StreamRequests.shape(url, bytes, CHUNK, chunk++)
            val req = Request.Builder().url(shaped.url)
                .apply { shaped.headers.forEach { (k, v) -> header(k, v) } }
                .post(shaped.body.toRequestBody())
                .build()
            val got = okHttp.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use -1L
                val src = r.body.source()
                val sink = Buffer()
                var n = 0L
                while (true) {
                    val k = src.read(sink, 64 * 1024)
                    if (k < 0) break
                    n += k
                    sink.clear()
                }
                n
            }
            if (got <= 0) break
            bytes += got
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        if (bytes == 0L || ms == 0L) null else (bytes * 8 / ms).toInt()
    }

    companion object {
        /** "Costa Rica in 4K 60fps HDR", long-lived and served in every codec. */
        private const val PROBE_VIDEO = "LXb3EKWsInQ"
        private const val PROBE_NANOS = 5_000_000_000L
        private const val PROBE_BYTES = 40L * 1024 * 1024
        private const val CHUNK = 4L * 1024 * 1024

        private val MIME = listOf(
            VideoCodec.AV1 to "video/av01",
            VideoCodec.VP9 to "video/x-vnd.on2.vp9",
            VideoCodec.HEVC to "video/hevc",
            VideoCodec.H264 to "video/avc",
        )
        private val LADDER = listOf(854 to 480, 1280 to 720, 1920 to 1080, 2560 to 1440, 3840 to 2160)
        private val HDR_PROFILES = mapOf(
            VideoCodec.AV1 to setOf(CodecProfileLevel.AV1ProfileMain10HDR10, CodecProfileLevel.AV1ProfileMain10HDR10Plus),
            VideoCodec.VP9 to setOf(CodecProfileLevel.VP9Profile2HDR, CodecProfileLevel.VP9Profile2HDR10Plus),
            VideoCodec.HEVC to setOf(CodecProfileLevel.HEVCProfileMain10HDR10, CodecProfileLevel.HEVCProfileMain10HDR10Plus),
        )

        fun isTv(context: Context): Boolean {
            val ui = context.getSystemService(UiModeManager::class.java)
            return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                context.packageManager.hasSystemFeature("android.software.leanback")
        }
    }
}
