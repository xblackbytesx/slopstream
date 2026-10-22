package com.xblackbytesx.slopstream.youtube

import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * How a media byte range has to be requested from googlevideo.com, as NewPipe does it
 * (`YoutubeHttpDataSource`): the range goes in a `&range=` query parameter rather than a Range
 * header, each request gets an increasing `&rn=`, the body is POSTed as `x\0`, and the user
 * agent and Origin match the client the stream URL was issued to. Plain data so the app's
 * Media3 data source and the smoke test share it.
 */
object StreamRequests {

    data class Shaped(
        val url: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private val POST_BODY = byteArrayOf(0x78, 0)
    private const val ORIGIN = "https://www.youtube.com"

    fun isVideoPlayback(url: String): Boolean = "/videoplayback" in url

    /**
     * @param length bytes wanted, or a negative value for "to the end".
     */
    fun shape(url: String, position: Long, length: Long, requestNumber: Long): Shaped {
        val sb = StringBuilder(url)
        if ("&rn=" !in url && "?rn=" !in url) sb.append("&rn=").append(requestNumber)
        if (position > 0 || length >= 0) {
            sb.append("&range=").append(position).append('-')
            if (length >= 0) sb.append(position + length - 1)
        }

        val headers = LinkedHashMap<String, String>()
        val ua = when {
            YoutubeParsingHelper.isAndroidStreamingUrl(url) -> YoutubeParsingHelper.getAndroidUserAgent(null)
            YoutubeParsingHelper.isIosStreamingUrl(url) -> YoutubeParsingHelper.getIosUserAgent(null)
            YoutubeParsingHelper.isVisionOsStreamingUrl(url) -> YoutubeParsingHelper.getVisionOsUserAgent(null)
            else -> OkHttpDownloader.USER_AGENT
        }
        headers["User-Agent"] = ua
        if (YoutubeParsingHelper.isWebStreamingUrl(url) || YoutubeParsingHelper.isWebEmbeddedPlayerStreamingUrl(url)) {
            headers["Origin"] = ORIGIN
            headers["Referer"] = ORIGIN
            headers["Sec-Fetch-Dest"] = "empty"
            headers["Sec-Fetch-Mode"] = "cors"
            headers["Sec-Fetch-Site"] = "cross-site"
        }
        headers["Accept-Encoding"] = "identity"
        return Shaped(sb.toString(), headers, POST_BODY)
    }
}
