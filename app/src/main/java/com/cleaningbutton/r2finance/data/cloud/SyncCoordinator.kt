package com.cleaningbutton.r2finance.data.cloud

import android.content.Context
import com.cleaningbutton.r2finance.data.local.R2FinanceDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-level cloud ↔ Room sync.
 *
 * UI must always read from Room. This coordinator:
 * - hydrates Room from DDB only when empty (first install / wiped DB)
 * - allows manual force refresh
 * - single-flights concurrent pulls so navigation never stacks full re-downloads
 * - survives Compose dispose/recreate (Accounts → register → back)
 */
class SyncCoordinator(
    context: Context,
    private val db: R2FinanceDatabase,
    private val cloudSync: CloudSync,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _lastSyncedAt = MutableStateFlow(prefs.getLong(KEY_LAST_SYNCED_AT, 0L))
    val lastSyncedAt: StateFlow<Long> = _lastSyncedAt.asStateFlow()

    private val _hasLocalData = MutableStateFlow(false)
    val hasLocalData: StateFlow<Boolean> = _hasLocalData.asStateFlow()

    /** Refresh local-data flag (cheap counts). Call after ensureDefaultPlan. */
    suspend fun refreshLocalDataFlag(planId: String = DEFAULT_PLAN_ID) {
        val accounts = db.accountDao().countOpen(planId)
        val txns = db.transactionDao().countForPlan(planId)
        _hasLocalData.value = accounts > 0 || txns > 0
    }

    /**
     * Local-first entry: show Room immediately; only pull if Room has no ledger data.
     * Safe to call on every Accounts/Inbox entry — no-ops when already hydrated.
     * Re-checks emptiness under the mutex so concurrent callers do not double-pull.
     */
    suspend fun ensureHydrated(planId: String = DEFAULT_PLAN_ID): Result<CloudSyncReport?> =
        mutex.withLock {
            refreshLocalDataFlag(planId)
            if (_hasLocalData.value) {
                return@withLock Result.success(null)
            }
            doPullLocked(planId)
        }

    /**
     * Manual refresh (toolbar). Always hits the network.
     * While downloading, Room keeps serving previous data.
     */
    suspend fun refresh(planId: String = DEFAULT_PLAN_ID): Result<CloudSyncReport> =
        mutex.withLock {
            doPullLocked(planId).map { it!! }
        }

    private suspend fun doPullLocked(planId: String): Result<CloudSyncReport?> {
        _isSyncing.value = true
        _statusMessage.value = "Syncing from cloud…"
        return try {
            val report = cloudSync.syncFromCloud(pullYnab = true) { step ->
                _statusMessage.value = step
            }
            val now = System.currentTimeMillis()
            prefs.edit().putLong(KEY_LAST_SYNCED_AT, now).apply()
            _lastSyncedAt.value = now
            refreshLocalDataFlag(planId)
            _statusMessage.value =
                "Synced “${report.planName}”: ${report.accounts} accounts, " +
                    "${report.transactions} transactions"
            Result.success(report)
        } catch (e: Exception) {
            _statusMessage.value = "Sync failed: ${e.message}"
            Result.failure(e)
        } finally {
            _isSyncing.value = false
        }
    }

    companion object {
        const val DEFAULT_PLAN_ID = "default"
        private const val PREFS = "r2finance_sync"
        private const val KEY_LAST_SYNCED_AT = "last_synced_at"
    }
}
