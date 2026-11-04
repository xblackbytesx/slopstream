package com.xblackbytesx.slopstream.data.sync

import com.xblackbytesx.slopstream.data.remote.ControlRequest
import com.xblackbytesx.slopstream.data.remote.PlayState
import com.xblackbytesx.slopstream.data.remote.ReceiveAnswer
import com.xblackbytesx.slopstream.data.remote.ReceiveRequest
import com.xblackbytesx.slopstream.data.remote.RemoteAction
import com.xblackbytesx.slopstream.data.remote.RemoteStatus
import com.xblackbytesx.slopstream.data.remote.SendAnswer
import com.xblackbytesx.slopstream.data.remote.SendRequest
import com.xblackbytesx.slopstream.data.remote.StateReport
import com.xblackbytesx.slopstream.data.remote.Targets
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(listOf("UCXuqSBlHAE6Xw-yeJA0Tunw"), r.durationJobs)
        val report = FeedDurations("UCXuqSBlHAE6Xw-yeJA0Tunw", mapOf("dQw4w9WgXcQ" to 212L))
        sameJson(golden("feed_durations"), SyncJson.encodeToString(FeedDurations.serializer(), report))
        assertEquals(1L, SyncJson.decodeFromString(DurationsStored.serializer(), golden("durations_stored")).updated)
        assertEquals(ChangesAnswer(7, true), SyncJson.decodeFromString(ChangesAnswer.serializer(), golden("changes")))
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

    @Test
    fun playingOnATv() {
        val commands = SyncJson.decodeFromString(ReceiveAnswer.serializer(), golden("remote_commands"))
        val (play, seek) = commands.commands
        assertEquals(RemoteAction.PLAY to 61_000L, play.action to play.positionMs)
        assertEquals("dQw4w9WgXcQ", play.item?.videoId)
        assertTrue(play.guest)
        assertEquals(RemoteAction.SEEK to null, seek.action to seek.item)
        sameJson(golden("remote_commands"), SyncJson.encodeToString(ReceiveAnswer.serializer(), commands))

        // What the app sends reads as the server's own example.
        val sent = listOf(
            "remote_receive" to { t: String -> SyncJson.encodeToString(ReceiveRequest.serializer(), SyncJson.decodeFromString(ReceiveRequest.serializer(), t)) },
            "remote_send" to { t: String -> SyncJson.encodeToString(SendRequest.serializer(), SyncJson.decodeFromString(SendRequest.serializer(), t)) },
            "remote_control" to { t: String -> SyncJson.encodeToString(ControlRequest.serializer(), SyncJson.decodeFromString(ControlRequest.serializer(), t)) },
            "remote_state" to { t: String -> SyncJson.encodeToString(StateReport.serializer(), SyncJson.decodeFromString(StateReport.serializer(), t)) },
        )
        sent.forEach { (name, roundTrip) -> golden(name).let { sameJson(it, roundTrip(it)) } }
        assertEquals(PlayState.PAUSED, SyncJson.decodeFromString(StateReport.serializer(), golden("remote_state")).state)

        val targets = SyncJson.decodeFromString(Targets.serializer(), golden("remote_targets")).targets
        assertEquals("Living room TV", targets.single().name)
        assertFalse(targets.single().own)
        assertEquals(PlayState.PLAYING, targets.single().nowPlaying?.state)
        val status = SyncJson.decodeFromString(RemoteStatus.serializer(), golden("remote_status"))
        assertTrue(status.online && status.generation == 3L)
        assertEquals("6f1c2d3e-4b5a-4c6d-8e7f-0a1b2c3d4e5f", SyncJson.decodeFromString(SendAnswer.serializer(), golden("remote_sent")).session)
    }
}
