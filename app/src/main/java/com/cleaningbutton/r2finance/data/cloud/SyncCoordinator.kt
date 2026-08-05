package com.cleaningbutton.r2finance.data.cloud

import android.content.Context
import com.cleaningbutton.r2finance.data.local.R2FinanceDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Offline-first cloud ↔ Room sync.
 *
 * Flow for airplane / hours offline:
 * 1. UI always reads/writes **Room** (works with zero network)
 * 2. Local edits → syncStatus = PENDING_PUSH
 * 3. When network returns → push PENDING_PUSH → DynamoDB
 * 4. Then pull DDB → Room
 * 5. YNAB sync is **backend-only** (EventBridge ~15m or optional tick) — not required for phone ops
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

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    suspend fun refreshLocalDataFlag(planId: String = DEFAULT_PLAN_ID) {
        val accounts = db.accountDao().countOpen(planId)
        val txns = db.transactionDao().countForPlan(planId)
        _hasLocalData.value = accounts > 0 || txns > 0
        _pendingCount.value = db.transactionDao().countPendingPush(planId)
    }

    /**
     * Local-first entry: show Room immediately; only full-hydrate when empty.
     * If there is already data, still try a light online flush (push pending) when possible.
     */
    suspend fun ensureHydrated(planId: String = DEFAULT_PLAN_ID): Result<CloudSyncReport?> =
        mutex.withLock {
            refreshLocalDataFlag(planId)
            if (!_hasLocalData.value) {
                return@withLock doFullSyncLocked(planId, pullYnab = false)
            }
            // Already have data — best-effort push of any offline work (no full re-download).
            doPushOnlyLocked(planId)
            Result.success(null)
        }

    /**
     * Called when network becomes available (or app cold-start online).
     * Push offline queue → DDB, then pull DDB → Room. YNAB later on backend.
     */
    suspend fun syncWhenOnline(planId: String = DEFAULT_PLAN_ID): Result<CloudSyncReport?> =
        mutex.withLock {
            refreshLocalDataFlag(planId)
            if (!_hasLocalData.value) {
                return@withLock doFullSyncLocked(planId, pullYnab = false)
            }
            doPushThenPullLocked(planId, pullYnab = false)
        }

    /**
     * Manual refresh (toolbar). Push pending, pull DDB, and tick YNAB on the server.
     */
    suspend fun refresh(planId: String = DEFAULT_PLAN_ID): Result<CloudSyncReport> =
        mutex.withLock {
            doFullSyncLocked(planId, pullYnab = true).map { it!! }
        }

    private suspend fun doPushOnlyLocked(planId: String): Result<Unit> {
        return try {
            val pending = db.transactionDao().countPendingPush(planId)
            if (pending == 0) return Result.success(Unit)
            _isSyncing.value = true
            _statusMessage.value = "Uploading $pending offline change(s)…"
            cloudSync.pushLocalPending(planId) { step -> _statusMessage.value = step }
            refreshLocalDataFlag(planId)
            _statusMessage.value = "Offline changes uploaded to cloud"
            Result.success(Unit)
        } catch (e: Exception) {
            // Stay silent-ish: still offline or flaky link; Room remains correct.
            _statusMessage.value = "Will retry upload: ${e.message}"
            Result.failure(e)
        } finally {
            _isSyncing.value = false
        }
    }

    private suspend fun doPushThenPullLocked(
        planId: String,
        pullYnab: Boolean,
    ): Result<CloudSyncReport?> {
        _isSyncing.value = true
        return try {
            val pending = db.transactionDao().countPendingPush(planId)
            if (pending > 0) {
                _statusMessage.value = "Uploading $pending offline change(s)…"
                runCatching {
                    cloudSync.pushLocalPending(planId) { step -> _statusMessage.value = step }
                }.onFailure {
                    _statusMessage.value = "Upload deferred: ${it.message}"
                }
            }
            _statusMessage.value = "Refreshing from cloud…"
            val report = cloudSync.syncFromCloud(pullYnab = pullYnab) { step ->
                _statusMessage.value = step
            }
            markSyncedOk(planId, report)
            Result.success(report)
        } catch (e: Exception) {
            _statusMessage.value = "Sync failed: ${e.message}"
            Result.failure(e)
        } finally {
            _isSyncing.value = false
        }
    }

    private suspend fun doFullSyncLocked(
        planId: String,
        pullYnab: Boolean,
    ): Result<CloudSyncReport?> {
        _isSyncing.value = true
        _statusMessage.value = "Syncing with cloud…"
        return try {
            val pending = db.transactionDao().countPendingPush(planId)
            if (pending > 0) {
                _statusMessage.value = "Uploading $pending offline change(s)…"
                runCatching {
                    cloudSync.pushLocalPending(planId) { step -> _statusMessage.value = step }
                }
            }
            val report = cloudSync.syncFromCloud(pullYnab = pullYnab) { step ->
                _statusMessage.value = step
            }
            markSyncedOk(planId, report)
            Result.success(report)
        } catch (e: Exception) {
            _statusMessage.value = "Sync failed: ${e.message}"
            Result.failure(e)
        } finally {
            _isSyncing.value = false
        }
    }

    private suspend fun markSyncedOk(planId: String, report: CloudSyncReport) {
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_LAST_SYNCED_AT, now).apply()
        _lastSyncedAt.value = now
        refreshLocalDataFlag(planId)
        val pending = _pendingCount.value
        _statusMessage.value =
            buildString {
                append("Synced “${report.planName}”: ${report.accounts} accounts, ")
                append("${report.transactions} transactions")
                if (pending > 0) append(" · $pending still pending upload")
            }
    }

    companion object {
        const val DEFAULT_PLAN_ID = "default"
        private const val PREFS = "r2finance_sync"
        private const val KEY_LAST_SYNCED_AT = "last_synced_at"
    }
}
