package com.cleaningbutton.r2finance.data

import com.cleaningbutton.r2finance.data.cloud.CloudApi
import com.cleaningbutton.r2finance.data.cloud.CloudConnectorStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-scoped bank-connector list (Accounts tab).
 *
 * Same strategy as [InboxCache]: bottom-nav remounts must paint the last
 * snapshot from RAM. Network fetch runs once at warm (or manual refresh),
 * never on every tab enter after ready.
 */
class ConnectorsCache(
    private val cloudApi: CloudApi,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var warmJob: Job? = null

    private val _connectors = MutableStateFlow<List<CloudConnectorStatus>>(emptyList())
    val connectors: StateFlow<List<CloudConnectorStatus>> = _connectors.asStateFlow()

    /** True after at least one successful fetch this process. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** First load only (no cache yet). */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Manual / balance probe refresh while cache may already be painted. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    /**
     * Idempotent warm: first call loads connectors (+ optional balance probe);
     * later tab enters no-op when [ready].
     */
    fun ensureWarm(probeBalancesIfNeeded: Boolean = true) {
        if (_ready.value) return
        if (warmJob?.isActive == true) return
        warmJob =
            scope.launch {
                load(probeBalancesIfNeeded = probeBalancesIfNeeded, force = false)
            }
    }

    /** Manual toolbar refresh — always hits network. */
    fun refresh(probeBalances: Boolean) {
        scope.launch {
            load(probeBalancesIfNeeded = probeBalances, force = true)
        }
    }

    private suspend fun load(
        probeBalancesIfNeeded: Boolean,
        force: Boolean,
    ) {
        mutex.withLock {
            if (!force && _ready.value) return@withLock
            val first = !_ready.value
            if (first) {
                _loading.value = true
            } else {
                _refreshing.value = true
            }
            _error.value = null
            try {
                var list = fetchConnectors()
                _connectors.value = list
                _ready.value = true

                // Auto-probe when cache has names but null balances; always on manual refresh.
                val shouldProbe =
                    probeBalancesIfNeeded && (force || needsBalanceProbe(list))
                if (shouldProbe) {
                    _statusMessage.value =
                        if (force) "Refreshing balances from Plaid…"
                        else "Loading balances from banks…"
                    val res =
                        if (force) {
                            cloudApi.refreshConnectorBalances()
                        } else {
                            runCatching { cloudApi.refreshConnectorBalances() }.getOrNull()
                        }
                    if (res != null) {
                        val ok = res.results.count { it.ok == true }
                        val fail = res.results.count { it.ok == false }
                        _statusMessage.value =
                            "Updated $ok connector(s)" + if (fail > 0) " · $fail failed" else ""
                    }
                    list = fetchConnectors()
                    _connectors.value = list
                } else if (force) {
                    _statusMessage.value = "Connectors updated"
                }
            } catch (e: Exception) {
                _error.value = e.message ?: e.toString()
                if (force) {
                    _statusMessage.value = e.message ?: e.toString()
                }
            } finally {
                _loading.value = false
                _refreshing.value = false
            }
        }
    }

    private suspend fun fetchConnectors(): List<CloudConnectorStatus> {
        val hh = runCatching { cloudApi.getHouseholdConnectors() }.getOrNull()
        return if (hh != null && hh.users.isNotEmpty()) {
            flattenHousehold(hh.users.map { it.email to it.connectors })
        } else {
            cloudApi.getConnectors().connectors
        }
    }

    companion object {
        fun flattenHousehold(
            users: List<Pair<String, List<CloudConnectorStatus>>>,
        ): List<CloudConnectorStatus> =
            users.flatMap { (email, list) ->
                list.map { c ->
                    if (c.email.isNullOrBlank()) c.copy(email = email) else c
                }
            }

        fun needsBalanceProbe(list: List<CloudConnectorStatus>): Boolean {
            val connected = list.filter { it.connected }
            if (connected.isEmpty()) return false
            val previews = connected.flatMap { it.accountsPreview }
            if (previews.isEmpty()) return true
            // DDB connector cache often has names but null available/current
            // until /refresh-balances runs once.
            return previews.all { it.available == null && it.current == null }
        }
    }
}
