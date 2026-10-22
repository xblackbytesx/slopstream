package com.xblackbytesx.slopstream.potoken

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.annotation.MainThread
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.xblackbytesx.slopstream.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs YouTube's BotGuard in a hidden, network-blocked WebView to mint proof-of-origin tokens.
 * Ported from NewPipe's PoTokenWebView (GPL-3.0), RxJava replaced by coroutines and the
 * BotGuard requests sent through the app's OkHttp client.
 *
 * Lifecycle: [create] loads the page, fetches the challenge, runs it and obtains an integrity
 * token; [generate] can then be called many times until [isExpired].
 */
class PoTokenWebView private constructor(
    context: Context,
    private val http: OkHttpClient,
) : Closeable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val webView = WebView(context)
    private val ready = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    @Volatile
    private var expiresAt: Instant = Instant.MIN

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure() {
        webView.settings.apply {
            javaScriptEnabled = true
            userAgentString = USER_AGENT
            // BotGuard's own requests go through OkHttp; the page itself needs no network.
            blockNetworkLoads = true
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(webView.settings, false)
        }
        webView.addJavascriptInterface(this, JS_INTERFACE)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if ("Uncaught" in m.message()) {
                    // Everything that can fail is wrapped in try/catch, so an uncaught error means
                    // the WebView cannot even parse the script.
                    val e = BadWebViewException("\"${m.message()}\", source: ${m.sourceId()} (${m.lineNumber()})")
                    failAll(e)
                }
                return super.onConsoleMessage(m)
            }
        }
    }

    private suspend fun load(context: Context) {
        val html = withContext(Dispatchers.IO) { context.assets.open("po_token.html").bufferedReader().use { it.readText() } }
        webView.loadDataWithBaseURL(
            "https://www.youtube.com",
            html.replaceFirst("</script>", "\n$JS_INTERFACE.downloadAndRunBotguard()</script>"),
            "text/html",
            "utf-8",
            null,
        )
        ready.await()
    }

    @JavascriptInterface
    fun downloadAndRunBotguard() {
        scope.launch {
            try {
                val body = botguardRequest("https://www.youtube.com/api/jnn/v1/Create", "[ \"$REQUEST_KEY\" ]")
                val challenge = parseChallengeData(body)
                webView.evaluateJavascript(
                    """try {
                        data = $challenge
                        runBotGuard(data).then(function (result) {
                            this.webPoSignalOutput = result.webPoSignalOutput
                            $JS_INTERFACE.onRunBotguardResult(result.botguardResponse)
                        }, function (error) {
                            $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                        })
                    } catch (error) {
                        $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                    }""",
                    null,
                )
            } catch (e: Exception) {
                failAll(e)
            }
        }
    }

    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        if (BuildConfig.DEBUG) Log.e(TAG, "BotGuard init error: $error")
        failAll(exceptionForJsError(error))
    }

    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        scope.launch {
            try {
                val body = botguardRequest(
                    "https://www.youtube.com/api/jnn/v1/GenerateIT",
                    "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]",
                )
                val (integrityToken, lifetimeSec) = parseIntegrityTokenData(body)
                // Ten minutes of margin, as NewPipe does.
                expiresAt = Instant.now().plusSeconds(lifetimeSec - 600)
                webView.evaluateJavascript("this.integrityToken = $integrityToken") { ready.complete(Unit) }
            } catch (e: Exception) {
                failAll(e)
            }
        }
    }

    /** A poToken bound to [identifier] (a video id, or the visitor data for the streaming token). */
    suspend fun generate(identifier: String): String {
        val result = CompletableDeferred<String>()
        pending[identifier] = result
        withContext(Dispatchers.Main.immediate) {
            webView.evaluateJavascript(
                """try {
                    identifier = "$identifier"
                    u8Identifier = ${stringToU8(identifier)}
                    poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier)
                    poTokenU8String = ""
                    for (i = 0; i < poTokenU8.length; i++) {
                        if (i != 0) poTokenU8String += ","
                        poTokenU8String += poTokenU8[i]
                    }
                    $JS_INTERFACE.onObtainPoTokenResult(identifier, poTokenU8String)
                } catch (error) {
                    $JS_INTERFACE.onObtainPoTokenError(identifier, error + "\n" + error.stack)
                }""",
                null,
            )
        }
        return result.await()
    }

    @JavascriptInterface
    fun onObtainPoTokenError(identifier: String, error: String) {
        pending.remove(identifier)?.completeExceptionally(exceptionForJsError(error))
    }

    @JavascriptInterface
    fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
        val d = pending.remove(identifier) ?: return
        runCatching { u8ToBase64(poTokenU8) }
            .onSuccess { d.complete(it) }
            .onFailure { d.completeExceptionally(it) }
    }

    fun isExpired(): Boolean = Instant.now().isAfter(expiresAt)

    private fun failAll(e: Throwable) {
        ready.completeExceptionally(e)
        pending.keys.toList().forEach { pending.remove(it)?.completeExceptionally(e) }
    }

    private suspend fun botguardRequest(url: String, data: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("x-goog-api-key", GOOGLE_API_KEY)
            .header("x-user-agent", "grpc-web-javascript/0.1")
            .post(data.toRequestBody("application/json+protobuf".toMediaType()))
            .build()
        http.newCall(request).execute().use { r ->
            if (r.code != 200) throw PoTokenException("BotGuard request failed: HTTP ${r.code}")
            r.body.string()
        }
    }

    @MainThread
    override fun close() {
        scope.cancel()
        failAll(PoTokenException("closed"))
        webView.clearHistory()
        webView.clearCache(true)
        webView.loadUrl("about:blank")
        webView.onPause()
        webView.removeAllViews()
        webView.destroy()
    }

    companion object {
        private const val TAG = "PoTokenWebView"

        // Public BotGuard API key and request key, as observed in YouTube's own requests.
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
        private const val JS_INTERFACE = "PoTokenWebView"

        /** Builds and initialises a generator. Must not be called on the main thread's behalf. */
        suspend fun create(context: Context, http: OkHttpClient): PoTokenWebView = withContext(Dispatchers.Main.immediate) {
            val wv = PoTokenWebView(context, http)
            wv.configure()
            try {
                wv.load(context)
                wv
            } catch (e: Throwable) {
                wv.close()
                throw e
            }
        }
    }
}
