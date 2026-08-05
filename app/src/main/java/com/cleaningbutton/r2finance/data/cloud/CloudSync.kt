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
)

/**
 * Pull ledger snapshot from R2FinanceAPI (DynamoDB) into local Room.
 * Uses stable remote ids (API `ynabId` fields) as local primary keys for clean re-sync.
 */
class CloudSync(
    private val db: R2FinanceDatabase,
    private val api: CloudApi = CloudApi(),
) {
    /**
     * Refresh YNAB↔DDB on the server, then hydrate Room (accounts/categories/payees/txns + inbox).
     */
    suspend fun syncFromCloud(
        pullYnab: Boolean = true,
        onProgress: (String) -> Unit = {},
    ): CloudSyncReport = withContext(Dispatchers.IO) {
        if (pullYnab) {
            onProgress("Syncing with YNAB…")
            runCatching { api.syncTick() }
                .onFailure { /* still hydrate from last DDB snapshot */ }
        }
        pullAll(onProgress)
    }

    suspend fun pullAll(onProgress: (String) -> Unit = {}): CloudSyncReport =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            onProgress("Fetching plan…")
            val planInfo = api.getPlan()
            val planId = "default"
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

            onProgress("Accounts…")
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

            onProgress("Categories…")
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
                        ynabId = c.ynabId,
                        updatedAt = now,
                        syncStatus = SyncStatus.SYNCED,
                    )
                },
            )

            onProgress("Payees…")
            val payees = api.getPayees()
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

            onProgress("Transactions…")
            val txns = api.getTransactions()
            // Phone edits marked PENDING_PUSH must not be overwritten by cloud snapshot.
            val pendingLocal = db.transactionDao().pendingPushIds(planId).toSet()
            val accountIds = accounts.map { it.ynabId }.toSet()
            val entities = mutableListOf<TransactionEntity>()
            val subs = mutableListOf<SubTransactionEntity>()
            var skippedPending = 0
            for (t in txns) {
                val stableId = t.stableId()
                if (t.accountId !in accountIds) continue
                if (stableId.isBlank() || t.date.isBlank()) continue
                if (stableId in pendingLocal || t.ynabId in pendingLocal) {
                    skippedPending++
                    continue
                }
                entities.add(toEntity(t, planId, now))
                for (s in t.subtransactions) {
                    subs.add(
                        SubTransactionEntity(
                            id = s.ynabId ?: UUID.randomUUID().toString(),
                            transactionId = stableId,
                            amountMilli = s.amount,
                            payeeId = s.payeeId,
                            categoryId = s.categoryId,
                            memo = s.memo,
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

            // Always merge lightweight inbox snapshot so unapproved/uncategorized land
            // even if full txn parse dropped rows or timed out partially.
            onProgress("Inbox…")
            val inbox = runCatching { api.getInbox() }.getOrNull()
            inbox?.transactions?.let { list ->
                val inboxEntities = list.mapNotNull { t ->
                    val stableId = t.stableId()
                    if (stableId.isBlank() || t.accountId !in accountIds) null
                    else if (stableId in pendingLocal || t.ynabId in pendingLocal) null
                    else toEntity(t, planId, now)
                }
                inboxEntities.chunked(200).forEach { chunk ->
                    db.transactionDao().upsertAll(chunk)
                }
            }

            onProgress("Done")
            CloudSyncReport(
                planName = planInfo.name,
                accounts = accounts.size,
                categories = cats.categories.size,
                payees = payees.size,
                transactions = entities.size,
                inboxCount = inbox?.count ?: 0,
            )
        }

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
                        ynabId = c.ynabId,
                        updatedAt = now,
                        syncStatus = SyncStatus.SYNCED,
                    )
                },
            )
            // Resolve payee names for inbox rows
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
                else toEntity(t, planId, now)
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
        // Also resolve names for txn payees already synced
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
        // Mark successfully landed rows as SYNCED on device (DDB is source of cloud truth).
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

    private fun toEntity(t: CloudTransaction, planId: String, now: Long): TransactionEntity {
        val stableId = t.stableId()
        return TransactionEntity(
            id = stableId,
            planId = planId,
            accountId = t.accountId,
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
            updatedAt = now,
            syncStatus = SyncStatus.SYNCED,
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
