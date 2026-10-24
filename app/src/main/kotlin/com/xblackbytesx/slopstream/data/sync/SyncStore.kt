package com.xblackbytesx.slopstream.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xblackbytesx.slopstream.data.TokenStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.syncStore: DataStore<Preferences> by preferencesDataStore("sync")

/** What a session change still owes the server, kept until it got there. */
enum class SessionOp { PUT, DELETE }

data class SyncConfig(
    /** Null: not connected. */
    val server: String? = null,
    val personName: String = "",
    val personEmail: String = "",
    val cursor: Long = 0,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    /** Until the first sync after connecting: local sessions the server lacks go up. */
    val adoptLocalSessions: Boolean = false,
    /** Service id to the session change the server has not heard about yet. */
    val pending: Map<String, SessionOp> = emptyMap(),
) {
    val connected: Boolean get() = server != null
}

/** Where the sync engine keeps its state. The token is not here: it lives in [TokenStore]. */
interface SyncStore {
    val config: Flow<SyncConfig>
    suspend fun current(): SyncConfig = config.first()
    suspend fun update(f: (SyncConfig) -> SyncConfig)
    fun token(): String?
    fun setToken(token: String?)
}

@Singleton
class DataStoreSyncStore @Inject constructor(
    @ApplicationContext context: Context,
    private val tokens: TokenStore,
) : SyncStore {
    private val store = context.syncStore

    private object Keys {
        val server = stringPreferencesKey("server")
        val name = stringPreferencesKey("person_name")
        val email = stringPreferencesKey("person_email")
        val cursor = longPreferencesKey("cursor")
        val lastSyncAt = longPreferencesKey("last_sync_at")
        val lastError = stringPreferencesKey("last_error")
        val adopt = booleanPreferencesKey("adopt_local_sessions")
        val pending = stringPreferencesKey("pending_sessions")
    }

    private fun Preferences.toConfig() = SyncConfig(
        server = this[Keys.server],
        personName = this[Keys.name].orEmpty(),
        personEmail = this[Keys.email].orEmpty(),
        cursor = this[Keys.cursor] ?: 0,
        lastSyncAt = this[Keys.lastSyncAt],
        lastError = this[Keys.lastError],
        adoptLocalSessions = this[Keys.adopt] ?: false,
        pending = this[Keys.pending].orEmpty().split(',').filter { ':' in it }.associate {
            val (k, v) = it.split(':', limit = 2)
            k to (runCatching { SessionOp.valueOf(v) }.getOrNull() ?: SessionOp.PUT)
        },
    )

    override val config: Flow<SyncConfig> = store.data.map { it.toConfig() }

    override suspend fun update(f: (SyncConfig) -> SyncConfig) {
        store.edit { p ->
            val new = f(p.toConfig())
            fun <T> put(key: Preferences.Key<T>, v: T?) {
                if (v == null) p.remove(key) else p[key] = v
            }
            put(Keys.server, new.server)
            put(Keys.name, new.personName)
            put(Keys.email, new.personEmail)
            put(Keys.cursor, new.cursor)
            put(Keys.lastSyncAt, new.lastSyncAt)
            put(Keys.lastError, new.lastError)
            put(Keys.adopt, new.adoptLocalSessions)
            put(Keys.pending, new.pending.entries.joinToString(",") { "${it.key}:${it.value}" })
        }
    }

    override fun token(): String? = runCatching { tokens.readToken() }.getOrNull()

    override fun setToken(token: String?) {
        if (token == null) tokens.deleteToken() else tokens.writeToken(token)
    }
}
