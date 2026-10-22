package com.xblackbytesx.slopstream.ui.nav

import kotlinx.serialization.Serializable

@Serializable data object SearchRoute
@Serializable data object VideosRoute
@Serializable data object ShortsRoute
@Serializable data object WatchLaterRoute
@Serializable data object SubscriptionsRoute
@Serializable data object SettingsRoute
@Serializable data object ImportRoute

/** [query] prefills the search, e.g. a channel link shared from a browser. */
@Serializable data class AddChannelRoute(val query: String = "")
@Serializable data class DeviceCheckRoute(val firstRun: Boolean)

/** Signing in to a service that needs an account; [source] is a SourceKey id (Floatplane). */
@Serializable data class SignInRoute(val source: String)
@Serializable data class ChannelRoute(val source: String, val channelId: String, val name: String)
@Serializable data class PlayerRoute(val source: String, val videoId: String, val title: String = "", val channelName: String = "")

/** The Shorts player pages through the Shorts feed starting at [videoId]. */
@Serializable data class ShortsPlayerRoute(val source: String, val videoId: String)
