package com.xblackbytesx.slopstream.floatplane

import com.xblackbytesx.slopstream.core.SourceKey
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * A site on Floatplane's platform. Sauce+ runs the same frontend build and API with its own
 * accounts (checked 2026-10-23, AGENTS.md roadmap), so one module serves both.
 */
data class FloatplaneSite(
    val key: SourceKey,
    val baseUrl: String,
    /** The website's session cookie, what the sign-in captures. */
    val sessionCookie: String,
    /**
     * One subscription covers every creator, so the account lists none: members pick
     * creators from the catalog ([FloatplaneSource.catalog]) and follow them locally.
     */
    val globalSubscriptions: Boolean,
    val hasLive: Boolean,
) {
    val name: String get() = key.label
    val host: String get() = baseUrl.toHttpUrl().host

    companion object {
        val FLOATPLANE = FloatplaneSite(SourceKey.FLOATPLANE, Floatplane.BASE_URL, "sails.sid", globalSubscriptions = false, hasLive = true)

        /** `__Host-sp-sess` seen in the user's browser; `isLiveStreamEnabled: false` in its config. */
        val SAUCEPLUS = FloatplaneSite(SourceKey.SAUCEPLUS, "https://www.sauceplus.com", "__Host-sp-sess", globalSubscriptions = true, hasLive = false)

        val ALL = listOf(FLOATPLANE, SAUCEPLUS)
    }
}
