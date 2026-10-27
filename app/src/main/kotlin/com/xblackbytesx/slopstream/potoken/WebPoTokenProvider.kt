package com.xblackbytesx.slopstream.potoken

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * The extractor asks for web-client poTokens through this (blocking, from its IO thread). The
 * generator is created once, recreated when it expires or fails, and the streaming token is
 * minted exactly once per generator before any player token, as BotGuard expects.
 * Devices without a usable WebView simply get no tokens; the extractor then uses other clients.
 * Ported from NewPipe's PoTokenProviderImpl (Copyright (C) NewPipe contributors,
 * GPL-3.0-or-later), RxJava and Handler replaced by a coroutine Mutex.
 */
class WebPoTokenProvider(
    private val context: Context,
    private val http: OkHttpClient,
) : PoTokenProvider {

    private val lock = Mutex()
    private var generator: PoTokenWebView? = null
    private var visitorData: String? = null
    private var streamingPot: String? = null

    @Volatile
    private var webViewBroken = false

    private val webViewAvailable: Boolean by lazy {
        // CookieManager throws when the device has no (or a disabled) WebView package.
        runCatching { CookieManager.getInstance() }.isSuccess
    }

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        if (!webViewAvailable || webViewBroken) return null
        return try {
            runBlocking { withTimeout(TIMEOUT_MS) { token(videoId, forceRecreate = false) } }
        } catch (e: BadWebViewException) {
            Log.e(TAG, "WebView cannot run BotGuard; poTokens disabled", e)
            webViewBroken = true
            null
        } catch (e: Exception) {
            Log.w(TAG, "poToken failed for $videoId", e)
            null
        }
    }

    private suspend fun token(videoId: String, forceRecreate: Boolean): PoTokenResult {
        val (gen, visitor, streaming, fresh) = lock.withLock {
            val current = generator
            val recreate = current == null || forceRecreate || current.isExpired()
            if (recreate) {
                val info = InnertubeClientRequestInfo.ofWebClient().apply {
                    clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion()
                }
                val vd = withContext(Dispatchers.IO) {
                    YoutubeParsingHelper.getVisitorDataFromInnertube(
                        info,
                        NewPipe.getPreferredLocalization(),
                        NewPipe.getPreferredContentCountry(),
                        YoutubeParsingHelper.getYouTubeHeaders(),
                        YoutubeParsingHelper.YOUTUBEI_V1_URL,
                        null,
                        false,
                    )
                }
                current?.let { old -> withContext(Dispatchers.Main) { old.close() } }
                val created = PoTokenWebView.create(context, http)
                generator = created
                visitorData = vd
                streamingPot = created.generate(vd)
            }
            Quad(generator!!, visitorData!!, streamingPot!!, recreate)
        }

        val playerPot = try {
            gen.generate(videoId)
        } catch (e: BadWebViewException) {
            throw e
        } catch (e: Exception) {
            // The WebView may have lost its state in the background: rebuild once.
            if (fresh) throw e
            return token(videoId, forceRecreate = true)
        }
        return PoTokenResult(visitor, playerPot, streaming)
    }

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = null
    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null
    override fun getIosClientPoToken(videoId: String): PoTokenResult? = null

    private data class Quad(val gen: PoTokenWebView, val visitor: String, val streaming: String, val fresh: Boolean)

    private companion object {
        const val TAG = "WebPoTokenProvider"
        const val TIMEOUT_MS = 20_000L
    }
}
