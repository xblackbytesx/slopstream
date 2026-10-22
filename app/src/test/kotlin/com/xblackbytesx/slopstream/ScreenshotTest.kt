package com.xblackbytesx.slopstream

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.github.takahirom.roborazzi.captureRoboImage
import com.xblackbytesx.slopstream.core.Channel
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.core.playback.DecoderCaps
import com.xblackbytesx.slopstream.core.playback.DeviceProfile
import com.xblackbytesx.slopstream.core.playback.VideoCodec
import com.xblackbytesx.slopstream.data.Account
import com.xblackbytesx.slopstream.data.AccountStatus
import com.xblackbytesx.slopstream.data.ServiceState
import com.xblackbytesx.slopstream.data.db.SubscriptionEntity
import com.xblackbytesx.slopstream.data.db.VideoRow
import com.xblackbytesx.slopstream.ui.components.ScreenHeader
import com.xblackbytesx.slopstream.ui.components.ServiceBar
import com.xblackbytesx.slopstream.ui.components.VideoActions
import com.xblackbytesx.slopstream.ui.components.VideoGrid
import com.xblackbytesx.slopstream.ui.nav.AppChrome
import com.xblackbytesx.slopstream.ui.search.GlobalResults
import com.xblackbytesx.slopstream.ui.search.SearchActions
import com.xblackbytesx.slopstream.ui.search.SearchBar
import com.xblackbytesx.slopstream.ui.search.SearchResults
import com.xblackbytesx.slopstream.ui.search.SearchState
import com.xblackbytesx.slopstream.ui.settings.ProfileSummary
import com.xblackbytesx.slopstream.ui.subscriptions.SubscriptionList
import com.xblackbytesx.slopstream.ui.theme.SlopstreamTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

private const val PHONE = "w411dp-h891dp-port-xxhdpi"
private const val TV = "w960dp-h540dp-land-television-xhdpi"

/**
 * Renders the main screens on a phone and on a 1080p TV, with generated thumbnails. For
 * looking at after UI changes, not a golden comparison: `make screenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val outDir = File(System.getProperty("slopstream.screenshots") ?: "build/screenshots").apply { mkdirs() }

    private val noop = object : VideoActions {
        override fun play(row: VideoRow) = Unit
        override fun menu(row: VideoRow) = Unit
        override fun prefetch(row: VideoRow) = Unit
    }

    @OptIn(ExperimentalCoilApi::class)
    private val thumbs = AsyncImagePreviewHandler { request ->
        val key = request.data.toString().hashCode()
        val portrait = "/s" in request.data.toString() || request.data.toString().startsWith("test://s")
        val (w, h) = if (portrait) 180 to 320 else 320 to 180
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val hue = (key and 0xFF) / 255f * 360f
        val a = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.55f))
        val b = android.graphics.Color.HSVToColor(floatArrayOf((hue + 50) % 360, 0.65f, 0.25f))
        Canvas(bmp).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), a, b, Shader.TileMode.CLAMP) })
        bmp.asImage()
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun shot(name: String, isTv: Boolean, content: @Composable () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides thumbs) {
                SlopstreamTheme(isTv = isTv) { content() }
            }
        }
        rule.onRoot().captureRoboImage(File(outDir, "$name.png").path)
    }

    @Composable
    private fun FeedScreen(title: String, rows: List<VideoRow>, tab: Int, portrait: Boolean = false) {
        val items = flowOf(PagingData.from(rows)).collectAsLazyPagingItems()
        AppChrome(current = tab, onSelect = {}) { modifier ->
            Column(modifier) {
                ScreenHeader(title, status = "Updated 4 min ago")
                VideoGrid(items, noop, portrait = portrait)
            }
        }
    }

    /** Floatplane shown, its session rejected since: switcher, no Shorts tab, the notice. */
    @Composable
    private fun FloatplaneFeed(expired: Boolean) {
        val rows = sampleRows.take(4).mapIndexed { i, r ->
            r.copy(source = "fp", channelName = listOf("Linus Tech Tips", "TechLinked", "ShortCircuit", "Linus Tech Tips")[i])
        }
        val items = flowOf(PagingData.from(rows)).collectAsLazyPagingItems()
        val state = ServiceState(
            visible = listOf(SourceKey.YOUTUBE, SourceKey.FLOATPLANE),
            current = SourceKey.FLOATPLANE,
            capabilities = fakeFloatplane().capabilities,
            account = Account(if (expired) AccountStatus.EXPIRED else AccountStatus.SIGNED_IN, "viewer"),
        )
        AppChrome(current = 1, onSelect = {}, hasShorts = false) { modifier ->
            Column(modifier) {
                ScreenHeader("Videos", status = "Updated 4 min ago")
                ServiceBar(state, {}, {})
                VideoGrid(items, noop)
            }
        }
    }

    @Test @Config(qualifiers = TV)
    fun tvFloatplane() = shot("tv-floatplane", isTv = true) { FloatplaneFeed(expired = false) }

    @Test @Config(qualifiers = PHONE)
    fun phoneFloatplaneExpired() = shot("phone-floatplane-expired", isTv = false) { FloatplaneFeed(expired = true) }

    @Test @Config(qualifiers = TV)
    fun tvVideos() = shot("tv-videos", isTv = true) { FeedScreen("Videos", sampleRows, tab = 1) }

    @Test @Config(qualifiers = TV)
    fun tvShorts() = shot("tv-shorts", isTv = true) { FeedScreen("Shorts", sampleShorts, tab = 2, portrait = true) }

    @Test @Config(qualifiers = PHONE)
    fun phoneVideos() = shot("phone-videos", isTv = false) { FeedScreen("Videos", sampleRows, tab = 1) }

    @Test @Config(qualifiers = PHONE)
    fun phoneShorts() = shot("phone-shorts", isTv = false) { FeedScreen("Shorts", sampleShorts, tab = 2, portrait = true) }

    private val searchNoop = object : SearchActions {
        override fun play(row: VideoRow, global: Boolean) = Unit
        override fun menu(row: VideoRow, global: Boolean) = Unit
        override fun prefetch(row: VideoRow) = Unit
        override fun openSubscription(s: SubscriptionEntity) = Unit
        override fun toggleSubscribe(c: Channel) = Unit
    }

    @Composable
    private fun Search() {
        val state = SearchState(
            query = "tech",
            global = true,
            channels = sampleSubscriptions.filter { "Tech" in it.name },
            library = sampleRows.take(3),
            globalResults = GlobalResults(
                "tech",
                loading = false,
                channels = listOf(Channel(SourceKey.YOUTUBE, "UCx", "TechLinked", "", subscriberCount = 2_100_000)),
                videos = sampleRows.drop(4).take(3),
            ),
        )
        AppChrome(current = 0, onSelect = {}) { modifier ->
            Column(modifier) {
                ScreenHeader("Search", status = "Your channels and the videos in your feeds, plus all of YouTube")
                SearchBar(state.query, state.global, {}, {}, {})
                SearchResults(state, searchNoop)
            }
        }
    }

    @Composable
    private fun Subscriptions(filter: String) {
        AppChrome(current = 4, onSelect = {}) { modifier ->
            Column(modifier) {
                ScreenHeader("Subscriptions", status = "8 channels \u00b7 3 favorites")
                SubscriptionList(sampleSubscriptions, filter, {}, {}, { _, _ -> }, {})
            }
        }
    }

    @Test @Config(qualifiers = TV)
    fun tvSearch() = shot("tv-search", isTv = true) { Search() }

    @Test @Config(qualifiers = PHONE)
    fun phoneSearch() = shot("phone-search", isTv = false) { Search() }

    @Test @Config(qualifiers = TV)
    fun tvSubscriptions() = shot("tv-subscriptions", isTv = true) { Subscriptions(filter = "") }

    @Test @Config(qualifiers = PHONE)
    fun phoneSubscriptionsFiltered() = shot("phone-subscriptions-filtered", isTv = false) { Subscriptions(filter = "tech") }

    @Test @Config(qualifiers = TV)
    fun tvDeviceProfile() = shot("tv-device-profile", isTv = true) {
        val ladder = listOf(480, 720, 1080, 1440, 2160).associateWith { 60 }
        ProfileSummary(
            DeviceProfile(
                decoders = listOf(
                    DecoderCaps(VideoCodec.VP9, true, ladder, hdr = true),
                    DecoderCaps(VideoCodec.HEVC, true, ladder, hdr = true),
                    DecoderCaps(VideoCodec.H264, true, ladder),
                ),
                hdrDisplay = true,
                displayLines = 2160,
                throughputKbps = 87_000,
                matchFrameRate = true,
            ),
        )
    }
}
