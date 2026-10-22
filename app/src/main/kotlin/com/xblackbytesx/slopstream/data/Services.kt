package com.xblackbytesx.slopstream.data

import com.xblackbytesx.slopstream.core.SourceCapabilities
import com.xblackbytesx.slopstream.core.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which service the list screens show (AGENTS.md decision 12: one at a time, never mixed).
 * A service that needs a login is listed once signed in, and stays listed while its session
 * is expired so its cached feed and the "sign in again" prompt remain reachable.
 */
@Singleton
class Services @Inject constructor(
    private val sources: Sources,
    private val accounts: Accounts,
    private val settings: Settings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun visibleFor(state: Map<SourceKey, Account>) = sources.keys.filter { k ->
        !sources[k].capabilities.needsLogin || (state[k]?.status ?: AccountStatus.SIGNED_OUT) != AccountStatus.SIGNED_OUT
    }

    val visible: StateFlow<List<SourceKey>> = accounts.state.map(::visibleFor)
        .stateIn(scope, SharingStarted.Eagerly, visibleFor(accounts.state.value))

    val current: StateFlow<SourceKey> = combine(visible, settings.settings.map { it.service }) { v, chosen ->
        chosen?.takeIf { it in v } ?: v.first()
    }.stateIn(scope, SharingStarted.Eagerly, visible.value.first())

    /** What a list screen needs to know about the service it shows. */
    val state: StateFlow<ServiceState> = combine(visible, current, accounts.state) { v, c, a -> stateOf(v, c, a) }
        .stateIn(scope, SharingStarted.Eagerly, stateOf(visible.value, current.value, accounts.state.value))

    private fun stateOf(visible: List<SourceKey>, current: SourceKey, accounts: Map<SourceKey, Account>) =
        ServiceState(visible, current, sources[current].capabilities, accounts[current] ?: Account())

    suspend fun select(source: SourceKey) = settings.setService(source)
}

data class ServiceState(
    val visible: List<SourceKey>,
    val current: SourceKey,
    val capabilities: SourceCapabilities,
    val account: Account,
) {
    /** Signed in once, rejected since: the cached feed shows, with a "sign in again" prompt. */
    val expired: Boolean get() = account.status == AccountStatus.EXPIRED
}
