package com.cleaningbutton.r2finance.data

import com.cleaningbutton.r2finance.data.repository.LedgerRepository
import com.cleaningbutton.r2finance.data.repository.TransactionRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-scoped Categorization list cache.
 *
 * Bottom-nav remounts [com.cleaningbutton.r2finance.ui.inbox.InboxScreen] on every
 * tab switch. Collecting Room there with `initialValue = emptyList()` flashed
 * "All clear" until the Flow re-emitted, then a network [CloudSync.pullInbox]
 * refill. This store:
 * - Subscribes to Room once and keeps the last list for the process lifetime
 * - Exposes [ready] so UI only shows true-empty after the first Room emission
 * - Lets the screen paint immediately from local storage while delta sync runs
 *   in the background
 */
class InboxCache(
    private val ledger: LedgerRepository,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var observeJob: Job? = null
    private var planId: String? = null

    private val _rows = MutableStateFlow<List<TransactionRow>>(emptyList())
    val rows: StateFlow<List<TransactionRow>> = _rows.asStateFlow()

    /** False until Room has emitted at least once for the active plan. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /**
     * Process-scoped: at most one automatic empty-inbox cloud heal per cold start.
     * Survives bottom-nav remounts so we do not hammer /v1/inbox every tab switch
     * when the server truly has zero needs-attention rows.
     */
    @Volatile
    var emptyInboxHealAttempted: Boolean = false

    /**
     * Start (or switch) the long-lived Room subscription.
     * Safe to call from every screen entry — no-ops when already watching [planId].
     */
    fun start(planId: String) {
        if (this.planId == planId && observeJob?.isActive == true) return
        scope.launch {
            mutex.withLock {
                if (this@InboxCache.planId == planId && observeJob?.isActive == true) return@withLock
                val switched =
                    this@InboxCache.planId != null && this@InboxCache.planId != planId
                this@InboxCache.planId = planId
                observeJob?.cancel()
                if (switched) {
                    // Rare plan change — drop stale rows; same-plan remount keeps cache.
                    _rows.value = emptyList()
                    _ready.value = false
                }
                // Same plan (tab switch): keep last Room snapshot painted until Flow re-binds.
                observeJob = scope.launch {
                    ledger.observeInboxRows(planId).collect { list ->
                        _rows.value = list
                        _ready.value = true
                    }
                }
            }
        }
    }
}
