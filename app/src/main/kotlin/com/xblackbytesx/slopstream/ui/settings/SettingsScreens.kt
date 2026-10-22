package com.xblackbytesx.slopstream.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xblackbytesx.slopstream.BuildConfig
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.core.playback.DeviceProfile
import com.xblackbytesx.slopstream.core.playback.QualityCap
import com.xblackbytesx.slopstream.data.Account
import com.xblackbytesx.slopstream.data.AccountStatus
import com.xblackbytesx.slopstream.data.Accounts
import com.xblackbytesx.slopstream.data.AppSettings
import com.xblackbytesx.slopstream.data.FloatplaneAccount
import com.xblackbytesx.slopstream.data.Settings
import com.xblackbytesx.slopstream.device.DeviceCheck
import com.xblackbytesx.slopstream.device.DeviceCheckResult
import com.xblackbytesx.slopstream.playback.PlaybackRepository
import com.xblackbytesx.slopstream.ui.components.ListRow
import com.xblackbytesx.slopstream.ui.components.PrimaryButton
import com.xblackbytesx.slopstream.ui.components.ScreenHeader
import com.xblackbytesx.slopstream.ui.components.SecondaryButton
import com.xblackbytesx.slopstream.ui.theme.LocalDims
import com.xblackbytesx.slopstream.ui.theme.LocalIsTv
import com.xblackbytesx.slopstream.ui.theme.Palette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: Settings,
    private val playback: PlaybackRepository,
    accounts: Accounts,
    private val floatplane: FloatplaneAccount,
) : ViewModel() {
    val state: StateFlow<AppSettings?> = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val accounts: StateFlow<Map<SourceKey, Account>> = accounts.state

    fun signOutFloatplane() {
        viewModelScope.launch { floatplane.signOut() }
    }

    private fun edit(block: suspend Settings.() -> Unit) {
        viewModelScope.launch { settings.block(); playback.invalidate() }
    }

    fun setCap(c: QualityCap) = edit { setCap(c) }
    fun setHdr(on: Boolean) = edit { setAllowHdr(on) }
    fun setLive(on: Boolean) = edit { setShowLive(on) }
    fun setMatchFrameRate(on: Boolean) = edit { setMatchFrameRate(on) }
    fun setRefresh(hours: Int) = edit { setRefreshHours(hours) }
}

@Composable
fun SettingsScreen(runDeviceCheck: () -> Unit, signIn: (SourceKey) -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }
    val settings = s ?: return
    val dims = LocalDims.current
    val isTv = LocalIsTv.current

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = dims.gutter, end = dims.gutter, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { ScreenHeader("Settings", modifier = Modifier.padding(start = 0.dp)) }

        item { Section("Playback") }
        item {
            Text("Maximum quality", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            Text(
                "Slopstream always plays the best stream this device decodes in hardware, up to this limit." +
                    (settings.recommendedCap?.let { " The device check suggests ${capLabel(it)}." } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
            )
        }
        QualityCap.entries.forEach { cap ->
            item(key = cap.name) {
                ChoiceRow(capLabel(cap), selected = settings.cap == cap) { vm.setCap(cap) }
            }
        }
        item { ToggleRow("HDR", "Play HDR versions when the display and decoder support them.", settings.allowHdr, vm::setHdr) }
        if (isTv) {
            item { ToggleRow("Match frame rate", "Switch the TV's refresh rate to the video's (24, 25, 30, 50, 60 Hz) for judder-free motion.", settings.matchFrameRate, vm::setMatchFrameRate) }
        }

        item { Section("Feed") }
        item { ToggleRow("Livestreams and premieres", "Show them in Videos, mixed in by date. Shorts always have their own screen.", settings.showLiveInFeed, vm::setLive) }
        item {
            Text("Background refresh", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
        }
        listOf(0 to "Only when the app opens", 1 to "Every hour", 3 to "Every 3 hours", 6 to "Every 6 hours", 12 to "Every 12 hours").forEach { (h, label) ->
            item(key = "refresh$h") { ChoiceRow(label, selected = settings.refreshHours == h) { vm.setRefresh(h) } }
        }

        item { Section("Accounts") }
        item {
            AccountRow(
                SourceKey.FLOATPLANE,
                accounts[SourceKey.FLOATPLANE] ?: Account(),
                onSignIn = { signIn(SourceKey.FLOATPLANE) },
                onSignOut = { confirmSignOut = true },
            )
        }

        item { Section("This device") }
        item { ProfileSummary(settings.profile) }
        item { SecondaryButton("Run the device check again", runDeviceCheck, icon = Icons.Filled.Memory) }

        item { Section("About") }
        item {
            Text(
                "Slopstream ${BuildConfig.VERSION_NAME}. Free software under the GPL-3.0. " +
                    "YouTube support is built on NewPipeExtractor by the NewPipe team. No tracking: YouTube needs no account and " +
                    "its subscriptions live on this device; Floatplane plays what your own plan includes.",
                style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
                modifier = Modifier.widthIn(max = 720.dp),
            )
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of Floatplane?") },
            text = { Text("This ends the session on Floatplane too. Its channels leave Subscriptions; sign in again to bring them back.") },
            confirmButton = { TextButton(onClick = { vm.signOutFloatplane(); confirmSignOut = false }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
            containerColor = Palette.Surface,
        )
    }
}

@Composable
private fun AccountRow(source: SourceKey, account: Account, onSignIn: () -> Unit, onSignOut: () -> Unit) {
    ListRow {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(source.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    when (account.status) {
                        AccountStatus.SIGNED_OUT -> "Not signed in. Sign in to watch what your plan includes."
                        AccountStatus.SIGNED_IN -> "Signed in as ${account.username}"
                        AccountStatus.EXPIRED -> "${account.username}: ${source.label} signed you out. Sign in again to refresh."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (account.status == AccountStatus.EXPIRED) Palette.Live else Palette.TextDim,
                )
            }
            Spacer(Modifier.width(12.dp))
            when (account.status) {
                AccountStatus.SIGNED_OUT -> PrimaryButton("Sign in", onSignIn)
                AccountStatus.SIGNED_IN -> SecondaryButton("Sign out", onSignOut)
                AccountStatus.EXPIRED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Sign in", onSignIn)
                    SecondaryButton("Sign out", onSignOut)
                }
            }
        }
    }
}

fun capLabel(c: QualityCap) = when (c) {
    QualityCap.BEST -> "Best available"
    QualityCap.P2160 -> "2160p (4K)"
    QualityCap.P1440 -> "1440p"
    QualityCap.P1080 -> "1080p"
    QualityCap.P720 -> "720p"
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = Palette.Accent, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    ListRow(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                tint = if (selected) Palette.Accent else Palette.TextFaint, modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListRow(onClick = { onChange(!checked) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(body, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked, onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.OnAccent),
            )
        }
    }
}

@Composable
fun ProfileSummary(p: DeviceProfile?) {
    ListRow {
        if (p == null) {
            Text("Not checked yet.", color = Palette.TextDim)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                p.decoders.sortedBy { it.codec.ordinal }.forEach { d ->
                    val max = d.maxLines
                    val fps = d.maxFpsByLines[max] ?: 0
                    Text("${d.codec.name}: hardware up to ${max}p$fps" + if (d.hdr) " · HDR" else "", style = MaterialTheme.typography.bodyMedium)
                }
                if (p.decoders.isEmpty()) Text("No hardware video decoders found", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Display ${p.displayLines}p" + (if (p.hdrDisplay) " HDR" else "") +
                        " · " + (p.throughputKbps?.let { "measured %.0f Mbit/s".format(it / 1000.0) } ?: "speed not measured"),
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
                )
            }
        }
    }
}

@HiltViewModel
class DeviceCheckViewModel @Inject constructor(private val check: DeviceCheck) : ViewModel() {
    private val _step = MutableStateFlow<DeviceCheck.Step?>(null)
    val step: StateFlow<DeviceCheck.Step?> = _step.asStateFlow()
    private val _result = MutableStateFlow<DeviceCheckResult?>(null)
    val result: StateFlow<DeviceCheckResult?> = _result.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun start() {
        if (_step.value != null && _error.value == null) return
        _error.value = null
        viewModelScope.launch {
            runCatching { check.run { _step.value = it } }
                .onSuccess { _result.value = it }
                .onFailure { _error.value = it.message ?: "Device check failed" }
        }
    }
}

@Composable
fun DeviceCheckScreen(firstRun: Boolean, onDone: () -> Unit, vm: DeviceCheckViewModel = hiltViewModel()) {
    val step by vm.step.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.start() }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (firstRun) "Welcome to Slopstream" else "Device check", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Checking what this device plays best, so every video starts at the highest quality it can handle.",
                color = Palette.TextDim,
            )
            Spacer(Modifier.height(8.dp))
            StepRow("Video decoders", step, DeviceCheck.Step.DECODERS)
            StepRow("Display and HDR", step, DeviceCheck.Step.DISPLAY)
            StepRow("Connection speed", step, DeviceCheck.Step.NETWORK)
            result?.let { r ->
                Spacer(Modifier.height(8.dp))
                ProfileSummary(r.profile)
                Text("Suggested maximum quality: ${capLabel(r.recommendedCap)}. You can change it in Settings.", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text("The check did not finish: $it", color = Palette.Live) }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(if (firstRun) "Continue" else "Done", onDone, enabled = result != null || error != null)
                if (error != null) SecondaryButton("Try again", vm::start)
            }
        }
    }
}

@Composable
private fun StepRow(label: String, current: DeviceCheck.Step?, step: DeviceCheck.Step) {
    val done = current != null && current.ordinal > step.ordinal
    val active = current == step
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            done -> Icon(Icons.Filled.CheckCircle, null, tint = Palette.Accent, modifier = Modifier.size(20.dp))
            active -> CircularProgressIndicator(Modifier.size(20.dp), color = Palette.Accent, strokeWidth = 2.dp)
            else -> Icon(Icons.Filled.RadioButtonUnchecked, null, tint = Palette.TextFaint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = if (done || active) Palette.Text else Palette.TextDim)
    }
}
