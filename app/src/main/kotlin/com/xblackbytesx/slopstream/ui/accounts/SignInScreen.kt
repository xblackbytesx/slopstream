package com.xblackbytesx.slopstream.ui.accounts

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.xblackbytesx.slopstream.core.SourceAuthException
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.data.SiteSignIn
import com.xblackbytesx.slopstream.data.importer.LanImportServer
import com.xblackbytesx.slopstream.floatplane.FloatplaneSite
import com.xblackbytesx.slopstream.ui.components.ListRow
import com.xblackbytesx.slopstream.ui.components.PrimaryButton
import com.xblackbytesx.slopstream.ui.components.ScreenHeader
import com.xblackbytesx.slopstream.ui.components.SecondaryButton
import com.xblackbytesx.slopstream.ui.nav.SignInRoute
import com.xblackbytesx.slopstream.ui.theme.LocalDims
import com.xblackbytesx.slopstream.ui.theme.LocalIsTv
import com.xblackbytesx.slopstream.ui.theme.Palette
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

enum class SignInMode { CHOOSE, WEB, OTHER_DEVICE }

data class SignInState(
    val mode: SignInMode = SignInMode.CHOOSE,
    val busy: Boolean = false,
    val error: String? = null,
    val lanAddress: String? = null,
    /** Set once signed in. */
    val username: String? = null,
)

/**
 * Sign-in to a Floatplane-platform site (Floatplane, Sauce+) until Slopstream has its own OAuth
 * client id: on the site's own login page in a web view, or with the website session pasted
 * from a computer that is signed in.
 */
@HiltViewModel
class SignInViewModel @Inject constructor(
    @ApplicationContext context: Context,
    savedState: SavedStateHandle,
    private val signIn: SiteSignIn,
) : ViewModel() {
    val key: SourceKey = SourceKey.fromId(savedState.toRoute<SignInRoute>().source)
    val site: FloatplaneSite = signIn.site(key)

    private val _state = MutableStateFlow(SignInState())
    val state: StateFlow<SignInState> = _state.asStateFlow()
    private val attempt = Mutex()

    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = LanImportServer(context, pastePage(site)) { bytes ->
        when (val r = submit(bytes.decodeToString())) {
            null -> "Signed in. You can close this page."
            else -> r
        }
    }

    fun choose(mode: SignInMode) {
        _state.update { it.copy(mode = mode, error = null) }
        if (mode == SignInMode.OTHER_DEVICE && _state.value.lanAddress == null) {
            val address = runCatching { server.start(serverScope) }.getOrNull()
            _state.update { it.copy(lanAddress = address, error = if (address == null) "No network address for the sign-in page" else null) }
        }
    }

    /**
     * The web view's session after a page load. Before the login it is an anonymous session,
     * which the site refuses quietly; this keeps trying until one is signed in.
     */
    fun tryWebSession(sid: String) {
        if (_state.value.username != null || attempt.isLocked) return
        viewModelScope.launch {
            attempt.withLock {
                if (_state.value.username != null) return@withLock
                try {
                    val name = signIn.signIn(key, sid)
                    _state.update { it.copy(username = name, error = null) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SourceAuthException) {
                    // Not signed in yet: wait for the next page.
                } catch (e: Exception) {
                    _state.update { it.copy(error = e.message ?: "Could not reach ${site.name}") }
                }
            }
        }
    }

    /** A pasted session. Returns null when signed in, else what went wrong. */
    suspend fun submit(text: String): String? = attempt.withLock {
        _state.update { it.copy(busy = true, error = null) }
        val error = try {
            val name = signIn.signIn(key, text)
            _state.update { it.copy(username = name) }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: SourceAuthException) {
            "${site.name} does not accept that session: it is signed out or expired. Sign in on ${site.host} and copy it again."
        } catch (e: IllegalArgumentException) {
            e.message
        } catch (e: Exception) {
            e.message ?: "Could not reach ${site.name}"
        }
        _state.update { it.copy(busy = false, error = error) }
        error
    }

    fun paste(text: String) {
        viewModelScope.launch { submit(text) }
    }

    override fun onCleared() {
        server.stop()
        serverScope.cancel()
    }

    private companion object {
        fun pastePage(site: FloatplaneSite) = """
            <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Slopstream: ${site.name} sign-in</title>
            <style>${LanImportServer.PAGE_STYLE}</style></head><body>
            <h1>Sign in to ${site.name} on Slopstream</h1>
            <div class="card"><ol>
              <li>On this computer, sign in at <code>${site.host}</code>.</li>
              <li>Open the developer tools (F12): Storage (Firefox) or Application (Chrome), then Cookies, then <code>${site.baseUrl}</code>.</li>
              <li>Copy the value of <code>${site.sessionCookie}</code> and paste it below.</li>
            </ol>
            <input type="text" id="sid" placeholder="${site.sessionCookie} value" autocomplete="off"><br>
            <button onclick="send()">Sign in</button></div>
            <p>The session goes straight to the TV on your own network and is stored there encrypted. Signing out in Slopstream ends it on ${site.name} too.</p>
            <p id="status"></p>
            <script>
              async function send(){const v=document.getElementById('sid').value.trim();if(!v)return;
                const s=document.getElementById('status');s.textContent='Checking with ${site.name}…';
                try{const r=await fetch('/upload',{method:'POST',body:new Blob([v])});s.textContent=await r.text();}catch(e){s.textContent='Failed: '+e;}}
            </script></body></html>
        """.trimIndent()
    }
}

@Composable
fun SignInScreen(onDone: () -> Unit, vm: SignInViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val dims = LocalDims.current
    val isTv = LocalIsTv.current
    val site = vm.site

    LaunchedEffect(state.username) {
        if (state.username != null) {
            delay(1_200)
            onDone()
        }
    }

    if (state.mode == SignInMode.WEB && state.username == null) {
        BackHandler { vm.choose(SignInMode.CHOOSE) }
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Sign in to ${site.name}", status = "On ${site.name}'s own page. Slopstream continues once you are signed in.")
            state.error?.let { Text(it, color = Palette.Live, modifier = Modifier.padding(horizontal = dims.gutter)) }
            SiteLoginView(site, onSession = vm::tryWebSession, modifier = Modifier.fillMaxSize())
        }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = dims.gutter).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader("Sign in to ${site.name}", modifier = Modifier.padding(start = 0.dp))
        state.username?.let { name ->
            Text("Signed in as $name.", style = MaterialTheme.typography.titleMedium, color = Palette.Accent)
            return@Column
        }
        Text(
            "Slopstream plays what your ${site.name} membership includes, nothing more. You sign in on ${site.name}'s own page; " +
                "Slopstream keeps only the resulting session, encrypted on this device.",
            color = Palette.TextDim,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.widthIn(max = 720.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Sign in here", { vm.choose(SignInMode.WEB) }, icon = Icons.AutoMirrored.Filled.Login)
            if (state.lanAddress == null) SecondaryButton("Use another device", { vm.choose(SignInMode.OTHER_DEVICE) }, icon = Icons.Filled.Wifi)
        }
        if (isTv && state.mode == SignInMode.CHOOSE) {
            Text("Typing a password with the remote is slow; another device is quicker.", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
        }
        state.lanAddress?.let { address ->
            ListRow {
                Column {
                    Text("On a computer signed in to ${site.host}, in the same network, open", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
                    Text(address, style = MaterialTheme.typography.headlineSmall, color = Palette.Accent)
                    Text("It explains where to copy the session from. Keep this screen open.", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!isTv) PasteField(site.sessionCookie, busy = state.busy, onSubmit = vm::paste)
        if (state.busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Palette.Accent, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Checking with ${site.name}…")
            }
        }
        state.error?.let { Text(it, color = Palette.Live, modifier = Modifier.widthIn(max = 720.dp)) }
    }
}

/** For a session copied on this phone: the session cookie's value. */
@Composable
private fun PasteField(cookie: String, busy: Boolean, onSubmit: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Or paste the $cookie value from a signed-in browser", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            placeholder = { Text(cookie) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Accent),
            modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
        )
        SecondaryButton("Sign in with it", { onSubmit(text) }, icon = Icons.Filled.ContentPaste, enabled = text.isNotBlank() && !busy)
    }
}

/**
 * The site's login page. After every page load, and every few seconds while it is open (the
 * site signs in without navigating), its session cookie is handed to [onSession] to try. The
 * cookie is removed from the web view when the screen closes: the encrypted store keeps the
 * only copy.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SiteLoginView(site: FloatplaneSite, onSession: (String) -> Unit, modifier: Modifier = Modifier) {
    val cookies = remember { CookieManager.getInstance() }
    fun current(): String? = cookies.getCookie(site.baseUrl)
        ?.split(';')?.map { it.trim() }
        ?.firstOrNull { it.startsWith(site.sessionCookie + "=") }
        ?.substringAfter('=')
        ?.takeIf { it.isNotBlank() }

    LaunchedEffect(Unit) {
        while (true) {
            delay(4_000)
            current()?.let(onSession)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            // A __Host- cookie is only replaced by one that is Secure with Path=/.
            cookies.setCookie(site.baseUrl, "${site.sessionCookie}=; Max-Age=0; Path=/; Secure")
            cookies.flush()
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        current()?.let(onSession)
                    }
                }
                loadUrl("${site.baseUrl}/login")
                requestFocus()
            }
        },
        onRelease = { it.destroy() },
    )
}
