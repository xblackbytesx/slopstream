package com.xblackbytesx.slopstream

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.activity.viewModels
import androidx.navigation.compose.rememberNavController
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.data.FeedRepository
import com.xblackbytesx.slopstream.data.Settings
import com.xblackbytesx.slopstream.device.DeviceCheck
import com.xblackbytesx.slopstream.playback.PlayerSession
import com.xblackbytesx.slopstream.ui.nav.AddChannelRoute
import com.xblackbytesx.slopstream.ui.nav.AppNav
import com.xblackbytesx.slopstream.ui.nav.DeviceCheckRoute
import com.xblackbytesx.slopstream.ui.nav.PlayerRoute
import com.xblackbytesx.slopstream.ui.nav.ShortsPlayerRoute
import com.xblackbytesx.slopstream.ui.nav.VideosRoute
import com.xblackbytesx.slopstream.ui.theme.SlopstreamTheme
import com.xblackbytesx.slopstream.youtube.YoutubeIds
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settings: Settings,
    private val feed: FeedRepository,
) : ViewModel() {
    /** Null until settings are read; the splash screen stays up until then. */
    private val _start = MutableStateFlow<Any?>(null)
    val start: StateFlow<Any?> = _start.asStateFlow()

    private val _shared = MutableStateFlow<Any?>(null)
    val shared: StateFlow<Any?> = _shared.asStateFlow()

    init {
        viewModelScope.launch {
            _start.value = if (settings.current().profile == null) DeviceCheckRoute(firstRun = true) else VideosRoute
        }
    }

    fun onForeground() {
        viewModelScope.launch { if (settings.current().profile != null) feed.refreshIfStale() }
    }

    /** A YouTube link shared from a browser: play a video, or look up a channel to follow. */
    fun onShared(text: String?) {
        val t = text?.trim().orEmpty()
        val url = Regex("https?://\\S+").find(t)?.value ?: t
        if (url.isBlank()) return
        val video = YoutubeIds.videoIdFrom(url)
        _shared.value = when {
            video != null && "/shorts/" in url -> ShortsPlayerRoute(SourceKey.YOUTUBE.id, video)
            video != null -> PlayerRoute(SourceKey.YOUTUBE.id, video)
            "youtube.com/" in url -> AddChannelRoute(url)
            else -> null
        }
    }

    fun consumeShared() { _shared.value = null }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private val isTv by lazy { DeviceCheck.isTv(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { vm.start.value == null }
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { vm.onForeground() }
        }
        if (!isTv && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    PlayerSession.info.collect { setPictureInPictureParams(pipParams(it)) }
                }
            }
        }

        setContent {
            SlopstreamTheme(isTv = isTv) {
                val start by vm.start.collectAsStateWithLifecycle()
                val shared by vm.shared.collectAsStateWithLifecycle()
                val nav = rememberNavController()
                val startRoute = start ?: return@SlopstreamTheme
                AppNav(nav, startRoute, enterPip = ::enterPip)
                LaunchedEffect(shared) {
                    shared?.let { nav.navigate(it); vm.consumeShared() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) vm.onShared(intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    /** Android 11 has no auto-enter: going Home while a video plays shrinks it to PiP. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val info = PlayerSession.info.value
            if (info.active && info.playing) enterPip()
        }
    }

    private fun enterPip() {
        if (isTv) return
        runCatching { enterPictureInPictureMode(pipParams(PlayerSession.info.value)) }
    }

    private fun pipParams(info: PlayerSession.Info): PictureInPictureParams {
        // Android allows aspect ratios between 1:2.39 and 2.39:1.
        val ratio = (info.width.toFloat() / info.height).coerceIn(0.42f, 2.39f)
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(info.active && info.playing).setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PlayerSession.setInPip(isInPictureInPictureMode)
    }
}
