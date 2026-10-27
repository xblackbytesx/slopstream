package com.xblackbytesx.slopstream.potoken

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/*
 * BotGuard request/response plumbing, ported from NewPipe's JavaScriptUtil.kt (Copyright (C)
 * NewPipe contributors, GPL-3.0-or-later) onto kotlinx.serialization and java.util.Base64.
 */

class PoTokenException(message: String) : Exception(message)

/** The system WebView is too old or broken to run BotGuard. */
class BadWebViewException(message: String) : Exception(message)

internal fun exceptionForJsError(error: String): Exception =
    if ("SyntaxError" in error) BadWebViewException(error) else PoTokenException(error)

/** The Create response, as a JS object literal for `runBotGuard(data)`. */
internal fun parseChallengeData(raw: String): String {
    val scrambled = Json.parseToJsonElement(raw).jsonArray
    val second = scrambled.getOrNull(1)
    val challenge: JsonArray = if (scrambled.size > 1 && second is JsonPrimitive && second.isString) {
        val descrambled = base64Decode(second.content).map { (it + 97).toByte() }.toByteArray().decodeToString()
        Json.parseToJsonElement(descrambled).jsonArray
    } else {
        scrambled[0].jsonArray
    }

    fun str(i: Int): String? = (challenge.getOrNull(i) as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun firstString(i: Int): String? = (challenge.getOrNull(i) as? JsonArray)
        ?.firstOrNull { it is JsonPrimitive && it.isString }?.jsonPrimitive?.content

    return buildJsonObject {
        put("messageId", str(0))
        putJsonObject("interpreterJavascript") {
            put("privateDoNotAccessOrElseSafeScriptWrappedValue", firstString(1))
            put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", firstString(2))
        }
        put("interpreterHash", str(3))
        put("program", str(4))
        put("globalName", str(5))
        put("clientExperimentsStateBlob", str(7))
    }.toString()
}

/** The GenerateIT response: integrity token as a JS `Uint8Array`, and its lifetime in seconds. */
internal fun parseIntegrityTokenData(raw: String): Pair<String, Long> {
    val arr = Json.parseToJsonElement(raw).jsonArray
    val token = arr[0].jsonPrimitive.content
    val lifetime = (arr.getOrNull(1) as? JsonPrimitive)?.longOrNull ?: 0L
    return newUint8Array(base64Decode(token)) to lifetime
}

internal fun stringToU8(identifier: String): String = newUint8Array(identifier.toByteArray())

/** `Uint8Array.toString()` output ("97,98,99") to YouTube's URL-safe base64. */
internal fun u8ToBase64(poToken: String): String {
    val bytes = poToken.split(",").map { it.trim().toInt().toByte() }.toByteArray()
    return Base64.getEncoder().encodeToString(bytes).replace('+', '-').replace('/', '_')
}

private fun newUint8Array(contents: ByteArray): String =
    "new Uint8Array([" + contents.joinToString(",") { (it.toInt() and 0xFF).toString() } + "])"

private fun base64Decode(s: String): ByteArray = try {
    Base64.getDecoder().decode(s.replace('-', '+').replace('_', '/').replace('.', '='))
} catch (e: IllegalArgumentException) {
    throw PoTokenException("Cannot base64 decode")
}
