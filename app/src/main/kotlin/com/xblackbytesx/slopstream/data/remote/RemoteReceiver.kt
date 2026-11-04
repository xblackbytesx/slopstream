package com.xblackbytesx.slopstream.data.remote

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.data.AccountStatus
import com.xblackbytesx.slopstream.data.Accounts
import com.xblackbytesx.slopstream.data.sync.SyncEngine
import com.xblackbytesx.slopstream.data.sync.SyncException
import com.xblackbytesx.slopstream.data.sync.SyncRefusedException
import com.xblackbytesx.slopstream.device.DeviceCheck
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The TV's side of playing on a TV (decision 28): while the app is open on a TV connected to a
 * sync server, it waits on the server for commands. A [plays] command opens the player (the
 * navigation collects it); the rest steer the player of that session ([controls]). The player
 * reports back through [report], in order. Nothing runs on phones, without a server, or in the
 * background.
 */
@Singleton
class RemoteReceiver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: SyncEngine,
    private val accounts: Accounts,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playChannel = Channel<RemoteCommand>(4, BufferOverflow.DROP_OLDEST)
    private val steering = MutableSharedFlow<RemoteCommand>(extraBufferCapacity = 16)
    private val reports = Channel<StateReport>(64, BufferOverflow.DROP_OLDEST)
    private var loop: Job? = null

    /** Videos sent to this TV, each taken once. */
    val plays: Flow<RemoteCommand> = playChannel.receiveAsFlow()

    /** Pause, resume, seek and stop for [session]. */
    fun controls(session: String): Flow<RemoteCommand> = steering.filter { it.session == session }

    /** How the session plays; sent in order, dropped when no server is connected. */
    fun report(report: StateReport) {
        reports.trySend(report)
    }

    fun start() {
        if (!DeviceCheck.isTv(context)) return
        scope.launch {
            for (r in reports) {
                runCatching { engine.remote()?.report(r) }
            }
        }
        scope.launch {
            withContext(Dispatchers.Main) {
                ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        loop?.cancel()
                        loop = scope.launch { run() }
                    }

                    override fun onStop(owner: LifecycleOwner) {
                        loop?.cancel()
                        loop = null
                        // Off every phone's list at once, not 35 s later.
                        scope.launch { runCatching { engine.remote()?.offline() } }
                    }
                })
            }
        }
    }

    private suspend fun run() {
        var since: Long? = null
        while (scope.isActive) {
            engine.config.first { it.connected }
            val api = engine.remote()
            if (api == null) {
                delay(RETRY_MS)
                continue
            }
            try {
                val answer = api.receive(ReceiveRequest(since, services()))
                since = answer.seq
                answer.commands.forEach { c ->
                    if (c.action == RemoteAction.PLAY) playChannel.trySend(c) else steering.emit(c)
                }
            } catch (e: SyncRefusedException) {
                // A server from before playing on a TV: ask again much later.
                delay(OLD_SERVER_MS)
            } catch (e: SyncException) {
                delay(RETRY_MS)
            }
        }
    }

    /** What this TV can play now: YouTube, and every service it is signed in to. */
    private fun services(): List<String> = SourceKey.entries
        .filter { it == SourceKey.YOUTUBE || accounts.account(it).status == AccountStatus.SIGNED_IN }
        .map { it.id }

    private companion object {
        const val RETRY_MS = 10_000L
        const val OLD_SERVER_MS = 5 * 60_000L
    }
}
