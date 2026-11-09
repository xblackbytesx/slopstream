package com.xblackbytesx.slopstream.youtube.innertube

import com.xblackbytesx.slopstream.core.playback.VideoCandidate

/**
 * A one-representation DASH manifest for a whole-file YouTube stream: the byte ranges of its
 * header and seek index, so the player seeks by range instead of reading from the start. The same
 * document NewPipeExtractor's `YoutubeProgressiveDashManifestCreator` writes, built as text.
 */
internal object DashManifest {
    fun progressive(format: PlayerResponse.Format, durationSec: Long, language: String? = null): String {
        val init = requireNotNull(format.initRange) { "no initRange" }
        val index = requireNotNull(format.indexRange) { "no indexRange" }
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="utf-8" standalone="no"?>""")
        sb.append("""<MPD xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns="urn:mpeg:DASH:schema:MPD:2011" """)
        sb.append("""xsi:schemaLocation="urn:mpeg:DASH:schema:MPD:2011 DASH-MPD.xsd" minBufferTime="PT1.500S" """)
        sb.append("""profiles="urn:mpeg:dash:profile:full:2011" type="static" mediaPresentationDuration="PT${durationSec}S">""")
        sb.append("<Period>")
        sb.append("""<AdaptationSet id="0"""")
        language?.let { sb.append(""" lang="${xml(it)}"""") }
        sb.append(""" mimeType="${xml(format.container)}" subsegmentAlignment="true">""")
        if (format.isAudio) sb.append("""<Role schemeIdUri="urn:mpeg:DASH:role:2011" value="main"/>""")
        sb.append("""<Representation id="${format.itag}" codecs="${xml(format.codecs)}" startWithSAP="1" maxPlayoutRate="1" bandwidth="${format.bitrate}"""")
        if (format.isVideo) {
            sb.append(""" width="${format.width}" height="${format.height}"""")
            if (format.fps > 0) sb.append(""" frameRate="${format.fps}"""")
        }
        sb.append(">")
        if (format.isAudio) {
            sb.append("""<AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="${format.audioChannels}"/>""")
        }
        sb.append("<BaseURL>").append(xml(format.url)).append("</BaseURL>")
        sb.append("""<SegmentBase indexRange="${index.first}-${index.last}">""")
        sb.append("""<Initialization range="${init.first}-${init.last}"/>""")
        sb.append("</SegmentBase></Representation></AdaptationSet></Period></MPD>")
        return sb.toString()
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

/**
 * The variants of an HLS master playlist (YouTube's are muxed: picture and sound together),
 * each its own playlist URL, as quality choices.
 */
internal object HlsMaster {
    data class Variant(val candidate: VideoCandidate, val url: String)

    fun parse(text: String): List<Variant> {
        val lines = text.lines()
        val out = ArrayList<Variant>()
        for ((i, line) in lines.withIndex()) {
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val url = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim() ?: continue
            val attrs = attributes(line.substringAfter(':'))
            val (w, h) = attrs["RESOLUTION"]?.split('x')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }
                ?.let { it[0] to it[1] } ?: continue
            val codecs = attrs["CODECS"].orEmpty()
            val range = attrs["VIDEO-RANGE"].orEmpty()
            out += Variant(
                VideoCandidate(
                    id = "hls${out.size}",
                    // Only the picture's codec decides; the muxed audio is AAC every device plays.
                    codecs = codecs.split(',').firstOrNull { !it.startsWith("mp4a") }.orEmpty(),
                    width = w,
                    height = h,
                    fps = attrs["FRAME-RATE"]?.toDoubleOrNull()?.let { Math.round(it).toInt() } ?: 30,
                    bitrate = attrs["BANDWIDTH"]?.toIntOrNull() ?: 0,
                    hdr = range == "PQ" || range == "HLG",
                ),
                url,
            )
        }
        return out
    }

    /** `KEY=VALUE,KEY="quoted, with commas"` */
    private fun attributes(s: String): Map<String, String> =
        Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""").findAll(s).associate { m ->
            m.groupValues[1] to m.groupValues[2].removeSurrounding("\"")
        }
}
