package com.xblackbytesx.slopstream.data.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The server's sync rules, in memory: revs, the merge rule (`slopstream_core::merge`, a
 * device wins a conflict only when more than 2 s newer), the echo of rows a device lost, and
 * sessions. The real thing is tested against Postgres in slopstream-server.
 */
class FakeSyncServer : SyncApi {
    private var rev = 0L
    val subscriptions = LinkedHashMap<String, SubscriptionWire>()
    val watchLater = LinkedHashMap<String, WatchLaterWire>()
    val watchState = LinkedHashMap<String, WatchStateWire>()

    /** Service id to (value, username, status). */
    val sessions = LinkedHashMap<String, Triple<String, String?, String>>()
    val expiredReports = ArrayList<String>()
    var syncs = 0

    /** Answer the next sync with `reset` (a server restored from an older backup). */
    var resetNext = false

    /** Runs while a sync request is "on the wire", before the server answers. */
    var during: (suspend () -> Unit)? = null

    private fun key(source: String, id: String) = "$source:$id"

    private fun <T> merge(map: MutableMap<String, T>, key: String, baseRev: Long, updatedAt: Long, rev: (T) -> Long, updated: (T) -> Long, write: (Long) -> T): Boolean {
        val server = map[key]
        val apply = server == null || baseRev >= rev(server) || updatedAt > updated(server) + 2_000
        if (apply) map[key] = write(++this.rev)
        return !apply
    }

    /** A change made by another device or the website. */
    fun put(row: SubscriptionWire) { subscriptions[key(row.source, row.remoteId)] = row.copy(baseRev = null, rev = ++rev) }

    override suspend fun sync(request: SyncRequest): SyncResponse {
        syncs++
        during?.let { during = null; it() }
        if (resetNext) {
            resetNext = false
            return SyncResponse(PROTOCOL, 0, more = false, reset = true, serverNow = 0)
        }
        val echo = HashSet<String>()
        request.subscriptions.forEach { c ->
            val k = key(c.source, c.remoteId)
            if (merge(subscriptions, k, c.baseRev ?: 0, c.updatedAt, { it.rev!! }, { it.updatedAt }) { c.copy(baseRev = null, rev = it) }) echo += k
        }
        request.watchLater.forEach { c ->
            val k = key(c.source, c.remoteId)
            if (merge(watchLater, k, c.baseRev ?: 0, c.updatedAt, { it.rev!! }, { it.updatedAt }) { c.copy(baseRev = null, rev = it) }) echo += k
        }
        request.watchState.forEach { c ->
            val k = key(c.source, c.remoteId)
            if (merge(watchState, k, c.baseRev ?: 0, c.updatedAt, { it.rev!! }, { it.updatedAt }) { c.copy(baseRev = null, rev = it) }) echo += k
        }
        val since = request.cursor
        return SyncResponse(
            protocol = PROTOCOL,
            cursor = maxOf(since, rev),
            more = false,
            serverNow = 0,
            subscriptions = subscriptions.filter { it.value.rev!! > since || it.key in echo }.values.toList(),
            watchLater = watchLater.filter { it.value.rev!! > since || it.key in echo }.values.toList(),
            watchState = watchState.filter { it.value.rev!! > since || it.key in echo }.values.toList(),
            sessions = sessions.map { (source, s) ->
                val fp = fingerprint(s.first)
                SessionInfo(source, s.third, s.second, fp, s.first.takeIf { request.sessionFingerprints[source] != fp }, 0)
            },
        )
    }

    override suspend fun putSession(source: String, put: SessionPut) {
        if (put.value.startsWith("bad")) throw SyncRefusedException("signed out")
        sessions[source] = Triple(put.value, put.username, "ok")
    }

    override suspend fun deleteSession(source: String, atService: Boolean) {
        sessions.remove(source)
    }

    override suspend fun reportExpired(source: String, report: ExpiredReport) {
        expiredReports += source
        val s = sessions[source] ?: return
        if (fingerprint(s.first) == report.fingerprint) sessions[source] = s.copy(third = "expired")
    }

    override suspend fun me(): Person = Person("Sam", "sam@example.com")

    /** The server's lists: rows and channel statuses, as its fetcher left them. */
    val feedRows = ArrayList<FeedRow>()
    val statuses = ArrayList<ChannelStatus>()
    val feedRequests = ArrayList<FeedRequest>()
    var feedRefreshes = 0
    var feedDown = false

    override suspend fun feed(request: FeedRequest): FeedResponse {
        if (feedDown) throw SyncException("down")
        feedRequests += request
        val items = feedRows.filter { r ->
            val cursor = request.channels["${r.source}:${r.channelId}"] ?: return@filter false
            r.rev > cursor
        }.sortedBy { it.rev }
        val channels = statuses.filter { "${it.source}:${it.remoteId}" in request.channels }
        return FeedResponse(PROTOCOL, more = false, items = items, channels = channels, durationJobs = durationJobs.toList())
    }

    override suspend fun refreshFeed(): FeedRefresh {
        feedRefreshes++
        return FeedRefresh(0, 0)
    }

    val durationJobs = ArrayList<String>()
    val durationReports = ArrayList<FeedDurations>()

    override suspend fun reportDurations(report: FeedDurations): DurationsStored {
        durationReports += report
        return DurationsStored(report.durations.size.toLong())
    }

    /** What the long-poll answers next, in order; an empty queue is an error. */
    val changesAnswers = ArrayDeque<ChangesAnswer>()
    val changesAsked = ArrayList<Long>()

    override suspend fun changes(since: Long): ChangesAnswer {
        changesAsked += since
        return changesAnswers.removeFirstOrNull() ?: throw SyncException("down")
    }
}

/** [SyncStore] in memory. */
class MemorySyncStore : SyncStore {
    private val state = MutableStateFlow(SyncConfig())
    private var token: String? = null
    override val config: Flow<SyncConfig> = state
    override suspend fun update(f: (SyncConfig) -> SyncConfig) { state.value = f(state.value) }
    override fun token(): String? = token
    override fun setToken(token: String?) { this.token = token }
}
