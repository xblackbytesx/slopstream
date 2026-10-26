package com.xblackbytesx.slopstream.data.sync

import com.xblackbytesx.slopstream.data.db.SubscriptionEntity
import com.xblackbytesx.slopstream.data.db.WatchLaterEntity
import com.xblackbytesx.slopstream.data.db.WatchStateEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/*
 * The sync server's /api/v1 wire format (slopstream-server, `slopstream_core::wire`), pinned
 * by the server's golden JSON; SyncContractTest parses copies in src/test/resources/sync/.
 * Top-level classes only: a private @Serializable class with a companion decodes on the JVM
 * but fails silently on Android (see AGENTS.md).
 */

const val PROTOCOL = 1

/** Most changes of one kind per request, as the server accepts them. */
const val MAX_CHANGES = 500

/** Nulls are left out (the server reads a missing field as null); defaults are written. */
val SyncJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** hex SHA-256: how a session is named without sending it, as the server computes it. */
fun fingerprint(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }

@Serializable
data class SyncRequest(
    val protocol: Int = PROTOCOL,
    val cursor: Long,
    val clientNow: Long,
    val subscriptions: List<SubscriptionWire> = emptyList(),
    val watchLater: List<WatchLaterWire> = emptyList(),
    val watchState: List<WatchStateWire> = emptyList(),
    val sessionFingerprints: Map<String, String> = emptyMap(),
)

@Serializable
data class SyncResponse(
    val protocol: Int,
    val cursor: Long,
    val more: Boolean,
    /** The server is behind this device (restored, or another server): resend everything. */
    val reset: Boolean = false,
    val serverNow: Long,
    val subscriptions: List<SubscriptionWire> = emptyList(),
    val watchLater: List<WatchLaterWire> = emptyList(),
    val watchState: List<WatchStateWire> = emptyList(),
    /** Always the full list: a service missing here has no session on the server. */
    val sessions: List<SessionInfo> = emptyList(),
)

/** A row going up carries [baseRev] (the rev it was based on); a row coming down, [rev]. */
@Serializable
data class SubscriptionWire(
    val baseRev: Long? = null,
    val rev: Long? = null,
    val source: String,
    val remoteId: String,
    val name: String,
    val avatarUrl: String? = null,
    val favorite: Boolean,
    val addedAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val deviceId: String,
)

@Serializable
data class WatchLaterWire(
    val baseRev: Long? = null,
    val rev: Long? = null,
    val source: String,
    val remoteId: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val thumbnailUrl: String? = null,
    val durationSec: Long? = null,
    val publishedAt: Long,
    val kind: String,
    val addedAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val deviceId: String,
)

@Serializable
data class WatchStateWire(
    val baseRev: Long? = null,
    val rev: Long? = null,
    val source: String,
    val remoteId: String,
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    val title: String? = null,
    val channelId: String? = null,
    val channelName: String? = null,
    val thumbnailUrl: String? = null,
    val kind: String? = null,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val deviceId: String,
)

@Serializable
data class SessionInfo(
    val source: String,
    /** ok, expired or unchecked. */
    val status: String,
    val username: String? = null,
    val fingerprint: String,
    /** Only when this device's fingerprint differs. */
    val value: String? = null,
    val updatedAt: Long,
)

@Serializable
data class SessionPut(val value: String, val username: String? = null)

@Serializable
data class ExpiredReport(val fingerprint: String)

@Serializable
data class PairStart(val deviceName: String)

@Serializable
data class PairStarted(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSec: Long,
    val intervalSec: Long,
)

@Serializable
data class PairPoll(val deviceCode: String)

@Serializable
data class PairResult(
    /** pending, approved, denied or expired. */
    val status: String,
    val token: String? = null,
    val person: Person? = null,
)

@Serializable
data class Person(val name: String, val email: String)

/** `POST /api/v1/feed`: channel (`"<source>:<id>"`) to the highest feed rev held for it. */
@Serializable
data class FeedRequest(val protocol: Int = PROTOCOL, val channels: Map<String, Long>)

@Serializable
data class FeedResponse(
    val protocol: Int,
    /** More waiting: until false, a channel's rev is the highest received, not its status rev. */
    val more: Boolean,
    val items: List<FeedRow> = emptyList(),
    /** Channels the server fetches; one missing here is this device's to fetch. */
    val channels: List<ChannelStatus> = emptyList(),
    /** YouTube channels this device is asked to look up durations for (leased to it alone). */
    val durationJobs: List<String> = emptyList(),
)

/** `POST /api/v1/feed/durations`: a duration job's result, video id to seconds. */
@Serializable
data class FeedDurations(val channelId: String, val durations: Map<String, Long>)

@Serializable
data class DurationsStored(val updated: Long)

/** `GET /api/v1/changes`: long-poll; on [changed], sync and pull, then wait with [generation]. */
@Serializable
data class ChangesAnswer(val generation: Long, val changed: Boolean)

@Serializable
data class FeedRow(
    val rev: Long,
    val source: String,
    val remoteId: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val publishedAt: Long,
    val kind: String,
    val thumbnailUrl: String? = null,
    val durationSec: Long? = null,
    /** Gone (an ended livestream). */
    val deleted: Boolean = false,
)

@Serializable
data class ChannelStatus(
    val source: String,
    val remoteId: String,
    val name: String? = null,
    /** The server's last good fetch; null: never. */
    val lastOkAt: Long? = null,
    val lastError: String? = null,
    val rev: Long,
)

@Serializable
data class FeedRefresh(val fetched: Long, val pending: Long)

fun SubscriptionEntity.toWire() = SubscriptionWire(
    baseRev = rev ?: 0,
    source = source, remoteId = remoteId, name = name, avatarUrl = avatarUrl, favorite = favorite,
    addedAt = addedAt, updatedAt = updatedAt, deletedAt = deletedAt, deviceId = deviceId,
)

fun WatchLaterEntity.toWire() = WatchLaterWire(
    baseRev = rev ?: 0,
    source = source, remoteId = remoteId, title = title, channelId = channelId, channelName = channelName,
    thumbnailUrl = thumbnailUrl, durationSec = durationSec, publishedAt = publishedAt, kind = kind,
    addedAt = addedAt, updatedAt = updatedAt, deletedAt = deletedAt, deviceId = deviceId,
)

fun WatchStateEntity.toWire() = WatchStateWire(
    baseRev = rev ?: 0,
    source = source, remoteId = remoteId, positionMs = positionMs, durationMs = durationMs, watched = watched,
    title = title, channelId = channelId, channelName = channelName, thumbnailUrl = thumbnailUrl, kind = kind,
    updatedAt = updatedAt, deletedAt = deletedAt, deviceId = deviceId,
)

/** The row apart from who wrote it when, and its revs: what a merge compares. */
fun SubscriptionWire.content() = copy(baseRev = null, rev = null, updatedAt = 0, deviceId = "")
fun WatchLaterWire.content() = copy(baseRev = null, rev = null, updatedAt = 0, deviceId = "")
fun WatchStateWire.content() = copy(baseRev = null, rev = null, updatedAt = 0, deviceId = "")

/** The server's row, keeping this device's cache columns and marking it synced. */
fun SubscriptionWire.toEntity(old: SubscriptionEntity?) = SubscriptionEntity(
    source = source, remoteId = remoteId, name = name, avatarUrl = avatarUrl ?: old?.avatarUrl,
    addedAt = addedAt, updatedAt = updatedAt, deletedAt = deletedAt, deviceId = deviceId, favorite = favorite,
    lastRefreshAt = old?.lastRefreshAt, lastError = old?.lastError, feedRev = old?.feedRev ?: 0,
    rev = rev, localVersion = old?.localVersion ?: 0, syncedVersion = old?.localVersion ?: 0,
)

fun WatchLaterWire.toEntity(old: WatchLaterEntity?) = WatchLaterEntity(
    source = source, remoteId = remoteId, title = title, channelId = channelId, channelName = channelName,
    thumbnailUrl = thumbnailUrl, durationSec = durationSec, publishedAt = publishedAt, kind = kind,
    addedAt = addedAt, updatedAt = updatedAt, deletedAt = deletedAt, deviceId = deviceId,
    rev = rev, localVersion = old?.localVersion ?: 0, syncedVersion = old?.localVersion ?: 0,
)

fun WatchStateWire.toEntity(old: WatchStateEntity?) = WatchStateEntity(
    source = source, remoteId = remoteId, positionMs = positionMs, durationMs = durationMs, watched = watched,
    updatedAt = updatedAt, deviceId = deviceId,
    title = title, channelId = channelId, channelName = channelName, thumbnailUrl = thumbnailUrl, kind = kind,
    deletedAt = deletedAt,
    rev = rev, localVersion = old?.localVersion ?: 0, syncedVersion = old?.localVersion ?: 0,
)
