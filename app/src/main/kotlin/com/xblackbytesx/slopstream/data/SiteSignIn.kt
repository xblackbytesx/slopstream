package com.xblackbytesx.slopstream.data

import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.data.sync.SyncEngine
import com.xblackbytesx.slopstream.floatplane.FloatplaneApi
import com.xblackbytesx.slopstream.floatplane.FloatplaneSite
import com.xblackbytesx.slopstream.floatplane.FloatplaneSource
import com.xblackbytesx.slopstream.floatplane.SessionAuthorizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** The Floatplane-platform sources (Floatplane, Sauce+), each signed in with its stored session. */
class FloatplaneSites(sources: List<FloatplaneSource>) {
    private val byKey = sources.associateBy { it.key }

    val all: Collection<FloatplaneSource> get() = byKey.values

    operator fun get(key: SourceKey): FloatplaneSource = byKey[key] ?: error("$key is not a Floatplane-platform site")

    fun isSite(key: SourceKey) = key in byKey
}

/**
 * Sign-in to a Floatplane-platform site with its website session, from the in-app login page
 * or pasted from another device. The stopgap until Slopstream has its own OAuth client id
 * (AGENTS.md decision 16); either way the user signs in on the site's own page.
 */
@Singleton
class SiteSignIn @Inject constructor(
    private val accounts: Accounts,
    private val client: OkHttpClient,
    private val sites: FloatplaneSites,
    private val subscriptions: SubscriptionRepository,
    private val feed: FeedRepository,
    private val services: Services,
    private val sync: SyncEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun site(key: SourceKey): FloatplaneSite = sites[key].site

    fun isSite(key: SourceKey): Boolean = sites.isSite(key)

    /**
     * Checks that [session] is signed in, stores it and starts fetching. Returns the username;
     * throws [com.xblackbytesx.slopstream.core.SourceAuthException] for a session that is not
     * (or no longer) signed in.
     */
    suspend fun signIn(key: SourceKey, session: String): String {
        val site = site(key)
        val sid = sessionValue(session, site.sessionCookie)
            ?: throw IllegalArgumentException("That does not look like a ${site.sessionCookie} value")
        val username = FloatplaneSource(FloatplaneApi(client, SessionAuthorizer(site.sessionCookie) { sid }, site.baseUrl), site).username()
        accounts.save(key, sid, username)
        // Every other device of this person signs in with it too.
        sync.sessionSignedIn(key)
        services.select(key)
        scope.launch { feed.refreshSource(key) }
        return username
    }

    /**
     * Ends the session on the site too, then forgets it here (and, with a sync server, on every
     * device of this person). Account-provided rows (Floatplane) are tombstoned, as they belong
     * to the account; creators picked from a catalog (Sauce+) stay, for the next sign-in.
     * Either way the service leaves the screens.
     */
    suspend fun signOut(key: SourceKey) {
        val source = sites[key]
        runCatching { source.signOut() }
        if (source.capabilities.accountSubscriptions) subscriptions.syncAccount(key, emptyList())
        accounts.clear(key)
        sync.sessionSignedOut(key)
    }

    companion object {
        /**
         * The cookie value from whatever was pasted: the bare value, `name=...`, or a whole
         * Cookie header. Null when nothing usable is there.
         */
        fun sessionValue(input: String, cookie: String = FloatplaneSite.FLOATPLANE.sessionCookie): String? {
            val text = input.trim().removePrefix("Cookie:").trim()
            val named = text.split(';').map { it.trim() }
                .firstOrNull { it.startsWith("$cookie=") }
                ?.substringAfter('=')
            val value = (named ?: text.takeIf { '=' !in it && ';' !in it }).orEmpty().trim().trim('"')
            return value.takeIf { it.length >= 16 && it.none(Char::isWhitespace) }
        }
    }
}
