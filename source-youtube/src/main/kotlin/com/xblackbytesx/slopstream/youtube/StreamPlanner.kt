package com.xblackbytesx.slopstream.youtube

import com.xblackbytesx.slopstream.core.DownloadOffer
import com.xblackbytesx.slopstream.core.DownloadStream
import com.xblackbytesx.slopstream.core.SourceUnavailableException
import com.xblackbytesx.slopstream.core.VideoKind
import com.xblackbytesx.slopstream.core.playback.AudioCandidate
import com.xblackbytesx.slopstream.core.playback.AudioTrackRole
import com.xblackbytesx.slopstream.core.playback.DeviceProfile
import com.xblackbytesx.slopstream.core.playback.ManifestType
import com.xblackbytesx.slopstream.core.playback.PlaybackPlan
import com.xblackbytesx.slopstream.core.playback.PlaybackPrefs
import com.xblackbytesx.slopstream.core.playback.StreamSelector
import com.xblackbytesx.slopstream.core.playback.Track
import com.xblackbytesx.slopstream.core.playback.VideoCandidate
import com.xblackbytesx.slopstream.core.playback.VideoCodec
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubePostLiveStreamDvrDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Turns the extractor's [StreamInfo] into a [PlaybackPlan]. YouTube serves video and audio as
 * separate itag streams; each becomes a one-representation DASH manifest (built offline by the
 * extractor's creators) so Media3 seeks by byte range instead of downloading from the start.
 */
internal class StreamPlanner {

    fun plan(info: StreamInfo, profile: DeviceProfile, prefs: PlaybackPrefs): PlaybackPlan {
        when (info.streamType) {
            StreamType.LIVE_STREAM, StreamType.AUDIO_LIVE_STREAM -> return livePlan(info)
            else -> Unit
        }

        val videos = adaptiveVideos(info)
        val candidates = videos.keys.toList()
        val chosen = StreamSelector.selectVideo(candidates, profile, prefs)
        val audio = StreamSelector.selectAudio(adaptiveAudios(info).keys.toList())
        if (chosen != null && audio != null) {
            val audioStream = adaptiveAudios(info).getValue(audio)
            val options = StreamSelector.playable(candidates, profile, prefs).ifEmpty { listOf(chosen) }
            return PlaybackPlan.Adaptive(
                videoId = info.id,
                title = info.name,
                durationSec = info.duration,
                video = track(info, videos.getValue(chosen)),
                audio = track(info, audioStream),
                chosen = chosen,
                videoOptions = options,
            )
        }

        // No adaptive pair: the muxed 360p stream every video has.
        val muxed = info.videoStreams
            .filter { !it.isVideoOnly() && it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .maxByOrNull { it.height }
            ?: throw SourceUnavailableException("No playable stream for ${info.id}")
        return PlaybackPlan.Progressive(info.id, info.name, info.duration, muxed.content)
    }

    fun switch(info: StreamInfo, plan: PlaybackPlan.Adaptive, candidateId: String): PlaybackPlan.Adaptive {
        val videos = adaptiveVideos(info)
        val (candidate, stream) = videos.entries.firstOrNull { it.key.id == candidateId }?.toPair()
            ?: throw SourceUnavailableException("Quality $candidateId is no longer offered")
        // The audio URL may have been refreshed with the new StreamInfo; rebuild it too.
        val audio = StreamSelector.selectAudio(adaptiveAudios(info).keys.toList())
            ?.let { adaptiveAudios(info).getValue(it) }
        return plan.copy(
            video = track(info, stream),
            audio = audio?.let { track(info, it) } ?: plan.audio,
            chosen = candidate,
        )
    }

    /**
     * Whole-file streams only (progressive, with a known layout): the ones a byte-range download
     * can fetch. Every resolution the device decodes, ignoring the quality cap: a download is
     * chosen per video, and the sheet shows each one's size.
     */
    fun downloadOffer(info: StreamInfo, profile: DeviceProfile, prefs: PlaybackPrefs): DownloadOffer {
        if (info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM) {
            throw SourceUnavailableException("A livestream can't be downloaded")
        }
        val videos = downloadableVideos(info)
        val options = StreamSelector.playable(videos.keys.toList(), profile, prefs)
        val audios = downloadableAudios(info)
        val audio = StreamSelector.selectAudio(audios.keys.toList())?.let(audios::getValue)
        if (options.isEmpty() || audio == null) throw SourceUnavailableException("No downloadable stream this device can play")
        return DownloadOffer(
            videoId = info.id,
            title = info.name,
            channelId = YoutubeIds.channelIdFrom(info.uploaderUrl.orEmpty()).orEmpty(),
            channelName = info.uploaderName.orEmpty(),
            durationSec = info.duration,
            thumbnailUrl = YoutubeIds.thumbnailFor(info.id, VideoKind.VIDEO),
            videos = options.map { c -> download(info, videos.getValue(c), c) },
            audio = download(info, audio, null),
        )
    }

    /** One stream of [downloadOffer] with a fresh URL: video by itag, audio by itag and track. */
    fun downloadStream(info: StreamInfo, streamId: String): DownloadStream {
        downloadableVideos(info).entries.firstOrNull { it.key.id == streamId }?.let { (c, s) -> return download(info, s, c) }
        downloadableAudios(info).entries.firstOrNull { it.key.id == streamId }?.let { (_, s) -> return download(info, s, null) }
        throw SourceUnavailableException("YouTube no longer offers this quality ($streamId)")
    }

    private fun downloadableVideos(info: StreamInfo) =
        adaptiveVideos(info).filterValues { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }

    private fun downloadableAudios(info: StreamInfo) =
        adaptiveAudios(info).filterValues { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }

    private fun download(info: StreamInfo, stream: Stream, video: VideoCandidate?): DownloadStream {
        val itag = requireNotNull(stream.itagItem)
        val exact = itag.contentLength.takeIf { it > 0 }
        val bitrate = itag.averageBitrate.takeIf { it > 0 } ?: itag.bitrate.takeIf { it > 0 }
        return DownloadStream(
            id = video?.id ?: (stream as AudioStream).toCandidate().id,
            url = stream.content,
            extension = stream.format?.suffix ?: if (video != null) "mp4" else "m4a",
            sizeBytes = exact ?: bitrate?.let { it.toLong() * info.duration / 8 }?.takeIf { it > 0 },
            sizeExact = exact != null,
            video = video,
        )
    }

    private fun livePlan(info: StreamInfo): PlaybackPlan.Manifest {
        val (url, type) = when {
            info.hlsUrl.isNotEmpty() -> info.hlsUrl to ManifestType.HLS
            info.dashMpdUrl.isNotEmpty() -> info.dashMpdUrl to ManifestType.DASH
            else -> throw SourceUnavailableException("This livestream has no playable manifest")
        }
        return PlaybackPlan.Manifest(info.id, info.name, info.duration, url, type, live = true)
    }

    private fun adaptiveVideos(info: StreamInfo): Map<VideoCandidate, VideoStream> =
        info.videoOnlyStreams
            .filter { it.itagItem != null && it.deliveryMethod in SUPPORTED_DELIVERY }
            .associateBy { it.toCandidate() }

    private fun adaptiveAudios(info: StreamInfo): Map<AudioCandidate, AudioStream> =
        info.audioStreams
            .filter { it.itagItem != null && it.deliveryMethod in SUPPORTED_DELIVERY }
            .associateBy { it.toCandidate() }

    private fun track(info: StreamInfo, stream: Stream): Track {
        val itag = requireNotNull(stream.itagItem)
        val manifest = when {
            info.streamType == StreamType.POST_LIVE_STREAM ->
                YoutubePostLiveStreamDvrDashManifestCreator.fromPostLiveStreamDvrStreamingUrl(
                    stream.content, itag, itag.targetDurationSec, info.duration,
                )
            stream.deliveryMethod == DeliveryMethod.DASH ->
                YoutubeOtfDashManifestCreator.fromOtfStreamingUrl(stream.content, itag, info.duration)
            else ->
                YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(stream.content, itag, info.duration)
        }
        val codecs = when (stream) {
            is VideoStream -> stream.codec
            is AudioStream -> stream.codec
            else -> null
        }.orEmpty()
        return Track(
            url = stream.content,
            mimeType = stream.format?.mimeType.orEmpty(),
            codecs = codecs,
            dashManifest = manifest,
            cacheKey = "yt:${info.id}:${stream.id}",
        )
    }

    private fun VideoStream.toCandidate(): VideoCandidate {
        val (w, h) = if (width > 0 && height > 0) width to height else {
            val lines = RESOLUTION.find(getResolution())?.groupValues?.get(1)?.toIntOrNull() ?: 0
            lines * 16 / 9 to lines
        }
        val rate = fps.takeIf { it > 0 } ?: RESOLUTION.find(getResolution())?.groupValues?.get(2)?.toIntOrNull() ?: 30
        return VideoCandidate(
            id = itag.toString(),
            codecs = codec.orEmpty(),
            width = w,
            height = h,
            fps = rate,
            bitrate = bitrate,
            hdr = VideoCodec.isHdr(codec) || "HDR" in getResolution().uppercase(),
        )
    }

    private fun AudioStream.toCandidate() = AudioCandidate(
        id = listOfNotNull(itag.toString(), audioTrackId).joinToString(":"),
        codecs = codec.orEmpty(),
        bitrate = averageBitrate.takeIf { it > 0 } ?: bitrate,
        role = when (audioTrackType) {
            AudioTrackType.ORIGINAL -> AudioTrackRole.ORIGINAL
            AudioTrackType.DUBBED -> AudioTrackRole.DUBBED
            AudioTrackType.DESCRIPTIVE -> AudioTrackRole.DESCRIPTIVE
            null -> AudioTrackRole.UNKNOWN
            else -> AudioTrackRole.SECONDARY
        },
        language = audioLocale?.language,
    )

    private companion object {
        val SUPPORTED_DELIVERY = setOf(DeliveryMethod.PROGRESSIVE_HTTP, DeliveryMethod.DASH)
        val RESOLUTION = Regex("(\\d{3,4})p(\\d{2,3})?")
    }
}
