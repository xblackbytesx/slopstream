package com.xblackbytesx.slopstream.data

import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.floatplane.FloatplaneApi
import com.xblackbytesx.slopstream.floatplane.FloatplaneSource
import com.xblackbytesx.slopstream.floatplane.SessionAuthorizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Floatplane sign-in with the website session (`sails.sid`), from the in-app login page or
 * pasted from another device. The stopgap until Slopstream has its own OAuth client id
 * (AGENTS.md decision 13); either way the user signs in on Floatplane's own page.
 */
@Singleton
class FloatplaneAccount @Inject constructor(
    private val accounts: Accounts,
    private val client: OkHttpClient,
    private val source: FloatplaneSource,
    private val subscriptions: SubscriptionRepository,
    private val feed: FeedRepository,
    private val services: Services,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Checks that [session] is signed in, stores it and starts fetching the account's
     * subscriptions. Returns the username; throws [com.xblackbytesx.slopstream.core.SourceAuthException]
     * for a session that is not (or no longer) signed in.
     */
    suspend fun signIn(session: String): String {
        val sid = sessionValue(session) ?: throw IllegalArgumentException("That does not look like a sails.sid value")
        val username = FloatplaneSource(FloatplaneApi(client, SessionAuthorizer { sid })).username()
        accounts.save(SourceKey.FLOATPLANE, sid, username)
        services.select(SourceKey.FLOATPLANE)
        scope.launch { feed.refreshSource(SourceKey.FLOATPLANE) }
        return username
    }

    /**
     * Ends the session on Floatplane too, then forgets it here. The Floatplane rows are
     * tombstoned (they belong to the account); their cached videos simply stop showing.
     */
    suspend fun signOut() {
        runCatching { source.signOut() }
        subscriptions.syncAccount(SourceKey.FLOATPLANE, emptyList())
        accounts.clear(SourceKey.FLOATPLANE)
    }

    companion object {
        /**
         * The cookie value from whatever was pasted: the bare value, `sails.sid=...`, or a whole
         * Cookie header. Null when nothing usable is there.
         */
        fun sessionValue(input: String): String? {
            val text = input.trim().removePrefix("Cookie:").trim()
            val named = text.split(';').map { it.trim() }
                .firstOrNull { it.startsWith(SessionAuthorizer.SESSION_COOKIE + "=") }
                ?.substringAfter('=')
            val value = (named ?: text.takeIf { '=' !in it && ';' !in it }).orEmpty().trim().trim('"')
            return value.takeIf { it.length >= 16 && it.none(Char::isWhitespace) }
        }
    }
}
