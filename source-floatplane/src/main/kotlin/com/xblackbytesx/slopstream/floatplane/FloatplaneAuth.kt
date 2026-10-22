package com.xblackbytesx.slopstream.floatplane

import com.xblackbytesx.slopstream.core.SourceAuthException
import com.xblackbytesx.slopstream.core.SourceBlockedException
import com.xblackbytesx.slopstream.core.SourceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Clock
import java.time.Instant

/**
 * Floatplane sign-in: OAuth 2.0 device authorization (RFC 8628) against Floatplane's Keycloak
 * realm, the way its own TV app signs in. The TV shows a code, the user approves it on
 * floatplane.com from a phone, and we never see the password. Floatplane only accepts client
 * ids it registered, so [clientId] is Slopstream's own once granted, never another app's.
 */
class FloatplaneAuth(
    private val client: OkHttpClient,
    private val clientId: String,
    private val realmUrl: String = "https://auth.floatplane.com/realms/floatplane",
    private val clock: Clock = Clock.systemUTC(),
) {
    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        /** The same page with the code filled in; what a QR code should point at. */
        val verificationUriComplete: String?,
        val intervalSec: Long,
        val expiresAt: Instant,
    )

    @Serializable
    data class Tokens(val accessToken: String, val refreshToken: String?, val expiresAtEpochSec: Long) {
        fun expiresWithin(seconds: Long, now: Instant): Boolean = now.epochSecond + seconds >= expiresAtEpochSec
    }

    private val endpoint = "$realmUrl/protocol/openid-connect"

    suspend fun startDeviceLogin(): DeviceCode = withContext(Dispatchers.IO) {
        val o = post("$endpoint/auth/device", FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", "openid offline_access")
            .build())
        DeviceCode(
            deviceCode = o.string("device_code") ?: throw SourceException("Device login: no device_code"),
            userCode = o.string("user_code") ?: throw SourceException("Device login: no user_code"),
            verificationUri = o.string("verification_uri") ?: throw SourceException("Device login: no verification_uri"),
            verificationUriComplete = o.string("verification_uri_complete"),
            intervalSec = o.long("interval") ?: 5,
            expiresAt = clock.instant().plusSeconds(o.long("expires_in") ?: 600),
        )
    }

    /** Polls until the user approves, denies or the code expires. Cancel the coroutine to stop. */
    suspend fun awaitDeviceLogin(code: DeviceCode): Tokens {
        var interval = code.intervalSec.coerceAtLeast(1)
        while (true) {
            if (clock.instant().isAfter(code.expiresAt)) throw SourceAuthException("The sign-in code expired")
            delay(interval * 1000)
            val result = withContext(Dispatchers.IO) {
                tokenRequest(FormBody.Builder()
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .add("device_code", code.deviceCode)
                    .add("client_id", clientId)
                    .build())
            }
            when (result) {
                is TokenResult.Ok -> return result.tokens
                is TokenResult.Error -> when (result.error) {
                    "authorization_pending" -> Unit
                    "slow_down" -> interval += 5
                    "access_denied" -> throw SourceAuthException("Sign-in was declined")
                    "expired_token" -> throw SourceAuthException("The sign-in code expired")
                    else -> throw SourceAuthException("Sign-in failed: ${result.error}")
                }
            }
        }
    }

    /** A fresh access token. A refused refresh means the user has to sign in again. */
    suspend fun refresh(tokens: Tokens): Tokens = withContext(Dispatchers.IO) {
        val refresh = tokens.refreshToken ?: throw SourceAuthException("Signed out: no refresh token")
        when (val r = tokenRequest(FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("client_id", clientId)
            .build())) {
            is TokenResult.Ok -> r.tokens.copy(refreshToken = r.tokens.refreshToken ?: refresh)
            is TokenResult.Error -> throw SourceAuthException("Signed out (${r.error})")
        }
    }

    private sealed interface TokenResult {
        data class Ok(val tokens: Tokens) : TokenResult
        data class Error(val error: String) : TokenResult
    }

    private fun tokenRequest(body: FormBody): TokenResult {
        val (code, o) = exchange("$endpoint/token", body)
        if (code in 200..299) {
            val access = o.string("access_token") ?: throw SourceException("Token response without access_token")
            return TokenResult.Ok(Tokens(access, o.string("refresh_token"), clock.instant().epochSecond + (o.long("expires_in") ?: 300)))
        }
        if (code == 429) throw SourceBlockedException("Floatplane sign-in is rate limited (HTTP 429)")
        // RFC 6749 errors come back as 400 with an "error" field.
        return TokenResult.Error(o.string("error") ?: "HTTP $code")
    }

    private fun post(url: String, body: FormBody): JsonObject {
        val (code, o) = exchange(url, body)
        return when {
            code in 200..299 -> o
            code == 429 -> throw SourceBlockedException("Floatplane sign-in is rate limited (HTTP 429)")
            o.string("error") == "invalid_client" -> throw SourceAuthException("Floatplane does not know this app's client id")
            else -> throw SourceException("Sign-in request failed: ${o.string("error") ?: "HTTP $code"}")
        }
    }

    private fun exchange(url: String, body: FormBody): Pair<Int, JsonObject> {
        val request = Request.Builder().url(url).header("User-Agent", Floatplane.USER_AGENT).post(body).build()
        try {
            client.newCall(request).execute().use { r ->
                val text = r.body.string()
                val o = runCatching { FloatplaneJsonFormat.parseToJsonElement(text) as JsonObject }.getOrElse { JsonObject(emptyMap()) }
                return r.code to o
            }
        } catch (e: IOException) {
            throw SourceException("Sign-in request failed: ${e.message}", e)
        }
    }

    private fun JsonObject.string(k: String) = get(k)?.jsonPrimitive?.contentOrNull
    private fun JsonObject.long(k: String) = get(k)?.jsonPrimitive?.longOrNull
}
