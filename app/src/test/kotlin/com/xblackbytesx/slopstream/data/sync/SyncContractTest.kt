package com.xblackbytesx.slopstream.data.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The server's wire contract: copies of slopstream-server's golden JSON
 * (`crates/core/tests/golden`, refresh them together). What the app sends must read as the
 * server's own example, and what the server sends must parse here.
 */
class SyncContractTest {
    private fun golden(name: String): String =
        checkNotNull(javaClass.getResource("/sync/$name.json")) { "missing golden $name.json" }.readText()

    /** The server reads a missing field as null, so nulls on either side compare equal. */
    private fun withoutNulls(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.filterValues { it !is JsonNull }.mapValues { withoutNulls(it.value) })
        is JsonArray -> JsonArray(e.map { withoutNulls(it) })
        else -> e
    }

    private fun sameJson(expected: String, actual: String) =
        assertEquals(withoutNulls(SyncJson.parseToJsonElement(expected)), withoutNulls(SyncJson.parseToJsonElement(actual)))

    @Test
    fun theRequestWeSendIsTheServersExample() {
        val text = golden("sync_request")
        val request = SyncJson.decodeFromString(SyncRequest.serializer(), text)
        assertEquals(41L, request.cursor)
        assertEquals(12L, request.subscriptions.single().baseRev)
        assertNull("a request row has no rev", request.subscriptions.single().rev)
        assertEquals(fingerprint("old-session-value"), request.sessionFingerprints["fp"])
        sameJson(text, SyncJson.encodeToString(SyncRequest.serializer(), request))
    }

    @Test
    fun theServersAnswerParses() {
        val text = golden("sync_response")
        val r = SyncJson.decodeFromString(SyncResponse.serializer(), text)
        assertEquals(45L, r.cursor)
        assertFalse(r.more || r.reset)
        assertEquals(43L, r.subscriptions.single().rev)
        assertEquals(1_761_220_000_000L, r.watchLater.single().deletedAt)
        assertEquals("A video", r.watchState.single().title)
        val (fp, sp) = r.sessions
        assertEquals("ok" to "example-session-value", fp.status to fp.value)
        assertEquals("same fingerprint as ours", fingerprint("example-session-value"), fp.fingerprint)
        assertEquals("expired", sp.status)
        assertNull(sp.value)
        sameJson(text, SyncJson.encodeToString(SyncResponse.serializer(), r))
    }

    @Test
    fun pairingAndSessions() {
        val started = SyncJson.decodeFromString(PairStarted.serializer(), golden("pair_started"))
        assertEquals("KJ7W-3MPQ" to 600L, started.userCode to started.expiresInSec)
        val result = SyncJson.decodeFromString(PairResult.serializer(), golden("pair_result"))
        assertEquals("approved", result.status)
        assertEquals("sam@example.com", result.person?.email)
        sameJson(golden("session_put"), SyncJson.encodeToString(SessionPut.serializer(), SessionPut("example-session-value", "someone")))
        sameJson(golden("expired_report"), SyncJson.encodeToString(ExpiredReport.serializer(), ExpiredReport(fingerprint("example-session-value"))))
    }

    @Test
    fun feeds() {
        val req = SyncJson.decodeFromString(FeedRequest.serializer(), golden("feed_request"))
        assertEquals(120L, req.channels["yt:UCXuqSBlHAE6Xw-yeJA0Tunw"])
        sameJson(golden("feed_request"), SyncJson.encodeToString(FeedRequest.serializer(), req))
        val r = SyncJson.decodeFromString(FeedResponse.serializer(), golden("feed_response"))
        assertEquals(listOf(121L, 122L), r.items.map { it.rev })
        assertEquals("UCXuqSBlHAE6Xw-yeJA0Tunw" to false, r.items[0].channelId to r.items[0].deleted)
        assertEquals(true, r.items[1].deleted)
        assertEquals(121L, r.channels.single().rev)
        sameJson(golden("feed_response"), SyncJson.encodeToString(FeedResponse.serializer(), r))
        assertEquals(12L, SyncJson.decodeFromString(FeedRefresh.serializer(), golden("feed_refresh")).fetched)
    }

    @Test
    fun serverAddresses() {
        assertEquals("https://slop.example.com/", SyncClient.parseServer(" slop.example.com ").toString())
        assertEquals("https://slop.example.com/sync/", SyncClient.parseServer("https://slop.example.com/sync").toString())
        assertEquals("http://192.168.1.10:8080/", SyncClient.parseServer("http://192.168.1.10:8080").toString())
        assertEquals("http://nas.local:8080/", SyncClient.parseServer("http://nas.local:8080/").toString())
        assertNull("plain http to the internet", SyncClient.parseServer("http://slop.example.com"))
        assertNull(SyncClient.parseServer("http://8.8.8.8"))
        assertNull(SyncClient.parseServer("not a url at all"))
    }
}
