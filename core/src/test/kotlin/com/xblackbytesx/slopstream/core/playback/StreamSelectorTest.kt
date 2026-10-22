package com.xblackbytesx.slopstream.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamSelectorTest {

    private val ladder = listOf(480, 720, 1080, 1440, 2160)

    private fun caps(codec: VideoCodec, maxLines: Int, fps: Int = 60, hdr: Boolean = false) =
        DecoderCaps(codec, hardware = true, maxFpsByLines = ladder.associateWith { if (it <= maxLines) fps else 0 }, hdr = hdr)

    /** Shield TV 2019: hardware VP9 to 4K60 (HDR), H.264 to 4K, no AV1. */
    private val shield = DeviceProfile(
        decoders = listOf(caps(VideoCodec.VP9, 2160, hdr = true), caps(VideoCodec.H264, 2160), caps(VideoCodec.HEVC, 2160, hdr = true)),
        hdrDisplay = true,
        displayLines = 2160,
        throughputKbps = null,
        matchFrameRate = true,
    )

    /** Google TV Streamer: AV1 and VP9 in hardware. */
    private val streamer = shield.copy(decoders = shield.decoders + caps(VideoCodec.AV1, 2160, hdr = true))

    /** A mid phone: AV1 to 1080p only, VP9 to 1440p. */
    private val phone = DeviceProfile(
        decoders = listOf(caps(VideoCodec.AV1, 1080), caps(VideoCodec.VP9, 1440), caps(VideoCodec.H264, 1080)),
        hdrDisplay = false,
        displayLines = 1080,
        throughputKbps = null,
        matchFrameRate = false,
    )

    private fun v(id: String, codecs: String, lines: Int, fps: Int = 30, hdr: Boolean = false, bitrate: Int = lines * 1000) =
        VideoCandidate(id, codecs, width = lines * 16 / 9, height = lines, fps = fps, bitrate = bitrate, hdr = hdr)

    private val youtube4k = listOf(
        v("137", "avc1.640028", 1080),
        v("248", "vp09.00.40.08", 1080),
        v("399", "av01.0.08M.08", 1080),
        v("271", "vp09.00.50.08", 1440),
        v("400", "av01.0.12M.08", 1440),
        v("313", "vp09.00.51.08", 2160),
        v("401", "av01.0.12M.08", 2160),
        v("136", "avc1.4d401f", 720),
    )

    @Test
    fun shieldGetsVp9At4k() {
        assertEquals("313", StreamSelector.selectVideo(youtube4k, shield, PlaybackPrefs())?.id)
    }

    @Test
    fun streamerPrefersAv1AtTheSameResolution() {
        assertEquals("401", StreamSelector.selectVideo(youtube4k, streamer, PlaybackPrefs())?.id)
    }

    @Test
    fun capLimitsResolution() {
        assertEquals("400", StreamSelector.selectVideo(youtube4k, streamer, PlaybackPrefs(cap = QualityCap.P1440))?.id)
        assertEquals("248", StreamSelector.selectVideo(youtube4k, shield, PlaybackPrefs(cap = QualityCap.P1080))?.id)
    }

    @Test
    fun decoderLimitsPickTheBestCodecPerResolution() {
        // AV1 stops at 1080p on this phone, VP9 goes to 1440p: 1440p VP9 beats 1080p AV1.
        assertEquals("271", StreamSelector.selectVideo(youtube4k, phone, PlaybackPrefs())?.id)
    }

    @Test
    fun higherFrameRateWinsAtTheSameResolution() {
        val list = listOf(v("a", "vp09.00.40.08", 1080, fps = 30), v("b", "vp09.00.40.08", 1080, fps = 60))
        assertEquals("b", StreamSelector.selectVideo(list, shield, PlaybackPrefs())?.id)
    }

    @Test
    fun hdrNeedsDisplayDecoderAndPermission() {
        val list = listOf(v("sdr", "vp09.00.51.08", 2160), v("hdr", "vp09.02.51.10", 2160, hdr = true))
        assertEquals("hdr", StreamSelector.selectVideo(list, shield, PlaybackPrefs())?.id)
        assertEquals("sdr", StreamSelector.selectVideo(list, shield, PlaybackPrefs(allowHdr = false))?.id)
        assertEquals("sdr", StreamSelector.selectVideo(list, shield.copy(hdrDisplay = false), PlaybackPrefs())?.id)
    }

    @Test
    fun verticalShortsUseTheShortSideForTheCap() {
        val short = VideoCandidate("s", "vp09.00.40.08", width = 1080, height = 1920, fps = 30, bitrate = 1, hdr = false)
        assertEquals("s", StreamSelector.selectVideo(listOf(short), shield, PlaybackPrefs(cap = QualityCap.P1080))?.id)
    }

    @Test
    fun capBelowEveryStreamPlaysTheSmallest() {
        val list = listOf(v("1080", "vp09.00.40.08", 1080), v("1440", "vp09.00.50.08", 1440))
        assertEquals("1080", StreamSelector.selectVideo(list, shield, PlaybackPrefs(cap = QualityCap.P720))?.id)
    }

    @Test
    fun noHardwareMatchFallsBackToSmallestH264() {
        val list = listOf(v("av1", "av01.0.08M.08", 1080), v("h264-720", "avc1.4d401f", 720), v("h264-360", "avc1.42001e", 360))
        val vp9Only = DeviceProfile(listOf(caps(VideoCodec.VP9, 1080)), false, 1080, null, false)
        assertEquals("h264-360", StreamSelector.selectVideo(list, vp9Only, PlaybackPrefs())?.id)
        assertNull(StreamSelector.selectVideo(emptyList(), vp9Only, PlaybackPrefs()))
    }

    @Test
    fun audioPrefersOriginalTrackThenBitrate() {
        val list = listOf(
            AudioCandidate("dub-opus", "opus", 160_000, AudioTrackRole.DUBBED, "de"),
            AudioCandidate("orig-aac", "mp4a.40.2", 128_000, AudioTrackRole.ORIGINAL, "en"),
            AudioCandidate("orig-opus", "opus", 135_000, AudioTrackRole.ORIGINAL, "en"),
            AudioCandidate("desc", "opus", 170_000, AudioTrackRole.DESCRIPTIVE, "en"),
        )
        assertEquals("orig-opus", StreamSelector.selectAudio(list)?.id)
    }

    @Test
    fun singleUnlabelledTrackIsUsed() {
        assertEquals("x", StreamSelector.selectAudio(listOf(AudioCandidate("x", "mp4a.40.2", 128_000, AudioTrackRole.UNKNOWN)))?.id)
    }

    @Test
    fun decoderCapsRoundUpToTheNextRung() {
        val c = caps(VideoCodec.VP9, 1080)
        assertEquals(true, c.supports(1920, 800, 24))
        assertEquals(false, c.supports(2560, 1440, 30))
        assertEquals(1080, c.maxLines)
    }

    @Test
    fun hdrIsReadFromTheTransferFunction() {
        assertEquals(true, VideoCodec.isHdr("vp09.02.51.10.01.09.16.09.00"))
        assertEquals(true, VideoCodec.isHdr("av01.0.13M.10.0.110.09.16.09.0"))
        assertEquals(true, VideoCodec.isHdr("av01.0.13M.10.0.110.09.18.09.0"))
        assertEquals(false, VideoCodec.isHdr("av01.0.13M.10.0.110.01.01.01.0"))
        assertEquals(true, VideoCodec.isHdr("vp09.02.51.10"))
        assertEquals(false, VideoCodec.isHdr("vp09.00.51.08"))
        assertEquals(false, VideoCodec.isHdr("avc1.640028"))
        assertEquals(VideoCodec.AV1, VideoCodec.fromCodecs("av01.0.08M.08"))
    }

    @Test
    fun recommendedCapFollowsThroughput() {
        assertEquals(QualityCap.BEST, QualityCap.recommendedFor(null))
        assertEquals(QualityCap.BEST, QualityCap.recommendedFor(50_000))
        assertEquals(QualityCap.P1440, QualityCap.recommendedFor(20_000))
        assertEquals(QualityCap.P1080, QualityCap.recommendedFor(9_000))
        assertEquals(QualityCap.P720, QualityCap.recommendedFor(3_000))
    }
}
