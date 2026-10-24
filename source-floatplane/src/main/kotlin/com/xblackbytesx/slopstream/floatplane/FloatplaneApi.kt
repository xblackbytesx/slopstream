package com.xblackbytesx.slopstream.floatplane

import com.xblackbytesx.slopstream.core.SourceAuthException
import com.xblackbytesx.slopstream.core.SourceBlockedException
import com.xblackbytesx.slopstream.core.SourceException
import com.xblackbytesx.slopstream.core.SourceUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Clock

object Floatplane {
    const val USER_AGENT = "Slopstream/0.1"
    const val BASE_URL = "https://www.floatplane.com"
}

/** Adds the signed-in user's credentials to an API request. */
fun interface FloatplaneAuthorizer {
    suspend fun authorize(request: Request.Builder)
}

/**
 * Floatplane's REST API, read-only; requests run on Dispatchers.IO.
 * Floatplane answers 403 both when signed out and when the plan lacks the item, and names which
 * in the body (`notLoggedInError`, `notSubscribedError`; checked live 2026-10-22). Signed out
 * is [SourceAuthException], not in the plan is [SourceUnavailableException], 429 is
 * [SourceBlockedException].
 */
class FloatplaneApi(
    private val client: OkHttpClient,
    private val authorizer: FloatplaneAuthorizer,
    private val baseUrl: String = Floatplane.BASE_URL,
) {
    /** The API host; it also serves the HLS decryption keys (`/api/video/watchKey`). */
    val host: String = baseUrl.toHttpUrl().host

    /** The headers that sign a request in, for the player's key requests to [host]. */
    suspend fun credentialHeaders(): Map<String, String> {
        val builder = Request.Builder().url(baseUrl)
        authorizer.authorize(builder)
        return builder.build().headers.toMap()
    }

    /**
     * The HTTP status of a stream URL that carries its own token (no credentials sent). A live
     * playlist answers 404 while the creator is offline (checked live 2026-10-22).
     */
    suspend fun status(url: String): Int = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).header("User-Agent", Floatplane.USER_AGENT).build()).execute().use { it.code }
        } catch (e: IOException) {
            throw SourceException("Floatplane request failed: ${e.message}", e)
        }
    }

    suspend fun self(): SelfDto = get("/api/v3/user/self", SelfDto.serializer())

    suspend fun subscriptions(): List<SubscriptionDto> =
        get("/api/v3/user/subscriptions", ListSerializer(SubscriptionDto.serializer()))

    suspend fun creator(id: String): CreatorDto = get("/api/v3/creator/info", CreatorDto.serializer()) { addQueryParameter("id", id) }

    /** Every creator on the site matching [search] ("" for all): discovery, not subscriptions. */
    suspend fun creators(search: String = ""): List<CreatorDto> =
        get("/api/v3/creator/list", ListSerializer(CreatorDto.serializer())) { addQueryParameter("search", search) }

    suspend fun channels(creatorIds: List<String>): List<ChannelDto> =
        get("/api/v3/creator/channels/list", ListSerializer(ChannelDto.serializer())) {
            creatorIds.forEach { addQueryParameter("ids", it) }
        }

    /** Newest first. [channelId] narrows to one sub-channel; null returns all of the creator's. */
    suspend fun posts(creatorId: String, channelId: String? = null, limit: Int = 20, fetchAfter: Int = 0): List<PostDto> =
        get("/api/v3/content/creator", ListSerializer(PostDto.serializer())) {
            addQueryParameter("id", creatorId)
            channelId?.let { addQueryParameter("channel", it) }
            addQueryParameter("limit", limit.toString())
            if (fetchAfter > 0) addQueryParameter("fetchAfter", fetchAfter.toString())
            addQueryParameter("hasVideo", "true")
        }

    suspend fun post(id: String): PostDetailDto = get("/api/v3/content/post", PostDetailDto.serializer()) { addQueryParameter("id", id) }

    /**
     * Stream URLs for a video attachment (`onDemand`) or a livestream id (`live`). Floatplane
     * rate limits this endpoint hard, so resolve once per play, never per tile.
     */
    suspend fun delivery(scenario: String, entityId: String, outputKind: String? = null): DeliveryDto =
        get("/api/v3/delivery/info", DeliveryDto.serializer()) {
            addQueryParameter("scenario", scenario)
            addQueryParameter("entityId", entityId)
            outputKind?.let { addQueryParameter("outputKind", it) }
        }

    /** Watch progress per post, percent (0..100; spec and live report agree). Read-only for now. */
    suspend fun progress(postIds: List<String>): List<ProgressDto> {
        val body = buildJsonObject {
            putJsonArray("ids") { postIds.forEach { add(JsonPrimitive(it)) } }
            put("contentType", "blogPost")
        }.toString().toRequestBody("application/json".toMediaType())
        return call(Request.Builder().url("$baseUrl/api/v3/content/get/progress").post(body), ListSerializer(ProgressDto.serializer()))
    }

    /** Ends the session on Floatplane's side too, so a forgotten copy of it is worthless. */
    suspend fun logout() {
        val builder = Request.Builder().url("$baseUrl/api/v3/auth/logout").post(ByteArray(0).toRequestBody())
            .header("User-Agent", Floatplane.USER_AGENT)
        authorizer.authorize(builder)
        withContext(Dispatchers.IO) {
            try {
                client.newCall(builder.build()).execute().close()
            } catch (e: IOException) {
                throw SourceException("Floatplane sign-out failed: ${e.message}", e)
            }
        }
    }

    private suspend fun <T> get(path: String, serializer: KSerializer<T>, query: HttpUrl.Builder.() -> Unit = {}): T {
        val url = (baseUrl + path).toHttpUrl().newBuilder().apply(query).build()
        return call(Request.Builder().url(url), serializer)
    }

    private suspend fun <T> call(builder: Request.Builder, serializer: KSerializer<T>): T {
        builder.header("User-Agent", Floatplane.USER_AGENT).header("Accept", "application/json")
        authorizer.authorize(builder)
        val request = builder.build()
        val path = request.url.encodedPath
        return withContext(Dispatchers.IO) { execute(request, path, serializer) }
    }

    private fun <T> execute(request: Request, path: String, serializer: KSerializer<T>): T {
        try {
            client.newCall(request).execute().use { r ->
                val text = r.body.string()
                when {
                    r.code == 401 || (r.code == 403 && "notLoggedInError" in errorNames(text)) ->
                        throw SourceAuthException("Floatplane signed you out")
                    r.code == 403 -> throw SourceUnavailableException("Not included in your Floatplane plan ($path)")
                    r.code == 404 -> throw SourceUnavailableException("Not found on Floatplane ($path)")
                    r.code == 429 -> throw SourceBlockedException("Floatplane rate limit (HTTP 429, $path)")
                    !r.isSuccessful -> throw SourceException("Floatplane request failed (HTTP ${r.code}, $path)")
                }
                return try {
                    FloatplaneJsonFormat.decodeFromString(serializer, text)
                } catch (e: IllegalArgumentException) {
                    throw SourceException("Floatplane sent something unexpected for $path: ${e.message}", e)
                }
            }
        } catch (e: IOException) {
            throw SourceException("Floatplane request failed: ${e.message}", e)
        }
    }
}

/** The `errors[].name` values of a Floatplane error body. */
internal fun errorNames(body: String): Set<String> = runCatching {
    FloatplaneJsonFormat.parseToJsonElement(body).jsonObject["errors"]!!.jsonArray
        .mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.toSet()
}.getOrDefault(emptySet())

/**
 * The website session ([FloatplaneSite.sessionCookie]) from signing in on the site, the
 * stopgap until Slopstream has its own OAuth client id ([TokenAuthorizer]). [load] returns
 * the cookie value.
 */
class SessionAuthorizer(
    private val cookie: String = FloatplaneSite.FLOATPLANE.sessionCookie,
    private val load: suspend () -> String?,
) : FloatplaneAuthorizer {
    override suspend fun authorize(request: Request.Builder) {
        val sid = load() ?: throw SourceAuthException("Not signed in")
        request.header("Cookie", "$cookie=$sid")
    }
}

/** The usual authorizer: a bearer token from [FloatplaneAuth], refreshed when close to expiry. */
class TokenAuthorizer(
    private val auth: FloatplaneAuth,
    private val load: suspend () -> FloatplaneAuth.Tokens?,
    private val store: suspend (FloatplaneAuth.Tokens) -> Unit,
    private val clock: Clock = Clock.systemUTC(),
) : FloatplaneAuthorizer {
    private val lock = Mutex()

    override suspend fun authorize(request: Request.Builder) {
        val tokens = lock.withLock {
            val current = load() ?: throw SourceAuthException("Not signed in to Floatplane")
            if (current.expiresWithin(60, clock.instant())) auth.refresh(current).also { store(it) } else current
        }
        request.header("Authorization", "Bearer ${tokens.accessToken}")
    }
}
