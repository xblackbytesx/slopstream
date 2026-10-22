package com.xblackbytesx.slopstream.ui.nav

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.core.VideoKind
import com.xblackbytesx.slopstream.data.ServiceState
import com.xblackbytesx.slopstream.data.Services
import com.xblackbytesx.slopstream.ui.accounts.SignInScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import com.xblackbytesx.slopstream.data.db.VideoRow
import com.xblackbytesx.slopstream.ui.feed.ChannelScreen
import com.xblackbytesx.slopstream.ui.feed.ListNav
import com.xblackbytesx.slopstream.ui.feed.ShortsScreen
import com.xblackbytesx.slopstream.ui.feed.VideosScreen
import com.xblackbytesx.slopstream.ui.feed.WatchLaterScreen
import com.xblackbytesx.slopstream.ui.player.PlayerScreen
import com.xblackbytesx.slopstream.ui.player.ShortsPlayerScreen
import com.xblackbytesx.slopstream.ui.search.SearchNav
import com.xblackbytesx.slopstream.ui.search.SearchScreen
import com.xblackbytesx.slopstream.ui.settings.DeviceCheckScreen
import com.xblackbytesx.slopstream.ui.settings.SettingsScreen
import com.xblackbytesx.slopstream.ui.subscriptions.AddChannelScreen
import com.xblackbytesx.slopstream.ui.subscriptions.ImportScreen
import com.xblackbytesx.slopstream.ui.subscriptions.SubscriptionsScreen
import com.xblackbytesx.slopstream.ui.theme.LocalIsTv
import com.xblackbytesx.slopstream.ui.theme.Palette
import kotlin.reflect.KClass

/** [tvOnly]: phones reach it from the screen headers instead, keeping the bottom bar at five. */
private data class TopLevel(val route: Any, val type: KClass<*>, val label: String, val icon: ImageVector, val tvOnly: Boolean = false)

private val TOP_LEVEL = listOf(
    TopLevel(SearchRoute, SearchRoute::class, "Search", Icons.Filled.Search, tvOnly = true),
    TopLevel(VideosRoute, VideosRoute::class, "Videos", Icons.Filled.VideoLibrary),
    TopLevel(ShortsRoute, ShortsRoute::class, "Shorts", Icons.Filled.Slideshow),
    TopLevel(WatchLaterRoute, WatchLaterRoute::class, "Watch later", Icons.AutoMirrored.Filled.PlaylistPlay),
    TopLevel(SubscriptionsRoute, SubscriptionsRoute::class, "Subscriptions", Icons.Filled.Subscriptions),
    TopLevel(SettingsRoute, SettingsRoute::class, "Settings", Icons.Filled.Settings),
)

fun NavHostController.play(row: VideoRow) {
    if (row.kind == VideoKind.SHORT.name) navigate(ShortsPlayerRoute(row.source, row.remoteId))
    else navigate(PlayerRoute(row.source, row.remoteId, row.title, row.channelName))
}

/** What the chrome needs from the service shown: Floatplane has no Shorts screen. */
@HiltViewModel
class ChromeViewModel @Inject constructor(services: Services) : ViewModel() {
    val service: StateFlow<ServiceState> = services.state
}

@Composable
fun AppNav(nav: NavHostController, startDestination: Any, enterPip: () -> Unit, chrome: ChromeViewModel = hiltViewModel()) {
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val current = TOP_LEVEL.firstOrNull { t -> destination?.hasRoute(t.type) == true }
    val service by chrome.service.collectAsStateWithLifecycle()
    val hasShorts = service.capabilities.hasShorts
    val showChrome = destination != null && listOf(
        PlayerRoute::class, ShortsPlayerRoute::class, DeviceCheckRoute::class,
    ).none { destination.hasRoute(it) }

    fun goTop(t: TopLevel) = nav.navigate(t.route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    // Switching to a service without Shorts while on Shorts lands on its Videos.
    LaunchedEffect(hasShorts, current) {
        if (!hasShorts && current?.route == ShortsRoute) goTop(TOP_LEVEL.first { it.route == VideosRoute })
    }

    val signIn: (SourceKey) -> Unit = { nav.navigate(SignInRoute(it.id)) }
    val listNav = ListNav(
        play = { nav.play(it) },
        openChannel = { nav.navigate(ChannelRoute(it.source, it.channelId, it.channelName)) },
        addChannel = { nav.navigate(AddChannelRoute()) },
        import = { nav.navigate(ImportRoute) },
        search = { goTop(TOP_LEVEL.first { it.route == SearchRoute }) },
        signIn = signIn,
    )

    val host: @Composable (Modifier) -> Unit = { modifier ->
        NavHost(nav, startDestination = startDestination, modifier = modifier) {
            composable<SearchRoute> {
                SearchScreen(
                    SearchNav(
                        play = { nav.play(it) },
                        playDirect = { nav.navigate(PlayerRoute(it.source, it.remoteId, it.title, it.channelName)) },
                        openChannel = listNav.openChannel,
                        openSubscription = { nav.navigate(ChannelRoute(it.source, it.remoteId, it.name)) },
                        signIn = signIn,
                    ),
                )
            }
            composable<VideosRoute> { VideosScreen(listNav) }
            composable<ShortsRoute> { ShortsScreen(listNav) }
            composable<WatchLaterRoute> { WatchLaterScreen(listNav) }
            composable<SubscriptionsRoute> {
                SubscriptionsScreen(
                    openChannel = { nav.navigate(ChannelRoute(it.source, it.remoteId, it.name)) },
                    addChannel = { nav.navigate(AddChannelRoute()) },
                    import = { nav.navigate(ImportRoute) },
                    search = listNav.search,
                    signIn = signIn,
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(runDeviceCheck = { nav.navigate(DeviceCheckRoute(firstRun = false)) }, signIn = signIn)
            }
            composable<SignInRoute> { SignInScreen(onDone = { nav.popBackStack() }) }
            composable<AddChannelRoute> { AddChannelScreen() }
            composable<ImportRoute> { ImportScreen() }
            composable<ChannelRoute> { ChannelScreen(listNav, onUnsubscribed = { nav.popBackStack() }) }
            composable<PlayerRoute> { PlayerScreen(onBack = { nav.popBackStack() }, enterPip = enterPip) }
            composable<ShortsPlayerRoute> { ShortsPlayerScreen(onBack = { nav.popBackStack() }, enterPip = enterPip) }
            composable<DeviceCheckRoute> { e ->
                val firstRun = e.toRoute<DeviceCheckRoute>().firstRun
                DeviceCheckScreen(firstRun, onDone = {
                    if (firstRun) nav.navigate(VideosRoute) { popUpTo(0) { inclusive = true } } else nav.popBackStack()
                })
            }
        }
    }

    if (!showChrome) {
        host(Modifier.fillMaxSize().background(Palette.Background))
        return
    }
    AppChrome(current = TOP_LEVEL.indexOf(current), onSelect = { goTop(TOP_LEVEL[it]) }, hasShorts = hasShorts, content = host)
}

/**
 * The navigation around top-level screens: a left rail on TV, a bottom bar on touch devices.
 * Stateless, so screenshot tests render it with fake content. [current] indexes the top-level
 * destinations (-1 for none).
 */
@Composable
fun AppChrome(current: Int, onSelect: (Int) -> Unit, hasShorts: Boolean = true, content: @Composable (Modifier) -> Unit) {
    val selected = TOP_LEVEL.getOrNull(current)
    val shown = TOP_LEVEL.filter { hasShorts || it.route != ShortsRoute }
    if (LocalIsTv.current) {
        Row(Modifier.fillMaxSize().background(Palette.Background)) {
            TvRail(shown, selected) { onSelect(TOP_LEVEL.indexOf(it)) }
            content(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Scaffold(
            containerColor = Palette.Background,
            bottomBar = {
                NavigationBar(containerColor = Palette.Surface) {
                    shown.forEach { t ->
                        if (t.tvOnly) return@forEach
                        NavigationBarItem(
                            selected = t == selected,
                            onClick = { onSelect(TOP_LEVEL.indexOf(t)) },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.label, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Palette.OnAccent,
                                indicatorColor = Palette.Accent,
                                selectedTextColor = Palette.Text,
                                unselectedIconColor = Palette.TextDim,
                                unselectedTextColor = Palette.TextDim,
                            ),
                        )
                    }
                }
            },
        ) { padding -> content(Modifier.fillMaxSize().padding(padding)) }
    }
}

/** Icons only until the rail takes focus, then it widens to show labels. */
@Composable
private fun TvRail(items: List<TopLevel>, current: TopLevel?, onSelect: (TopLevel) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxHeight()
            .width(if (expanded) 220.dp else 80.dp)
            .animateContentSize()
            .background(Palette.Surface)
            .onFocusChanged { expanded = it.hasFocus }
            .padding(vertical = 28.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (expanded) "Slopstream" else "S",
            style = MaterialTheme.typography.titleLarge,
            color = Palette.Accent,
            modifier = Modifier.padding(start = 14.dp, bottom = 20.dp),
            maxLines = 1,
        )
        items.forEach { t ->
            RailItem(t, selected = t == current, expanded = expanded) { onSelect(t) }
        }
    }
}

@Composable
private fun RailItem(t: TopLevel, selected: Boolean, expanded: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = when {
        focused -> Palette.Accent
        selected -> Palette.SurfaceHigh
        else -> Palette.Surface
    }
    val fg = when {
        focused -> Palette.OnAccent
        selected -> Palette.Accent
        else -> Palette.TextDim
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(t.icon, t.label, tint = fg, modifier = Modifier.size(24.dp))
        if (expanded) {
            Spacer(Modifier.width(14.dp))
            Text(t.label, color = fg, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
    }
}
