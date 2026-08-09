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
 * 5. **YNAB never on device.** Any YNAB↔DDB bridge runs only in AWS
 *    (EventBridge ~15m or optional POST /v1/sync/tick). Phone only talks to R2FinanceAPI.
 *
 * Day-to-day HTTP stays light: [KEY_SYNC_CURSOR] drives GET /v1/sync/changes?since=…
 * Silent full resync every [FULL_SYNC_INTERVAL_MS] avoids long-term drift.
 *
 * **Empty-ledger recovery:** accounts alone do not count as a usable ledger.
 * Zero live transactions always force a full hydrate (and never coalesce), so a
 * botched prune / pre-login failed pull / reinstall cannot leave Categorization
 * "All clear" and Reflect "No transactions" while the cloud still has thousands.
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

    /** Live (non-deleted) Room transaction count for empty-ledger recovery. */
    private var liveTxnCount: Int = 0

    /**
     * Process-scoped coalesce: warmup + ConnectivityMonitor cold-start both
     * fire hydrate; tab screens must not. Skip a second silent delta if one
     * just finished and Room already has a real ledger.
     */
    private var lastBackgroundSyncAtMs: Long = 0L

    fun syncCursor(): Long = prefs.getLong(KEY_SYNC_CURSOR, 0L)

    fun lastFullSyncAt(): Long = prefs.getLong(KEY_LAST_FULL_SYNC_AT, 0L)

    suspend fun refreshLocalDataFlag(planId: String = DEFAULT_PLAN_ID) {
        val accounts = db.accountDao().countOpen(planId)
        val txns = db.transactionDao().countForPlan(planId)
        liveTxnCount = txns
        // UI "has something" can still be accounts-only; hydrate logic uses [needsFullHydrate].
        _hasLocalData.value = accounts > 0 || txns > 0
        _pendingCount.value = db.transactionDao().countPendingPush(planId)
    }

    /**
     * True when Room cannot paint Reflect / Categorization from a real ledger.
     * Accounts without transactions are treated as empty (common after a failed
     * full sync that landed meta rows only, or a prune without re-insert).
     */
    private fun needsFullHydrate(): Boolean = liveTxnCount <= 0

    /**
     * Local-first entry (call from process warmup + post-auth, not every tab):
     * show Room immediately; full-hydrate when empty of transactions.
     * When data exists: push pending + lightweight delta (or silent full if due).
     * Coalesces with [syncWhenOnline] so cold-start does not double-pull.
     */
    suspend fun ensureHydrated(planId: String = DEFAULT_PLAN_ID): Result<CloudSyncReport?> =
        mutex.withLock {
            refreshLocalDataFlag(planId)
            if (shouldCoalesceBackgroundSync()) {
                return@withLock Result.success(null)
            }
            if (needsFullHydrate()) {
                return@withLock doPullLocked(
                    planId = planId,
                    requestServerTick = false,
                    forceFull = true,
                    showBusy = true,
                ).also { if (it.isSuccess) markBackgroundSyncAttempt() }
            }
            // Already have a real ledger — push offline work + delta (or silent full if due).
            doPushThenPullLocked(
                planId = planId,
                requestServerTick = false,
                forceFull = shouldFullSync(),
                showBusy = false,
            ).also { if (it.isSuccess) markBackgroundSyncAttempt() }
        }

    /**
     * Called when network becomes available (or app cold-start online).
     * Push offline queue → DDB, then **delta** pull (full only if due / empty).
     * Coalesced with [ensureHydrated] so process start runs one silent sync.
     */
    suspend fun syncWhenOnline(planId: String = DEFAULT_PLAN_ID): Result<CloudSyncReport?> =
        mutex.withLock {
            refreshLocalDataFlag(planId)
            if (shouldCoalesceBackgroundSync()) {
                return@withLock Result.success(null)
            }
            if (needsFullHydrate()) {
                return@withLock doPullLocked(
                    planId = planId,
                    requestServerTick = false,
                    forceFull = true,
                    showBusy = true,
                ).also { if (it.isSuccess) markBackgroundSyncAttempt() }
            }
            doPushThenPullLocked(
                planId = planId,
                requestServerTick = false,
                forceFull = shouldFullSync(),
                showBusy = false,
            ).also { if (it.isSuccess) markBackgroundSyncAttempt() }
        }

    private fun shouldCoalesceBackgroundSync(): Boolean {
        // Never coalesce past an empty ledger — recovery must always run.
        if (needsFullHydrate()) return false
        if (!_hasLocalData.value) return false
        // Always flush offline queue — never coalesce past PENDING_PUSH.
        if (_pendingCount.value > 0) return false
        if (lastBackgroundSyncAtMs <= 0L) return false
        return System.currentTimeMillis() - lastBackgroundSyncAtMs < BACKGROUND_SYNC_COALESCE_MS
    }

    private fun markBackgroundSyncAttempt() {
        lastBackgroundSyncAtMs = System.currentTimeMillis()
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
     * Manual refresh (toolbar / pull-to-refresh). Push pending → R2FinanceAPI/DDB,
     * optional server bridge tick (AWS only), then pull delta/full from cloud.
     * Empty local ledger always force-fulls so Categorization cannot stay stuck
     * on "All clear" while the website shows needs-attention rows.
     */
    suspend fun refresh(
        planId: String = DEFAULT_PLAN_ID,
        forceFull: Boolean = false,
    ): Result<CloudSyncReport> =
        mutex.withLock {
            refreshLocalDataFlag(planId)
            doPushThenPullLocked(
                planId = planId,
                requestServerTick = true,
                forceFull = forceFull || needsFullHydrate() || shouldFullSync(),
                showBusy = true,
            ).map { it!! }
        }

    private fun shouldFullSync(): Boolean {
        val lastFull = lastFullSyncAt()
        // Missing lastFull (upgrade / first install with Room already filled) must NOT
        // force a multi-MB full pull that used to wipe-before-write and blank the UI.
        // Empty Room still force-fulls via needsFullHydrate().
        if (lastFull <= 0L) return false
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
        requestServerTick: Boolean,
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
                requestServerTick = requestServerTick,
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
        requestServerTick: Boolean,
        forceFull: Boolean,
        showBusy: Boolean,
        alreadyBusy: Boolean = false,
    ): Result<CloudSyncReport?> {
        if (showBusy && !alreadyBusy) _isSyncing.value = true
        return try {
            val since = if (forceFull) 0L else syncCursor()
            if (showBusy) {
                _statusMessage.value =
                    if (forceFull || since <= 0L) "Full sync from R2Finance…"
                    else "Refreshing from R2Finance…"
            }
            val report = cloudSync.syncFromCloud(
                requestServerTick = requestServerTick,
                since = since,
                forceFull = forceFull || since <= 0L,
            ) { step ->
                if (showBusy) _statusMessage.value = step
            }
            markSyncedOk(planId, report)
            // Still empty after a non-full pull → immediately force a full snapshot.
            // Covers a stale cursor after Room was wiped without resetting prefs.
            refreshLocalDataFlag(planId)
            if (needsFullHydrate() && report.mode != "full") {
                if (showBusy) _statusMessage.value = "Ledger empty — full sync from R2Finance…"
                val fullReport = cloudSync.syncFromCloud(
                    requestServerTick = false,
                    since = 0L,
                    forceFull = true,
                ) { step ->
                    if (showBusy) _statusMessage.value = step
                }
                markSyncedOk(planId, fullReport)
                return Result.success(fullReport)
            }
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
        // Never advance the delta cursor after a full pack that applied zero
        // transactions — that would strand the phone on empty-delta forever while
        // the cloud still has the full ledger (and inbox).
        val emptyFull = report.mode == "full" && report.transactions <= 0
        if (!emptyFull && report.cursor > 0L) {
            edit.putLong(KEY_SYNC_CURSOR, report.cursor)
        }
        if (report.mode == "full") {
            if (!emptyFull) {
                edit.putLong(KEY_LAST_FULL_SYNC_AT, now)
            } else {
                // Keep lastFull unset/old so the next open retries full hydrate.
                edit.putLong(KEY_LAST_FULL_SYNC_AT, 0L)
            }
        } else if (lastFullSyncAt() <= 0L) {
            // Seed 24h heal clock after first successful delta so upgrades don't
            // immediately re-download the whole ledger — but only if we actually
            // have live rows now.
            refreshLocalDataFlag(planId)
            if (!needsFullHydrate()) {
                edit.putLong(KEY_LAST_FULL_SYNC_AT, now)
            }
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
                if (report.inboxCount > 0) append(" · ${report.inboxCount} need attention")
                if (pending > 0) append(" · $pending still pending upload")
                if (emptyFull) append(" · empty full pack (will retry)")
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
        /**
         * Warmup + cold-start ConnectivityMonitor both request a silent sync.
         * Skip a second pull within this window when Room already has a ledger.
         * Manual [refresh] always runs (separate code path).
         */
        const val BACKGROUND_SYNC_COALESCE_MS: Long = 30_000L
    }
}
