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
)

/**
 * Pull ledger snapshot from R2FinanceAPI (DynamoDB) into local Room.
 * Uses stable remote ids (API `ynabId` fields) as local primary keys for clean re-sync.
 */
class CloudSync(
    private val db: R2FinanceDatabase,
    private val api: CloudApi = CloudApi(),
) {
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

            // Seed balance via synthetic opening adjustment when no txns yet —
            // real balances come from transactions below.
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
            // Batch upsert
            val accountIds = accounts.map { it.ynabId }.toSet()
            val entities = mutableListOf<TransactionEntity>()
            val subs = mutableListOf<SubTransactionEntity>()
            for (t in txns) {
                if (t.accountId !in accountIds) continue
                entities.add(
                    TransactionEntity(
                        id = t.ynabId,
                        planId = planId,
                        accountId = t.accountId,
                        date = t.date,
                        amountMilli = t.amount,
                        payeeId = t.payeeId,
                        categoryId = t.categoryId,
                        memo = t.memo,
                        cleared = parseCleared(t.cleared),
                        approved = t.approved,
                        flagColor = parseFlag(t.flagColor),
                        transferAccountId = t.transferAccountId,
                        transferTransactionId = t.transferTransactionId,
                        importId = t.importId,
                        ynabId = t.ynabId,
                        updatedAt = now,
                        syncStatus = SyncStatus.SYNCED,
                    ),
                )
                for (s in t.subtransactions) {
                    subs.add(
                        SubTransactionEntity(
                            id = s.ynabId ?: UUID.randomUUID().toString(),
                            transactionId = t.ynabId,
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
            entities.chunked(200).forEach { chunk ->
                db.transactionDao().upsertAll(chunk)
            }
            if (subs.isNotEmpty()) {
                subs.chunked(200).forEach { chunk ->
                    db.transactionDao().upsertSubs(chunk)
                }
            }

            onProgress("Done")
            CloudSyncReport(
                planName = planInfo.name,
                accounts = accounts.size,
                categories = cats.categories.size,
                payees = payees.size,
                transactions = entities.size,
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
