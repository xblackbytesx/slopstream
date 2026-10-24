package com.xblackbytesx.slopstream.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xblackbytesx.slopstream.data.sync.PairStarted
import com.xblackbytesx.slopstream.data.sync.Person
import com.xblackbytesx.slopstream.data.sync.SyncClient
import com.xblackbytesx.slopstream.data.sync.SyncConfig
import com.xblackbytesx.slopstream.data.sync.SyncEngine
import com.xblackbytesx.slopstream.data.sync.SyncException
import com.xblackbytesx.slopstream.data.sync.SyncScheduler
import com.xblackbytesx.slopstream.ui.components.ListRow
import com.xblackbytesx.slopstream.ui.components.PrimaryButton
import com.xblackbytesx.slopstream.ui.components.SecondaryButton
import com.xblackbytesx.slopstream.ui.theme.LocalIsTv
import com.xblackbytesx.slopstream.ui.theme.Palette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import javax.inject.Inject

/** Where connecting stands. */
sealed interface ConnectStep {
    data object Address : ConnectStep

    /** The address works; how to get this device a token. [plainHttp]: warn first. */
    data class Method(val server: HttpUrl, val plainHttp: Boolean) : ConnectStep

    data class Pairing(val server: HttpUrl, val code: PairStarted) : ConnectStep

    data class Done(val person: Person) : ConnectStep
}

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val engine: SyncEngine,
    private val scheduler: SyncScheduler,
    private val http: OkHttpClient,
) : ViewModel() {
    val config: StateFlow<SyncConfig?> = engine.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val syncing: StateFlow<Boolean> = engine.syncing

    private val _step = MutableStateFlow<ConnectStep>(ConnectStep.Address)
    val step: StateFlow<ConnectStep> = _step.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Replace this device's library with the server's (default: merge). */
    var replace by mutableStateOf(false)

    private var polling: Job? = null

    fun syncNow() {
        viewModelScope.launch { scheduler.syncNow() }
    }

    fun disconnect() {
        viewModelScope.launch { engine.disconnect() }
    }

    fun back() {
        polling?.cancel()
        _error.value = null
        _step.value = when (val s = _step.value) {
            is ConnectStep.Pairing -> ConnectStep.Method(s.server, SyncClient.needsWarning(s.server))
            else -> ConnectStep.Address
        }
    }

    /** Checks the address answers like a Slopstream server before going on. */
    fun checkAddress(input: String) = work {
        val url = SyncClient.parseServer(input)
            ?: throw SyncException("Enter the server's address, like slopstream.example.com (https), or a LAN address")
        // An unauthenticated /me answers 401 on a Slopstream server.
        val probe = runCatching { SyncClient(http, url).me() }.exceptionOrNull()
        if (probe !is com.xblackbytesx.slopstream.data.sync.SyncUnauthorizedException) {
            throw SyncException("No Slopstream server answers at $url" + (probe?.message?.let { ": $it" } ?: ""))
        }
        _step.value = ConnectStep.Method(url, SyncClient.needsWarning(url))
    }

    fun startPairing(server: HttpUrl) = work {
        val code = SyncClient(http, server).pairStart(deviceName())
        _step.value = ConnectStep.Pairing(server, code)
        polling?.cancel()
        polling = viewModelScope.launch { poll(server, code) }
    }

    private suspend fun poll(server: HttpUrl, code: PairStarted) {
        val client = SyncClient(http, server)
        val until = System.currentTimeMillis() + code.expiresInSec * 1000
        while (System.currentTimeMillis() < until) {
            delay(code.intervalSec.coerceAtLeast(2) * 1000)
            val r = runCatching { client.pairPoll(code.deviceCode) }.getOrNull() ?: continue
            when (r.status) {
                "approved" -> {
                    val token = r.token ?: break
                    finish(server, token, r.person ?: Person("", ""))
                    return
                }
                "denied" -> {
                    _error.value = "The pairing was declined on the website."
                    return
                }
                "expired" -> break
            }
        }
        _error.value = "The code expired. Start again for a new one."
    }

    fun useToken(server: HttpUrl, token: String) = work {
        val t = token.trim()
        if (!t.startsWith("ss_")) throw SyncException("That is not a Slopstream token (they start with ss_)")
        val person = SyncClient(http, server, t).me()
        finish(server, t, person)
    }

    private suspend fun finish(server: HttpUrl, token: String, person: Person) {
        engine.connect(server.toString(), token, person, replace)
        _step.value = ConnectStep.Done(person)
    }

    private fun work(block: suspend () -> Unit) {
        _error.value = null
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: SyncException) {
                _error.value = e.message
            } finally {
                _busy.value = false
            }
        }
    }

    private fun deviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }.distinct()
        .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        .ifBlank { "Slopstream device" }
}

/** The "Sync server" section of Settings: the state on top, its buttons under it. */
@Composable
fun SyncSection(config: SyncConfig, syncing: Boolean, onConnect: () -> Unit, onSyncNow: () -> Unit, onDisconnect: () -> Unit) {
    ListRow {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                if (!config.connected) {
                    Text("Not connected", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Keep subscriptions, Watch later, progress and sign-ins in step across your devices with your own Slopstream server.",
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
                    )
                } else {
                    val who = config.personName.ifBlank { config.personEmail }
                    Text("Connected as $who", style = MaterialTheme.typography.bodyLarge)
                    Text(config.server.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                    val status = when {
                        syncing -> "Syncing\u2026"
                        config.lastError != null -> config.lastError
                        config.lastSyncAt != null -> "Last synced ${ago(config.lastSyncAt)}"
                        else -> "Not synced yet"
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (config.lastError != null && !syncing) Palette.Live else Palette.TextDim,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!config.connected) {
                    PrimaryButton("Connect", onConnect)
                } else {
                    SecondaryButton("Sync now", onSyncNow, icon = Icons.Filled.Sync, enabled = !syncing)
                    SecondaryButton("Disconnect", onDisconnect)
                }
            }
        }
    }
}

private fun ago(at: Long): String {
    val min = (System.currentTimeMillis() - at) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 24 * 60 -> "${min / 60} h ago"
        else -> "${min / (24 * 60)} days ago"
    }
}

@Composable
fun SyncConnectScreen(onDone: () -> Unit, vm: SyncViewModel = hiltViewModel()) {
    val step by vm.step.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    ConnectContent(step, error, busy, vm.replace, { vm.replace = it }, vm::checkAddress, vm::startPairing, vm::useToken, vm::back, onDone)
}

/** Stateless, so the screenshot test renders each step. */
@Composable
fun ConnectContent(
    step: ConnectStep,
    error: String?,
    busy: Boolean,
    replace: Boolean,
    onReplace: (Boolean) -> Unit,
    onAddress: (String) -> Unit,
    onPair: (HttpUrl) -> Unit,
    onToken: (HttpUrl, String) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val isTv = LocalIsTv.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Connect to a sync server", style = MaterialTheme.typography.headlineSmall)
            when (step) {
                ConnectStep.Address -> {
                    var address by remember { mutableStateOf("") }
                    Text(
                        "Your own Slopstream server keeps this device in step with your others. Enter its address.",
                        color = Palette.TextDim,
                    )
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        singleLine = true,
                        placeholder = { Text("slopstream.example.com") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Continue", { onAddress(address) }, enabled = address.isNotBlank() && !busy)
                        SecondaryButton("Cancel", onDone)
                    }
                }
                is ConnectStep.Method -> {
                    if (step.plainHttp) {
                        Text(
                            "This address is plain http. Everything this device syncs, including your Floatplane and Sauce+ " +
                                "sign-ins, crosses your network unencrypted. Use it only on a network you trust.",
                            color = Palette.Live,
                        )
                    }
                    Text("This device's library", style = MaterialTheme.typography.titleSmall)
                    Choice("Merge it with the server's", "Everything here goes up; where both have a change, the newer one wins.", !replace) { onReplace(false) }
                    Choice("Replace it with the server's", "Subscriptions, Watch later and progress here are dropped for the server's.", replace) { onReplace(true) }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Pair with a code", { onPair(step.server) }, enabled = !busy)
                        SecondaryButton("Back", onBack)
                    }
                    if (!isTv) {
                        var token by remember { mutableStateOf("") }
                        Text("Or paste a token made on the server's Devices page:", color = Palette.TextDim)
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it },
                            singleLine = true,
                            placeholder = { Text("ss_…") },
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SecondaryButton("Use token", { onToken(step.server, token) }, enabled = token.isNotBlank() && !busy)
                    }
                }
                is ConnectStep.Pairing -> {
                    Text("On your phone or computer, open", color = Palette.TextDim)
                    Text(step.code.verificationUrl, style = MaterialTheme.typography.titleMedium)
                    Text("sign in, and enter this code:", color = Palette.TextDim)
                    Text(
                        step.code.userCode,
                        fontFamily = FontFamily.Monospace,
                        fontSize = if (isTv) 56.sp else 40.sp,
                        letterSpacing = 4.sp,
                        color = Palette.Accent,
                    )
                    if (error == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = Palette.Accent, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Waiting for you to approve it…", color = Palette.TextDim)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (error != null) PrimaryButton("New code", { onPair(step.server) }, enabled = !busy)
                        SecondaryButton("Back", onBack)
                    }
                }
                is ConnectStep.Done -> {
                    Text(
                        "Connected as ${step.person.name.ifBlank { step.person.email }}. The first sync starts now.",
                        color = Palette.Text,
                    )
                    PrimaryButton("Done", onDone)
                }
            }
            error?.let { Text(it, color = Palette.Live) }
        }
    }
}

@Composable
private fun Choice(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    ListRow(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                tint = if (selected) Palette.Accent else Palette.TextFaint, modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(body, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
        }
    }
}
