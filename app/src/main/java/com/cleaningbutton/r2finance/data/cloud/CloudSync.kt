package com.cleaningbutton.r2finance.data.cloud

import com.cleaningbutton.r2finance.data.local.R2FinanceDatabase
import com.cleaningbutton.r2finance.data.local.entity.AccountEntity
import com.cleaningbutton.r2finance.data.local.entity.CategoryEntity
import com.cleaningbutton.r2finance.data.local.entity.CategoryGroupEntity
import com.cleaningbutton.r2finance.data.local.entity.PayeeEntity
import com.cleaningbutton.r2finance.data.local.entity.PlanEntity
import com.cleaningbutton.r2finance.data.local.entity.SubTransactionEntity
import com.cleaningbutton.r2finance.data.local.entity.TransactionEntity
import com.cleaningbutton.r2finance.domain.AccountType
import com.cleaningbutton.r2finance.domain.ClearedStatus
import com.cleaningbutton.r2finance.domain.FlagColor
import com.cleaningbutton.r2finance.domain.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

data class CloudSyncReport(
    val planName: String,
    val accounts: Int,
    val categories: Int,
    val payees: Int,
    val transactions: Int,
    val inboxCount: Int = 0,
    val mode: String = "full",
    val cursor: Long = 0L,
)

/**
 * Pull ledger from R2FinanceAPI (DynamoDB) into local Room.
 *
 * Local-first: prefer [pullChanges] with a server cursor so day-to-day opens only
 * download incremental rows. Occasional full resync heals drift.
 */
class CloudSync(
    private val db: R2FinanceDatabase,
    private val api: CloudApi = CloudApi(),
) {
    /**
     * Optional YNAB tick on the server, then pull DDB → Room (delta or full).
     */
    suspend fun syncFromCloud(
        pullYnab: Boolean = true,
        since: Long = 0L,
        forceFull: Boolean = false,
        onProgress: (String) -> Unit = {},
    ): CloudSyncReport = withContext(Dispatchers.IO) {
        if (pullYnab) {
            onProgress("Syncing with YNAB…")
            runCatching { api.syncTick() }
                .onFailure { /* still hydrate from last DDB snapshot */ }
        }
        pullChanges(since = since, forceFull = forceFull, onProgress = onProgress)
    }

    /**
     * Apply a full snapshot or delta from GET /v1/sync/changes.
     *
     * @param since last server cursor (0 → full)
     * @param forceFull ignore [since] and download live snapshot
     */
    suspend fun pullChanges(
        since: Long = 0L,
        forceFull: Boolean = false,
        onProgress: (String) -> Unit = {},
    ): CloudSyncReport = withContext(Dispatchers.IO) {
        val wantFull = forceFull || since <= 0L
        onProgress(if (wantFull) "Full sync from cloud…" else "Fetching changes…")
        val pack = api.getSyncChanges(since = if (wantFull) 0L else since, full = wantFull)
        val mode = pack.mode.ifBlank { if (wantFull) "full" else "delta" }
        val now = System.currentTimeMillis()
        val planId = "default"
        val planInfo = pack.plan ?: CloudPlan()
        val cursor = when {
            pack.cursor > 0L -> pack.cursor
            pack.serverTime > 0L -> pack.serverTime
            else -> now
        }

        db.planDao().upsert(
            PlanEntity(
                id = planId,
                name = planInfo.name,
                currencyCode = planInfo.currency,
                ynabId = planInfo.ynabPlanId,
                serverKnowledge = planInfo.serverKnowledge,
                updatedAt = now,
                syncStatus = SyncStatus.SYNCED,
            ),
        )

        val pendingLocal = db.transactionDao().pendingPushIds(planId).toSet()

        onProgress("Accounts…")
        if (mode == "full") {
            db.accountDao().softDeleteAllSynced(planId, now)
        }
        if (pack.accounts.isNotEmpty()) {
            db.accountDao().upsertAll(
                pack.accounts.map { a ->
                    AccountEntity(
                        id = a.ynabId,
                        planId = planId,
                        name = a.name,
                        type = parseAccountType(a.type),
                        onBudget = a.onBudget,
                        closed = a.closed,
                        note = a.note,
                        transferPayeeId = a.transferPayeeId,
                        ynabId = a.ynabId,
                        updatedAt = if (a.updatedAt > 0) a.updatedAt else now,
                        syncStatus = SyncStatus.SYNCED,
                        deleted = a.deleted,
                    )
                },
            )
        }

        onProgress("Categories…")
        if (pack.groups.isNotEmpty() || pack.categories.isNotEmpty()) {
            val groupIds = pack.groups.map { it.ynabId }.toSet()
            if (pack.groups.isNotEmpty()) {
                db.categoryDao().upsertGroups(
                    pack.groups.mapIndexed { i, g ->
                        CategoryGroupEntity(
                            id = g.ynabId,
                            planId = planId,
                            name = g.name,
                            hidden = g.hidden,
                            sortOrder = i,
                            ynabId = g.ynabId,
                            updatedAt = if (g.updatedAt > 0) g.updatedAt else now,
                            syncStatus = SyncStatus.SYNCED,
                            deleted = g.deleted,
                        )
                    },
                )
            }
            if (pack.categories.isNotEmpty()) {
                db.categoryDao().upsertCategories(
                    pack.categories.mapIndexed { i, c ->
                        val groupId = c.categoryGroupId?.takeIf { it in groupIds || it.isNotBlank() }
                            ?: pack.groups.firstOrNull()?.ynabId
                            ?: "uncategorized"
                        CategoryEntity(
                            id = c.ynabId,
                            planId = planId,
                            categoryGroupId = groupId,
                            name = c.name,
                            hidden = c.hidden,
                            sortOrder = i,
                            color = c.color,
                            ynabId = c.ynabId,
                            updatedAt = if (c.updatedAt > 0) c.updatedAt else now,
                            syncStatus = SyncStatus.SYNCED,
                            deleted = c.deleted,
                        )
                    },
                )
            }
        }

        onProgress("Payees…")
        if (pack.payees.isNotEmpty()) {
            db.payeeDao().upsertAll(
                pack.payees.map { p ->
                    PayeeEntity(
                        id = p.ynabId,
                        planId = planId,
                        name = p.name,
                        transferAccountId = p.transferAccountId,
                        ynabId = p.ynabId,
                        updatedAt = if (p.updatedAt > 0) p.updatedAt else now,
                        syncStatus = SyncStatus.SYNCED,
                        deleted = p.deleted,
                    )
                },
            )
        }

        onProgress(
            if (mode == "full") "Transactions (full)…"
            else "Transactions (+${pack.transactions.size})…",
        )
        if (mode == "full") {
            // Heal drift: tombstone all synced live rows, then re-upsert server live set.
            db.transactionDao().softDeleteAllSynced(planId, now)
        }

        val entities = mutableListOf<TransactionEntity>()
        val subs = mutableListOf<SubTransactionEntity>()
        var skippedPending = 0
        for (t in pack.transactions) {
            val stableId = t.stableId()
            if (stableId.isBlank()) continue
            // Delta may include tombstones without accountId; still apply by id.
            if (!t.deleted && t.accountId.isBlank()) continue
            if (stableId in pendingLocal || t.ynabId in pendingLocal) {
                skippedPending++
                continue
            }
            if (t.deleted) {
                entities.add(toEntity(t, planId, now, deleted = true))
                continue
            }
            if (t.date.isBlank()) continue
            entities.add(toEntity(t, planId, now, deleted = false))
            for (s in t.subtransactions) {
                subs.add(
                    SubTransactionEntity(
                        id = s.ynabId ?: UUID.randomUUID().toString(),
                        transactionId = stableId,
                        amountMilli = s.amount,
                        payeeId = s.payeeId,
                        categoryId = s.categoryId,
                        memo = s.memo,
                        transferAccountId = s.transferAccountId,
                        ynabId = s.ynabId,
                        updatedAt = now,
                    ),
                )
            }
        }
        if (skippedPending > 0) {
            onProgress("Kept $skippedPending local pending edit(s)…")
        }
        entities.chunked(200).forEach { chunk ->
            db.transactionDao().upsertAll(chunk)
        }
        if (subs.isNotEmpty()) {
            subs.chunked(200).forEach { chunk ->
                db.transactionDao().upsertSubs(chunk)
            }
        }

        // Lightweight inbox merge so unapproved land even if delta was tiny.
        onProgress("Inbox…")
        val inbox = runCatching { api.getInbox() }.getOrNull()
        inbox?.transactions?.let { list ->
            val accountIds = pack.accounts
                .filter { !it.deleted && !it.closed }
                .map { it.ynabId }
                .toSet()
                .ifEmpty {
                    // Delta may omit accounts; accept inbox rows by id only.
                    emptySet()
                }
            val inboxEntities = list.mapNotNull { t ->
                val stableId = t.stableId()
                if (stableId.isBlank()) null
                else if (stableId in pendingLocal || t.ynabId in pendingLocal) null
                else if (accountIds.isNotEmpty() && t.accountId !in accountIds) null
                else toEntity(t, planId, now, deleted = t.deleted)
            }
            inboxEntities.chunked(200).forEach { chunk ->
                db.transactionDao().upsertAll(chunk)
            }
        }

        onProgress("Done")
        CloudSyncReport(
            planName = planInfo.name,
            accounts = pack.accounts.size,
            categories = pack.categories.size,
            payees = pack.payees.size,
            transactions = entities.size,
            inboxCount = inbox?.count ?: 0,
            mode = mode,
            cursor = cursor,
        )
    }

    /** @deprecated Prefer [pullChanges]; kept for call sites that mean full hydrate. */
    suspend fun pullAll(onProgress: (String) -> Unit = {}): CloudSyncReport =
        pullChanges(since = 0L, forceFull = true, onProgress = onProgress)

    /**
     * Fast path for Inbox tab: YNAB tick + accounts/categories + inbox rows only.
     */
    suspend fun pullInbox(onProgress: (String) -> Unit = {}): CloudSyncReport =
        withContext(Dispatchers.IO) {
            onProgress("Syncing with YNAB…")
            runCatching { api.syncTick() }
            val now = System.currentTimeMillis()
            val planId = "default"
            onProgress("Accounts & categories…")
            val planInfo = api.getPlan()
            db.planDao().upsert(
                PlanEntity(
                    id = planId,
                    name = planInfo.name,
                    currencyCode = planInfo.currency,
                    ynabId = planInfo.ynabPlanId,
                    serverKnowledge = planInfo.serverKnowledge,
                    updatedAt = now,
                    syncStatus = SyncStatus.SYNCED,
                ),
            )
            val accounts = api.getAccounts()
            db.accountDao().upsertAll(
                accounts.map { a ->
                    AccountEntity(
                        id = a.ynabId,
                        planId = planId,
                        name = a.name,
                        type = parseAccountType(a.type),
                        onBudget = a.onBudget,
                        closed = a.closed,
                        note = a.note,
                        transferPayeeId = a.transferPayeeId,
                        ynabId = a.ynabId,
                        updatedAt = now,
                        syncStatus = SyncStatus.SYNCED,
                    )
                },
            )
            val cats = api.getCategories()
            val groupIds = cats.groups.map { it.ynabId }.toSet()
            db.categoryDao().upsertGroups(
                cats.groups.mapIndexed { i, g ->
                    CategoryGroupEntity(
                        id = g.ynabId,
                        planId = planId,
                        name = g.name,
                        hidden = g.hidden,
                        sortOrder = i,
                        ynabId = g.ynabId,
                        updatedAt = now,
                        syncStatus = SyncStatus.SYNCED,
                    )
                },
            )
            db.categoryDao().upsertCategories(
                cats.categories.mapIndexed { i, c ->
                    val groupId = c.categoryGroupId?.takeIf { it in groupIds }
                        ?: cats.groups.firstOrNull()?.ynabId
                        ?: "uncategorized"
                    CategoryEntity(
                        id = c.ynabId,
                        planId = planId,
                        categoryGroupId = groupId,
                        name = c.name,
                        hidden = c.hidden,
                        sortOrder = i,
                        color = c.color,
                        ynabId = c.ynabId,
                        updatedAt = now,
                        syncStatus = SyncStatus.SYNCED,
                    )
                },
            )
            val payeeIdsNeeded = mutableSetOf<String>()
            onProgress("Inbox…")
            val inbox = api.getInbox()
            for (t in inbox.transactions) {
                t.payeeId?.let { payeeIdsNeeded.add(it) }
            }
            if (payeeIdsNeeded.isNotEmpty()) {
                val payees = api.getPayees().filter { it.ynabId in payeeIdsNeeded }
                db.payeeDao().upsertAll(
                    payees.map { p ->
                        PayeeEntity(
                            id = p.ynabId,
                            planId = planId,
                            name = p.name,
                            transferAccountId = p.transferAccountId,
                            ynabId = p.ynabId,
                            updatedAt = now,
                            syncStatus = SyncStatus.SYNCED,
                        )
                    },
                )
            }
            val accountIds = accounts.map { it.ynabId }.toSet()
            val pendingLocal = db.transactionDao().pendingPushIds(planId).toSet()
            val entities = inbox.transactions.mapNotNull { t ->
                val stableId = t.stableId()
                if (stableId.isBlank() || t.accountId !in accountIds) null
                else if (stableId in pendingLocal || t.ynabId in pendingLocal) null
                else toEntity(t, planId, now, deleted = t.deleted)
            }
            entities.chunked(200).forEach { chunk ->
                db.transactionDao().upsertAll(chunk)
            }
            onProgress("Done")
            CloudSyncReport(
                planName = planInfo.name,
                accounts = accounts.size,
                categories = cats.categories.size,
                payees = payeeIdsNeeded.size,
                transactions = entities.size,
                inboxCount = inbox.count,
                mode = "inbox",
            )
        }

    /**
     * Push local Room PENDING_PUSH payees + transactions into DynamoDB.
     * Marks them SYNCED on the phone once DDB accepts (YNAB may still be pending server-side).
     */
    suspend fun pushLocalPending(
        planId: String = "default",
        onProgress: (String) -> Unit = {},
    ): DevicePushResponse = withContext(Dispatchers.IO) {
        val pendingTxns = db.transactionDao().listPendingPush(planId)
        val pendingPayees = db.payeeDao().listPendingPush(planId)
        if (pendingTxns.isEmpty() && pendingPayees.isEmpty()) {
            onProgress("Nothing pending")
            return@withContext DevicePushResponse(ok = true, accepted = DevicePushAccepted())
        }
        onProgress("Pushing ${pendingTxns.size} txn(s), ${pendingPayees.size} payee(s)…")

        val payeeNameById = pendingPayees.associate { it.id to it.name }.toMutableMap()
        for (t in pendingTxns) {
            val pid = t.payeeId ?: continue
            if (pid !in payeeNameById) {
                db.payeeDao().getById(pid)?.let { payeeNameById[pid] = it.name }
            }
        }

        val request = DevicePushRequest(
            payees = pendingPayees.map { p ->
                DevicePushPayee(
                    clientId = p.id,
                    name = p.name,
                    ynabId = p.ynabId,
                    updatedAt = p.updatedAt,
                    deleted = p.deleted,
                )
            },
            transactions = pendingTxns.map { t ->
                DevicePushTransaction(
                    clientId = t.id,
                    ynabId = t.ynabId,
                    accountId = t.accountId,
                    date = t.date,
                    amount = t.amountMilli,
                    payeeId = t.payeeId,
                    payeeName = t.payeeId?.let { payeeNameById[it] },
                    categoryId = t.categoryId,
                    memo = t.memo,
                    cleared = t.cleared.name,
                    approved = t.approved,
                    deleted = t.deleted,
                    importId = t.importId ?: t.id,
                    updatedAt = t.updatedAt,
                )
            },
        )
        val resp = api.devicePush(request)
        if (!resp.ok && resp.error != null) {
            error(resp.error)
        }
        val now = System.currentTimeMillis()
        for (t in pendingTxns) {
            db.transactionDao().update(
                t.copy(syncStatus = SyncStatus.SYNCED, updatedAt = now),
            )
        }
        for (p in pendingPayees) {
            db.payeeDao().upsert(
                p.copy(syncStatus = SyncStatus.SYNCED, updatedAt = now),
            )
        }
        onProgress(
            "Pushed to cloud: ${resp.accepted?.transactions ?: pendingTxns.size} txn(s)",
        )
        resp
    }

    private fun toEntity(
        t: CloudTransaction,
        planId: String,
        now: Long,
        deleted: Boolean = false,
    ): TransactionEntity {
        val stableId = t.stableId()
        return TransactionEntity(
            id = stableId,
            planId = planId,
            accountId = t.accountId.ifBlank { "unknown" },
            date = t.date.ifBlank { "1970-01-01" },
            amountMilli = t.amount,
            payeeId = t.payeeId,
            categoryId = t.categoryId,
            memo = t.memo,
            cleared = parseCleared(t.cleared),
            approved = t.approved,
            flagColor = parseFlag(t.flagColor),
            transferAccountId = t.transferAccountId,
            transferTransactionId = t.transferTransactionId,
            importId = t.importId ?: t.clientId,
            ynabId = t.ynabId.ifBlank { null } ?: t.clientId,
            updatedAt = if (t.updatedAt > 0) t.updatedAt else now,
            syncStatus = SyncStatus.SYNCED,
            deleted = deleted || t.deleted,
            plaidTransactionId = t.plaidTransactionId,
            plaidMerchantName = t.plaidMerchantName,
            plaidPaymentChannel = t.plaidPaymentChannel,
            plaidPfc = t.plaidPfc,
            locationDisplay = t.locationDisplay,
            locationSource = t.locationSource,
        )
    }

    private fun parseAccountType(raw: String): AccountType =
        runCatching { AccountType.valueOf(raw) }.getOrDefault(AccountType.checking)

    private fun parseCleared(raw: String): ClearedStatus =
        when (raw.lowercase()) {
            "cleared" -> ClearedStatus.cleared
            "reconciled" -> ClearedStatus.reconciled
            else -> ClearedStatus.uncleared
        }

    private fun parseFlag(raw: String?): FlagColor =
        when (raw?.lowercase()) {
            "red" -> FlagColor.red
            "orange" -> FlagColor.orange
            "yellow" -> FlagColor.yellow
            "green" -> FlagColor.green
            "blue" -> FlagColor.blue
            "purple" -> FlagColor.purple
            else -> FlagColor.none
        }
}
