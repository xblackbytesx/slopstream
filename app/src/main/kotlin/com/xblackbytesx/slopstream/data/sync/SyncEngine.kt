package com.xblackbytesx.slopstream.data.sync

import androidx.room.withTransaction
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.data.AccountStatus
import com.xblackbytesx.slopstream.data.Accounts
import com.xblackbytesx.slopstream.data.Clock
import com.xblackbytesx.slopstream.data.Sources
import com.xblackbytesx.slopstream.data.SubscriptionRepository
import com.xblackbytesx.slopstream.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** Makes the client for a connected server; a fake server in tests. */
fun interface SyncApiFactory {
    fun create(server: String, token: String): SyncApi
}

class OkHttpSyncApiFactory @Inject constructor(private val http: OkHttpClient) : SyncApiFactory {
    override fun create(server: String, token: String): SyncApi = SyncClient(http, server.toHttpUrl(), token)
}

/** What a sync brought that the rest of the app acts on. */
data class SyncOutcome(
    /** Channels followed on another device or the website: their feeds are not here yet. */
    val newChannels: List<Pair<SourceKey, String>> = emptyList(),
    /** Services whose session arrived from the server (signed in elsewhere). */
    val newSessions: List<SourceKey> = emptyList(),
)

/**
 * Keeps this device's library in step with the user's Slopstream server (AGENTS.md decision
 * 18). The server leads: whatever it answers is applied as is, except rows changed here while
 * the request was out, which stay unsynced and go up next time. Without a server nothing here
 * runs and the app works as before.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val db: AppDatabase,
    private val store: SyncStore,
    private val accounts: Accounts,
    private val sources: Sources,
    private val subscriptions: SubscriptionRepository,
    private val clock: Clock,
    private val apis: SyncApiFactory,
) {
    private val dao = db.sync()
    private val mutex = Mutex()
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()
    val config: Flow<SyncConfig> = store.config

    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Something wants a sync soon (a sign-in, a sign-out); the scheduler listens. */
    val requests: SharedFlow<Unit> = _requests.asSharedFlow()

    private val signInKeys: List<SourceKey> get() = sources.keys.filter { sources[it].capabilities.needsLogin }

    /**
     * Connects with a token for [person]. [replace]: this device's library is dropped for the
     * server's; otherwise all of it goes up and the server's merge rule decides row by row.
     */
    suspend fun connect(server: String, token: String, person: Person, replace: Boolean) = mutex.withLock {
        withContext(Dispatchers.IO) { store.setToken(token) }
        db.withTransaction {
            if (replace) {
                dao.clearSubscriptions()
                dao.clearWatchLater()
                dao.clearWatchState()
            } else {
                // Revs from any earlier server mean nothing to this one.
                dao.resendSubscriptions()
                dao.resendWatchLater()
                dao.resendWatchState()
            }
        }
        store.update {
            SyncConfig(server = server, personName = person.name, personEmail = person.email, adoptLocalSessions = true)
        }
        _requests.tryEmit(Unit)
    }

    /** Stops syncing; the library and the sign-ins stay on this device. */
    suspend fun disconnect() = mutex.withLock {
        withContext(Dispatchers.IO) { store.setToken(null) }
        store.update { SyncConfig() }
    }

    /** This device signed in to [key]: the server (and so the other devices) should have it. */
    suspend fun sessionSignedIn(key: SourceKey) = queue(key, SessionOp.PUT)

    /** This device signed out of [key] at the service: everywhere, then. */
    suspend fun sessionSignedOut(key: SourceKey) = queue(key, SessionOp.DELETE)

    private suspend fun queue(key: SourceKey, op: SessionOp) {
        if (!store.current().connected) return
        store.update { it.copy(pending = it.pending + (key.id to op)) }
        _requests.tryEmit(Unit)
    }

    /** The service refused this device's session: tell the server which one, then sync. */
    suspend fun reportExpired(key: SourceKey): SyncOutcome? {
        val api = api() ?: return null
        val secret = accounts.secret(key) ?: return null
        try {
            api.reportExpired(key.id, ExpiredReport(fingerprint(secret)))
        } catch (e: SyncException) {
            store.update { it.copy(lastError = e.message) }
            return null
        }
        return sync()
    }

    suspend fun connected(): Boolean = store.current().connected

    /** Long-poll ([SyncApi.changes]); null when not connected or the server could not be asked. */
    suspend fun waitForChanges(since: Long): ChangesAnswer? {
        val api = api() ?: return null
        return try {
            api.changes(since)
        } catch (e: SyncException) {
            null
        }
    }

    private suspend fun api(): SyncApi? {
        val server = store.current().server ?: return null
        val token = withContext(Dispatchers.IO) { store.token() } ?: return null
        return apis.create(server, token)
    }

    /** One full sync; null when not connected or when it failed (the error is in [config]). */
    suspend fun sync(): SyncOutcome? = mutex.withLock {
        val api = api() ?: return@withLock null
        _syncing.value = true
        try {
            val outcome = run(api)
            store.update { it.copy(lastSyncAt = clock.now(), lastError = null) }
            outcome
        } catch (e: SyncException) {
            store.update { it.copy(lastError = e.message) }
            null
        } finally {
            _syncing.value = false
        }
    }

    private suspend fun run(api: SyncApi): SyncOutcome {
        flushSessions(api)
        val channels = ArrayList<Pair<SourceKey, String>>()
        val sessions = ArrayList<SourceKey>()
        var resets = 0
        for (round in 0 until MAX_ROUNDS) {
            val subs = dao.unsyncedSubscriptions(MAX_CHANGES)
            val later = dao.unsyncedWatchLater(MAX_CHANGES)
            val states = dao.unsyncedWatchState(MAX_CHANGES)
            val request = SyncRequest(
                cursor = store.current().cursor,
                clientNow = clock.now(),
                subscriptions = subs.map { it.toWire() },
                watchLater = later.map { it.toWire() },
                watchState = states.map { it.toWire() },
                sessionFingerprints = signInKeys.mapNotNull { k -> accounts.secret(k)?.let { k.id to fingerprint(it) } }.toMap(),
            )
            val response = api.sync(request)
            if (response.reset) {
                if (resets++ > 0) throw SyncException("The sync server keeps asking this device to start over")
                db.withTransaction {
                    dao.resendSubscriptions()
                    dao.resendWatchLater()
                    dao.resendWatchState()
                }
                store.update { it.copy(cursor = 0) }
                continue
            }
            db.withTransaction {
                // What was sent is on the server now; a row edited meanwhile stays unsynced.
                subs.forEach { dao.sentSubscription(it.source, it.remoteId, it.localVersion) }
                later.forEach { dao.sentWatchLater(it.source, it.remoteId, it.localVersion) }
                states.forEach { dao.sentWatchState(it.source, it.remoteId, it.localVersion) }
                // A row edited here meanwhile keeps the edit. If the server took what was sent,
                // the edit builds on that rev; if not, the next round is a conflict, as it should be.
                val sentSubs = subs.associate { (it.source to it.remoteId) to it.toWire().content() }
                response.subscriptions.forEach { w ->
                    val old = dao.subscription(w.source, w.remoteId)
                    if (old != null && old.localVersion > old.syncedVersion) {
                        if (sentSubs[w.source to w.remoteId] == w.content()) dao.rebaseSubscription(w.source, w.remoteId, w.rev)
                        return@forEach
                    }
                    if (w.deletedAt == null && (old == null || old.deletedAt != null)) {
                        SourceKey.entries.firstOrNull { it.id == w.source }?.let { channels += it to w.remoteId }
                    }
                    dao.putSubscription(w.toEntity(old))
                }
                val sentLater = later.associate { (it.source to it.remoteId) to it.toWire().content() }
                response.watchLater.forEach { w ->
                    val old = dao.watchLater(w.source, w.remoteId)
                    if (old != null && old.localVersion > old.syncedVersion) {
                        if (sentLater[w.source to w.remoteId] == w.content()) dao.rebaseWatchLater(w.source, w.remoteId, w.rev)
                    } else {
                        dao.putWatchLater(w.toEntity(old))
                    }
                }
                val sentStates = states.associate { (it.source to it.remoteId) to it.toWire().content() }
                response.watchState.forEach { w ->
                    val old = dao.watchState(w.source, w.remoteId)
                    if (old != null && old.localVersion > old.syncedVersion) {
                        if (sentStates[w.source to w.remoteId] == w.content()) dao.rebaseWatchState(w.source, w.remoteId, w.rev)
                    } else {
                        dao.putWatchState(w.toEntity(old))
                    }
                }
            }
            store.update { it.copy(cursor = response.cursor) }
            sessions += applySessions(api, response.sessions)
            val full = subs.size == MAX_CHANGES || later.size == MAX_CHANGES || states.size == MAX_CHANGES
            if (!response.more && !full) break
            if (round == MAX_ROUNDS - 1) _requests.tryEmit(Unit)
        }
        return SyncOutcome(channels.distinct(), sessions.distinct())
    }

    /** Session changes made here go up before the server's list is applied. */
    private suspend fun flushSessions(api: SyncApi) {
        for ((id, op) in store.current().pending) {
            val key = SourceKey.entries.firstOrNull { it.id == id }
            try {
                when {
                    key == null -> {}
                    op == SessionOp.PUT -> accounts.secret(key)?.let { api.putSession(id, SessionPut(it, accounts.account(key).username)) }
                    else -> api.deleteSession(id, atService = false)
                }
            } catch (e: SyncRefusedException) {
                // The server checked it and the service said no: nothing to keep trying.
                if (key != null && op == SessionOp.PUT) accounts.markExpired(key, report = false)
            }
            store.update { it.copy(pending = it.pending - id) }
        }
    }

    /** The server's sessions replace this device's. Returns the services newly signed in. */
    private suspend fun applySessions(api: SyncApi, list: List<SessionInfo>): List<SourceKey> {
        val config = store.current()
        val arrived = ArrayList<SourceKey>()
        var adopted = false
        for (key in signInKeys) {
            if (key.id in config.pending) continue
            val s = list.firstOrNull { it.source == key.id }
            val local = accounts.secret(key)
            val account = accounts.account(key)
            when {
                s == null && local != null && config.adoptLocalSessions -> {
                    store.update { it.copy(pending = it.pending + (key.id to SessionOp.PUT)) }
                    adopted = true
                }
                // Signed out on another device or the website.
                s == null && local != null -> forget(key)
                s == null -> {}
                s.value != null -> {
                    accounts.save(key, s.value, s.username ?: account.username.orEmpty())
                    if (s.status == "expired") accounts.markExpired(key, report = false) else arrived += key
                }
                s.status == "expired" -> accounts.markExpired(key, report = false)
                // Renewed elsewhere with the same session, or the server's check passed again.
                account.status == AccountStatus.EXPIRED && local != null ->
                    accounts.save(key, local, s.username ?: account.username.orEmpty())
            }
        }
        if (config.adoptLocalSessions) store.update { it.copy(adoptLocalSessions = false) }
        if (adopted) flushSessions(api)
        return arrived
    }

    /** This device's copy of a session that ended everywhere. */
    private suspend fun forget(key: SourceKey) {
        if (sources[key].capabilities.accountSubscriptions) subscriptions.syncAccount(key, emptyList())
        accounts.clear(key)
    }

    private companion object {
        /** Rounds per sync (paging, or more than MAX_CHANGES unsynced rows of a kind). */
        const val MAX_ROUNDS = 20
    }
}
