package com.xblackbytesx.slopstream.youtube.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom

/**
 * An app YouTube's InnerTube API serves (decision 30). The values are NewPipeExtractor's
 * (`ClientsConstants`, v0.26.5, GPL-3.0), checked live 2026-11-08: they are what YouTube
 * accepts today, not guesses. When YouTube retires one, update it from NewPipe's current
 * release and rerun `gradle :source-youtube:bench`.
 */
data class InnertubeClient(
    val name: String,
    val id: String,
    val version: String,
    val userAgent: String,
    /** Extra `context.client` fields: platform, device and OS. */
    val device: Map<String, String>,
) {
    companion object {
        /**
         * Apple Vision Pro's YouTube app. Plain stream URLs (no signature or `n` to decipher), up
         * to 4K VP9 and an HLS manifest, and, unlike IOS and ANDROID_VR, served to the end of the
         * file without a PoToken (seen 2026-11-08: those two answer 403 past the first part).
         */
        val VISIONOS = InnertubeClient(
            name = "VISIONOS",
            id = "101",
            version = "1.02",
            userAgent = "com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; en_GB)",
            device = linkedMapOf(
                "clientScreen" to "WATCH",
                "platform" to "MOBILE",
                "deviceMake" to "Apple",
                "deviceModel" to "RealityDevice14,1",
                "osName" to "visionOS",
                "osVersion" to "25.6.0.23O471",
            ),
        )
    }
}

/** YouTube refused the request itself (HTTP 429, or a bot check in the answer). */
class InnertubeBlockedException(message: String) : IOException(message)

/**
 * The two InnerTube calls playback needs: a visitor id (once, then reused) and the player
 * response. Nothing else: no watch page, no `/next`, no JavaScript player.
 */
class Innertube(
    private val http: OkHttpClient,
    private val hl: String = "en-GB",
    private val gl: String = "GB",
    private val baseUrl: String = "https://www.youtube.com",
    private val apiUrl: String = "https://youtubei.googleapis.com",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    /** A new anonymous visitor: YouTube refuses player requests without one ("not a bot"). */
    fun visitorId(client: InnertubeClient): String {
        val answer = post("$baseUrl/youtubei/v1/visitor_id?prettyPrint=false", client, buildJsonObject { context(client, null) })
        return answer["responseContext"]?.jsonObject?.get("visitorData")?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() } ?: throw IOException("No visitorData in the visitor_id answer")
    }

    fun player(client: InnertubeClient, videoId: String, visitor: String): PlayerResponse {
        val cpn = nonce()
        val body = buildJsonObject {
            context(client, visitor)
            put("videoId", videoId)
            put("cpn", cpn)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val answer = post("$apiUrl/youtubei/v1/player?prettyPrint=false&id=$videoId", client, body)
        return PlayerResponse.parse(answer, cpn)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.context(client: InnertubeClient, visitor: String?) {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", client.name)
                put("clientVersion", client.version)
                client.device.forEach { (k, v) -> put(k, v) }
                visitor?.let { put("visitorData", it) }
                put("hl", hl)
                put("gl", gl)
                put("utcOffsetMinutes", 0)
            }
            putJsonObject("request") {
                putJsonArray("internalExperimentFlags") { }
                put("useSsl", true)
            }
            putJsonObject("user") { put("lockedSafetyMode", false) }
        }
    }

    private fun post(url: String, client: InnertubeClient, body: JsonObject): JsonObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", client.userAgent)
            .header("X-Goog-Api-Format-Version", "2")
            .header("X-Youtube-Client-Name", client.id)
            .header("X-Youtube-Client-Version", client.version)
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { r ->
            if (r.code == 429) throw InnertubeBlockedException("YouTube is rate limiting (HTTP 429)")
            if (!r.isSuccessful) throw IOException("InnerTube answered HTTP ${r.code}")
            return json.parseToJsonElement(r.body.string()).jsonObject
        }
    }

    /** The content playback nonce: 16 characters YouTube ties the stream URLs to. */
    private fun nonce(): String = buildString { repeat(16) { append(NONCE_ALPHABET[random.nextInt(NONCE_ALPHABET.length)]) } }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val NONCE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    }
}
