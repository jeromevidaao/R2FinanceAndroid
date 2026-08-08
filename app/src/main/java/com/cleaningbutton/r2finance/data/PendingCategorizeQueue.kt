package com.cleaningbutton.r2finance.data

import com.cleaningbutton.r2finance.data.cloud.ConnectivityMonitor
import com.cleaningbutton.r2finance.data.cloud.SyncCoordinator
import com.cleaningbutton.r2finance.data.local.entity.TransactionEntity
import com.cleaningbutton.r2finance.data.repository.LedgerRepository
import com.cleaningbutton.r2finance.data.repository.TransactionRow
import com.cleaningbutton.r2finance.domain.SyncStatus
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Delayed categorize → cloud push (default 10s) with undo.
 *
 * Local Room is updated immediately so the inbox drops rows. Transaction ids
 * stay in [PushHold] until the delay expires so ConnectivityMonitor / refresh
 * cannot upload them early. Undo restores prior category + approved flags.
 */
class PendingCategorizeQueue(
    private val scope: CoroutineScope,
    private val ledger: LedgerRepository,
    private val connectivityMonitor: ConnectivityMonitor,
    private val syncCoordinator: SyncCoordinator,
) {
    data class TxnSnapshot(
        val id: String,
        val categoryId: String?,
        val approved: Boolean,
        val syncStatus: SyncStatus,
    )

    data class Entry(
        val id: String,
        val planId: String,
        val transactionIds: List<String>,
        val previous: List<TxnSnapshot>,
        val newCategoryId: String,
        val categoryName: String,
        val label: String,
        val expiresAt: Long,
    )

    private data class Active(
        val job: Job,
        val cancelled: AtomicBoolean,
    )

    private val actives = ConcurrentHashMap<String, Active>()
    private val _pending = MutableStateFlow<List<Entry>>(emptyList())
    val pending: StateFlow<List<Entry>> = _pending.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun clearError() {
        _lastError.value = null
    }

    /**
     * Apply category locally, hold push for [delayMs], then silent push.
     * Returns entry id, or null if nothing to do.
     */
    fun enqueue(
        planId: String,
        targets: List<TransactionRow>,
        categoryId: String,
        categoryName: String,
        delayMs: Long = DEFAULT_DELAY_MS,
    ): String? {
        val eligible = targets.filter { it.txn.transferAccountId == null }
        if (eligible.isEmpty()) return null

        val snapshots = eligible.map { row ->
            val t = row.txn
            TxnSnapshot(
                id = t.id,
                categoryId = t.categoryId,
                approved = t.approved,
                syncStatus = t.syncStatus,
            )
        }
        val ledgerSnaps = snapshots.map {
            LedgerRepository.CategorySnapshot(
                id = it.id,
                categoryId = it.categoryId,
                approved = it.approved,
                syncStatus = it.syncStatus,
            )
        }
        val ids = snapshots.map { it.id }
        val label = buildLabel(eligible, categoryName)
        val entryId = UUID.randomUUID().toString()
        val expiresAt = System.currentTimeMillis() + delayMs
        val cancelled = AtomicBoolean(false)

        // Hold before Room write so any concurrent push skips these ids.
        PushHold.hold(ids)

        val entry = Entry(
            id = entryId,
            planId = planId,
            transactionIds = ids,
            previous = snapshots,
            newCategoryId = categoryId,
            categoryName = categoryName,
            label = label,
            expiresAt = expiresAt,
        )
        _pending.value = listOf(entry) + _pending.value

        val job = scope.launch {
            runCatching { ledger.setCategoryMany(ids, categoryId) }
            // Undo may have raced the Room write — re-restore if cancelled.
            if (cancelled.get()) {
                runCatching { ledger.restoreCategorySnapshots(ledgerSnaps) }
                return@launch
            }
            try {
                delay(delayMs)
            } catch (_: kotlinx.coroutines.CancellationException) {
                return@launch
            }
            if (cancelled.get()) return@launch
            removeEntry(entryId)
            PushHold.release(ids)
            if (connectivityMonitor.online.value) {
                runCatching { syncCoordinator.pushPendingSilent(planId) }
                    .onFailure { e ->
                        _lastError.value =
                            "Category save failed: ${e.message}. Pull to refresh if anything looks wrong."
                    }
            }
        }
        actives[entryId] = Active(job, cancelled)
        return entryId
    }

    fun undo(entryId: String): Boolean {
        val entry = _pending.value.find { it.id == entryId } ?: return false
        val active = actives.remove(entryId)
        active?.cancelled?.set(true)
        active?.job?.cancel()
        removeEntry(entryId)
        PushHold.release(entry.transactionIds)
        val ledgerSnaps = entry.previous.map {
            LedgerRepository.CategorySnapshot(
                id = it.id,
                categoryId = it.categoryId,
                approved = it.approved,
                syncStatus = it.syncStatus,
            )
        }
        scope.launch {
            runCatching { ledger.restoreCategorySnapshots(ledgerSnaps) }
        }
        return true
    }

    fun undoLatest(): Boolean {
        val latest = _pending.value.firstOrNull() ?: return false
        return undo(latest.id)
    }

    private fun removeEntry(entryId: String) {
        _pending.value = _pending.value.filterNot { it.id == entryId }
        actives.remove(entryId)
    }

    private fun buildLabel(targets: List<TransactionRow>, categoryName: String): String {
        return if (targets.size == 1) {
            val payee = targets[0].payeeName?.trim().orEmpty().ifEmpty { "Transaction" }
            "$payee → $categoryName"
        } else {
            "${targets.size} txns → $categoryName"
        }
    }

    companion object {
        const val DEFAULT_DELAY_MS = 10_000L
    }
}

/**
 * Transaction ids that must not leave the device yet (undo window).
 * [CloudSync.pushLocalPending] filters these out.
 */
object PushHold {
    private val held = ConcurrentHashMap.newKeySet<String>()

    fun hold(ids: Collection<String>) {
        held.addAll(ids)
    }

    fun release(ids: Collection<String>) {
        held.removeAll(ids.toSet())
    }

    fun isHeld(id: String): Boolean = held.contains(id)

    fun filterPushable(list: List<TransactionEntity>): List<TransactionEntity> =
        list.filter { it.id !in held && (it.ynabId == null || it.ynabId !in held) }

    /** Test / diagnostics. */
    fun clearAll() {
        held.clear()
    }

    fun size(): Int = held.size
}
