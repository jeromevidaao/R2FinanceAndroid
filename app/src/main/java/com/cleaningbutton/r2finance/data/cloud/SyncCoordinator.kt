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
 * 4. Then **delta** pull DDB → Room (full only on empty DB or periodic heal)
 * 5. YNAB sync is **backend-only** (EventBridge ~15m or optional tick) — not required for phone ops
 *
 * Day-to-day HTTP stays light: [KEY_SYNC_CURSOR] drives GET /v1/sync/changes?since=…
 * Silent full resync every [FULL_SYNC_INTERVAL_MS] avoids long-term drift.
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

    fun syncCursor(): Long = prefs.getLong(KEY_SYNC_CURSOR, 0L)

    fun lastFullSyncAt(): Long = prefs.getLong(KEY_LAST_FULL_SYNC_AT, 0L)

    suspend fun refreshLocalDataFlag(planId: String = DEFAULT_PLAN_ID) {
        val accounts = db.accountDao().countOpen(planId)
        val txns = db.transactionDao().countForPlan(planId)
        _hasLocalData.value = accounts > 0 || txns > 0
        _pendingCount.value = db.transactionDao().countPendingPush(planId)
    }

    /**
     * Local-first entry: show Room immediately; full-hydrate only when empty.
     * When data exists: push pending + lightweight delta (or silent full if due).
     */
    suspend fun ensureHydrated(planId: String = DEFAULT_PLAN_ID): Result<CloudSyncReport?> =
        mutex.withLock {
            refreshLocalDataFlag(planId)
            if (!_hasLocalData.value) {
                return@withLock doPullLocked(
                    planId = planId,
                    pullYnab = false,
                    forceFull = true,
                    showBusy = true,
                )
            }
            // Already have data — push offline work + delta (or silent full if due).
            doPushThenPullLocked(
                planId = planId,
                pullYnab = false,
                forceFull = shouldFullSync(),
                showBusy = false,
            )
        }

    /**
     * Called when network becomes available (or app cold-start online).
     * Push offline queue → DDB, then **delta** pull (full only if due / empty).
     */
    suspend fun syncWhenOnline(planId: String = DEFAULT_PLAN_ID): Result<CloudSyncReport?> =
        mutex.withLock {
            refreshLocalDataFlag(planId)
            if (!_hasLocalData.value) {
                return@withLock doPullLocked(
                    planId = planId,
                    pullYnab = false,
                    forceFull = true,
                    showBusy = true,
                )
            }
            doPushThenPullLocked(
                planId = planId,
                pullYnab = false,
                forceFull = shouldFullSync(),
                showBusy = false,
            )
        }

    /**
     * After local categorize/approve: flush PENDING_PUSH only.
     * **Never pulls** and does not flip [isSyncing] — inbox UI stays on the same
     * list with rows already removed from Room; no HTTP refresh flash.
     */
    suspend fun pushPendingSilent(planId: String = DEFAULT_PLAN_ID): Result<Unit> =
        mutex.withLock {
            doPushOnlyLocked(planId, silent = true)
        }

    /**
     * Manual refresh (toolbar). Push pending, tick YNAB on server, pull delta
     * (or full if forced / interval due).
     */
    suspend fun refresh(
        planId: String = DEFAULT_PLAN_ID,
        forceFull: Boolean = false,
    ): Result<CloudSyncReport> =
        mutex.withLock {
            doPushThenPullLocked(
                planId = planId,
                pullYnab = true,
                forceFull = forceFull || shouldFullSync(),
                showBusy = true,
            ).map { it!! }
        }

    private fun shouldFullSync(): Boolean {
        val lastFull = lastFullSyncAt()
        if (lastFull <= 0L) return true
        return System.currentTimeMillis() - lastFull >= FULL_SYNC_INTERVAL_MS
    }

    private suspend fun doPushOnlyLocked(
        planId: String,
        silent: Boolean = false,
    ): Result<Unit> {
        return try {
            val pending = db.transactionDao().countPendingPush(planId)
            if (pending == 0) return Result.success(Unit)
            if (!silent) {
                _isSyncing.value = true
                _statusMessage.value = "Uploading $pending offline change(s)…"
            }
            cloudSync.pushLocalPending(planId) { step ->
                if (!silent) _statusMessage.value = step
            }
            refreshLocalDataFlag(planId)
            if (!silent) {
                _statusMessage.value = "Offline changes uploaded to cloud"
            }
            Result.success(Unit)
        } catch (e: Exception) {
            if (!silent) {
                _statusMessage.value = "Will retry upload: ${e.message}"
            }
            Result.failure(e)
        } finally {
            if (!silent) {
                _isSyncing.value = false
            }
        }
    }

    private suspend fun doPushThenPullLocked(
        planId: String,
        pullYnab: Boolean,
        forceFull: Boolean,
        showBusy: Boolean,
    ): Result<CloudSyncReport?> {
        if (showBusy) _isSyncing.value = true
        return try {
            val pending = db.transactionDao().countPendingPush(planId)
            if (pending > 0) {
                if (showBusy) _statusMessage.value = "Uploading $pending offline change(s)…"
                runCatching {
                    cloudSync.pushLocalPending(planId) { step ->
                        if (showBusy) _statusMessage.value = step
                    }
                }.onFailure {
                    if (showBusy) _statusMessage.value = "Upload deferred: ${it.message}"
                }
            }
            doPullLocked(
                planId = planId,
                pullYnab = pullYnab,
                forceFull = forceFull,
                showBusy = showBusy,
                alreadyBusy = true,
            )
        } catch (e: Exception) {
            if (showBusy) _statusMessage.value = "Sync failed: ${e.message}"
            Result.failure(e)
        } finally {
            if (showBusy) _isSyncing.value = false
        }
    }

    private suspend fun doPullLocked(
        planId: String,
        pullYnab: Boolean,
        forceFull: Boolean,
        showBusy: Boolean,
        alreadyBusy: Boolean = false,
    ): Result<CloudSyncReport?> {
        if (showBusy && !alreadyBusy) _isSyncing.value = true
        return try {
            val since = if (forceFull) 0L else syncCursor()
            if (showBusy) {
                _statusMessage.value =
                    if (forceFull || since <= 0L) "Full sync from cloud…"
                    else "Refreshing changes…"
            }
            val report = cloudSync.syncFromCloud(
                pullYnab = pullYnab,
                since = since,
                forceFull = forceFull || since <= 0L,
            ) { step ->
                if (showBusy) _statusMessage.value = step
            }
            markSyncedOk(planId, report)
            Result.success(report)
        } catch (e: Exception) {
            if (showBusy) _statusMessage.value = "Sync failed: ${e.message}"
            Result.failure(e)
        } finally {
            if (showBusy && !alreadyBusy) _isSyncing.value = false
        }
    }

    private suspend fun markSyncedOk(planId: String, report: CloudSyncReport) {
        val now = System.currentTimeMillis()
        val edit = prefs.edit().putLong(KEY_LAST_SYNCED_AT, now)
        if (report.cursor > 0L) {
            edit.putLong(KEY_SYNC_CURSOR, report.cursor)
        }
        if (report.mode == "full" || report.mode.isBlank() && report.cursor > 0L) {
            // Treat successful full (or first) pull as full baseline.
            if (report.mode == "full" || lastFullSyncAt() <= 0L) {
                edit.putLong(KEY_LAST_FULL_SYNC_AT, now)
            }
        }
        if (report.mode == "full") {
            edit.putLong(KEY_LAST_FULL_SYNC_AT, now)
        }
        edit.apply()
        _lastSyncedAt.value = now
        refreshLocalDataFlag(planId)
        val pending = _pendingCount.value
        _statusMessage.value =
            buildString {
                val modeLabel = if (report.mode == "delta") "delta" else report.mode
                append("Synced “${report.planName}” ($modeLabel): ")
                append("${report.accounts} accounts, ${report.transactions} transactions")
                if (pending > 0) append(" · $pending still pending upload")
            }
    }

    companion object {
        const val DEFAULT_PLAN_ID = "default"
        private const val PREFS = "r2finance_sync"
        private const val KEY_LAST_SYNCED_AT = "last_synced_at"
        private const val KEY_SYNC_CURSOR = "sync_cursor"
        private const val KEY_LAST_FULL_SYNC_AT = "last_full_sync_at"
        /** Silent full resync interval to heal drift without daily megabyte pulls. */
        const val FULL_SYNC_INTERVAL_MS: Long = 24L * 60L * 60L * 1000L
    }
}
