package com.xblackbytesx.slopstream.data.sync

import com.xblackbytesx.slopstream.data.remote.ControlRequest
import com.xblackbytesx.slopstream.data.remote.ReceiveAnswer
import com.xblackbytesx.slopstream.data.remote.ReceiveRequest
import com.xblackbytesx.slopstream.data.remote.RemoteStatus
import com.xblackbytesx.slopstream.data.remote.SendAnswer
import com.xblackbytesx.slopstream.data.remote.SendRequest
import com.xblackbytesx.slopstream.data.remote.StateReport
import com.xblackbytesx.slopstream.data.remote.Targets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** The server could not be reached or refused the request; [message] says why. */
open class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The server understood and said no (HTTP 4xx); retrying the same request will not help. */
class SyncRefusedException(message: String) : SyncException(message)

/** The token was revoked (or the person disabled): this device is no longer connected. */
class SyncUnauthorizedException : SyncException("The sync server no longer accepts this device. Connect it again.")

/** What the sync engine needs from the server; [SyncClient] in the app, a fake in tests. */
interface SyncApi {
    suspend fun sync(request: SyncRequest): SyncResponse
    suspend fun putSession(source: String, put: SessionPut)
    suspend fun deleteSession(source: String, atService: Boolean)
    suspend fun reportExpired(source: String, report: ExpiredReport)
    suspend fun me(): Person
    suspend fun feed(request: FeedRequest): FeedResponse
    suspend fun refreshFeed(): FeedRefresh
    suspend fun reportDurations(report: FeedDurations): DurationsStored

    /** Long-poll: answers within ~25 s, at once when [since] is not the server's number. */
    suspend fun changes(since: Long): ChangesAnswer
}

/** Playing on a TV (decision 28): the TV's side, then the sender's. */
interface RemoteApi {
    /** Long-poll for commands: answers within ~25 s, at once when some are waiting. */
    suspend fun receive(request: ReceiveRequest): ReceiveAnswer
    suspend fun offline()
    suspend fun report(report: StateReport)
    suspend fun targets(): Targets
    suspend fun send(target: String, request: SendRequest): SendAnswer
    suspend fun control(target: String, request: ControlRequest)

    /** Long-poll on what [target] plays: at once when [since] is not its number. */
    suspend fun status(target: String, since: Long?): RemoteStatus
}

/**
 * HTTP to a Slopstream server. The token goes only to [base], in the Authorization header;
 * nothing here is logged. [token] null: pairing only.
 */
class SyncClient(private val http: OkHttpClient, private val base: HttpUrl, private val token: String? = null) : SyncApi, RemoteApi {
    override suspend fun sync(request: SyncRequest): SyncResponse =
        call("POST", "sync", body(SyncRequest.serializer(), request), SyncResponse.serializer())

    override suspend fun putSession(source: String, put: SessionPut) {
        call<Unit>("PUT", "sessions/$source", body(SessionPut.serializer(), put), null)
    }

    override suspend fun deleteSession(source: String, atService: Boolean) {
        call<Unit>("DELETE", "sessions/$source?atService=$atService", null, null)
    }

    override suspend fun reportExpired(source: String, report: ExpiredReport) {
        call<Unit>("POST", "sessions/$source/expired", body(ExpiredReport.serializer(), report), null)
    }

    override suspend fun me(): Person = call("GET", "me", null, Person.serializer())

    override suspend fun feed(request: FeedRequest): FeedResponse =
        call("POST", "feed", body(FeedRequest.serializer(), request), FeedResponse.serializer())

    override suspend fun refreshFeed(): FeedRefresh = call("POST", "feed/refresh", null, FeedRefresh.serializer())

    override suspend fun reportDurations(report: FeedDurations): DurationsStored =
        call("POST", "feed/durations", body(FeedDurations.serializer(), report), DurationsStored.serializer())

    override suspend fun changes(since: Long): ChangesAnswer =
        call("GET", if (since >= 0) "changes?since=$since" else "changes", null, ChangesAnswer.serializer(), waiting)

    override suspend fun receive(request: ReceiveRequest): ReceiveAnswer =
        call("POST", "remote/receive", body(ReceiveRequest.serializer(), request), ReceiveAnswer.serializer(), waiting)

    override suspend fun offline() {
        call<Unit>("POST", "remote/offline", null, null)
    }

    override suspend fun report(report: StateReport) {
        call<Unit>("POST", "remote/state", body(StateReport.serializer(), report), null)
    }

    override suspend fun targets(): Targets = call("GET", "remote/targets", null, Targets.serializer())

    override suspend fun send(target: String, request: SendRequest): SendAnswer =
        call("POST", "remote/$target/play", body(SendRequest.serializer(), request), SendAnswer.serializer())

    override suspend fun control(target: String, request: ControlRequest) {
        call<Unit>("POST", "remote/$target/control", body(ControlRequest.serializer(), request), null)
    }

    override suspend fun status(target: String, since: Long?): RemoteStatus =
        call("GET", if (since != null) "remote/$target/status?since=$since" else "remote/$target/status", null, RemoteStatus.serializer(), waiting)

    /** The long-polls are held up to ~25 s: a read timeout beyond that, for those calls only. */
    private val waiting by lazy { http.newBuilder().readTimeout(40, TimeUnit.SECONDS).build() }

    suspend fun pairStart(deviceName: String): PairStarted =
        call("POST", "pair/start", body(PairStart.serializer(), PairStart(deviceName)), PairStarted.serializer())

    suspend fun pairPoll(deviceCode: String): PairResult =
        call("POST", "pair/poll", body(PairPoll.serializer(), PairPoll(deviceCode)), PairResult.serializer())

    private fun <T> body(serializer: KSerializer<T>, value: T): RequestBody =
        SyncJson.encodeToString(serializer, value).toRequestBody(JSON)

    /** [serializer] null: the answer is ignored and `T` is Unit. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> call(
        method: String,
        path: String,
        body: RequestBody?,
        serializer: KSerializer<T>?,
        client: OkHttpClient = http,
    ): T =
        withContext(Dispatchers.IO) {
            val url = base.resolve("api/v1/$path") ?: throw SyncException("Bad server address")
            val builder = Request.Builder().url(url).method(method, body ?: if (method == "GET") null else ByteArray(0).toRequestBody())
                .header("Accept", "application/json")
            token?.let { builder.header("Authorization", "Bearer $it") }
            try {
                client.newCall(builder.build()).execute().use { r ->
                    val text = r.body.string()
                    when {
                        r.code == 401 -> throw SyncUnauthorizedException()
                        r.code in 400..499 -> throw SyncRefusedException(errorMessage(text) ?: "The sync server refused (HTTP ${r.code})")
                        !r.isSuccessful -> throw SyncException(errorMessage(text) ?: "The sync server answered HTTP ${r.code}")
                    }
                    if (serializer == null) Unit as T else SyncJson.decodeFromString(serializer, text)
                }
            } catch (e: IOException) {
                throw SyncException("Could not reach the sync server: ${e.message}", e)
            } catch (e: IllegalArgumentException) {
                throw SyncException("The sync server sent something unexpected: ${e.message}", e)
            }
        }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** `{"error": "..."}` from the server. */
        private fun errorMessage(text: String): String? =
            runCatching { SyncJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()

        /**
         * The server address as typed: https, or plain http only to a private LAN address
         * (where [needsWarning] is true). Null when it cannot be one.
         */
        fun parseServer(input: String): HttpUrl? {
            val text = input.trim().let { if ("://" in it) it else "https://$it" }
            val url = text.toHttpUrlOrNull() ?: return null
            if (url.scheme == "http" && !isPrivate(url.host)) return null
            return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").query(null).fragment(null).build()
        }

        fun needsWarning(url: HttpUrl): Boolean = url.scheme == "http"

        /** A LAN or loopback address, by literal or by `.local`/`.lan` name; never a lookup. */
        fun isPrivate(host: String): Boolean {
            if (host.endsWith(".local") || host.endsWith(".lan") || host == "localhost") return true
            val literal = Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host) || ':' in host
            if (!literal) return false
            val a = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            return a.isSiteLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress ||
                (a.address.size == 16 && (a.address[0].toInt() and 0xfe) == 0xfc)
        }
    }
}
