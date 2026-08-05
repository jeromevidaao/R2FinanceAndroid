package com.cleaningbutton.r2finance.data.ynab

import com.cleaningbutton.r2finance.data.local.R2FinanceDatabase
import com.cleaningbutton.r2finance.data.local.entity.AccountEntity
import com.cleaningbutton.r2finance.data.local.entity.CategoryEntity
import com.cleaningbutton.r2finance.data.local.entity.CategoryGroupEntity
import com.cleaningbutton.r2finance.data.local.entity.PayeeCategoryMemoryEntity
import com.cleaningbutton.r2finance.data.local.entity.PayeeEntity
import com.cleaningbutton.r2finance.data.local.entity.PlanEntity
import com.cleaningbutton.r2finance.data.local.entity.ScheduledTransactionEntity
import com.cleaningbutton.r2finance.data.local.entity.SubTransactionEntity
import com.cleaningbutton.r2finance.data.local.entity.TransactionEntity
import com.cleaningbutton.r2finance.domain.SyncStatus
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class YnabImportReport(
    val planName: String,
    val planId: String,
    val accounts: Int,
    val categoryGroups: Int,
    val categories: Int,
    val payees: Int,
    val transactions: Int,
    val subtransactions: Int,
    val scheduled: Int,
    val serverKnowledge: Long,
    val balanceAudit: List<AccountBalanceAudit>,
)

data class AccountBalanceAudit(
    val name: String,
    val ynabBalanceMilli: Long,
    val localBalanceMilli: Long,
) {
    val matches: Boolean get() = ynabBalanceMilli == localBalanceMilli
    val deltaMilli: Long get() = localBalanceMilli - ynabBalanceMilli
}

/**
 * One-shot (re-runnable) full import from YNAB → Room.
 * Idempotent on [ynabId] / import_id.
 */
class YnabImporter(
    private val db: R2FinanceDatabase,
    private val client: YnabClient,
) {
    suspend fun importDefaultPlan(
        sinceDate: String = "1990-01-01",
        onProgress: (String) -> Unit = {},
    ): YnabImportReport = withContext(Dispatchers.IO) {
        onProgress("Listing plans…")
        val plans = client.listPlans()
        val ynabPlan = plans.firstOrNull()
            ?: throw YnabException("No YNAB plans found for this token")

        importPlan(ynabPlan, sinceDate, onProgress)
    }

    suspend fun importPlan(
        ynabPlan: YnabPlanSummary,
        sinceDate: String = "1990-01-01",
        onProgress: (String) -> Unit = {},
    ): YnabImportReport = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val planId = db.planDao().getByYnabId(ynabPlan.id)?.id ?: UUID.randomUUID().toString()
        val currency = ynabPlan.currencyFormat?.isoCode ?: "USD"
        val plan = PlanEntity(
            id = planId,
            name = ynabPlan.name,
            currencyCode = currency,
            ynabId = ynabPlan.id,
            serverKnowledge = 0,
            ynabSyncEnabled = true,
            updatedAt = now,
            syncStatus = SyncStatus.SYNCED,
        )
        db.planDao().upsert(plan)

        onProgress("Accounts…")
        val (yAccounts, skAccounts) = client.listAccounts(ynabPlan.id)
        val accountIdByYnab = mutableMapOf<String, String>()
        val ynabBalanceByLocalId = mutableMapOf<String, Long>()
        for (ya in yAccounts) {
            if (ya.deleted) continue
            val localId = db.accountDao().getByYnabId(ya.id)?.id ?: UUID.randomUUID().toString()
            accountIdByYnab[ya.id] = localId
            ynabBalanceByLocalId[localId] = ya.balance
            db.accountDao().upsert(
                AccountEntity(
                    id = localId,
                    planId = planId,
                    name = ya.name,
                    type = YnabMapper.accountType(ya.type),
                    onBudget = ya.onBudget,
                    closed = ya.closed,
                    note = ya.note,
                    transferPayeeId = null, // remapped after payees
                    ynabId = ya.id,
                    updatedAt = now,
                    syncStatus = SyncStatus.SYNCED,
                    deleted = false,
                ),
            )
        }

        onProgress("Categories…")
        val (yGroups, skCats) = client.listCategories(ynabPlan.id)
        val groupIdByYnab = mutableMapOf<String, String>()
        val categoryIdByYnab = mutableMapOf<String, String>()
        var catCount = 0
        var groupCount = 0
        yGroups.forEachIndexed { index, yg ->
            if (yg.deleted) return@forEachIndexed
            val gLocal = db.categoryDao().getGroupByYnabId(yg.id)?.id ?: UUID.randomUUID().toString()
            groupIdByYnab[yg.id] = gLocal
            groupCount++
            db.categoryDao().upsertGroup(
                CategoryGroupEntity(
                    id = gLocal,
                    planId = planId,
                    name = yg.name,
                    hidden = yg.hidden,
                    internal = yg.name.equals("Internal Master Category", ignoreCase = true) ||
                        yg.name.contains("Credit Card Payments", ignoreCase = true) &&
                        yg.categories.any { it.name.contains("Payment", ignoreCase = true) },
                    sortOrder = index,
                    ynabId = yg.id,
                    updatedAt = now,
                    syncStatus = SyncStatus.SYNCED,
                    deleted = false,
                ),
            )
            for ((ci, yc) in yg.categories.withIndex()) {
                if (yc.deleted) continue
                val cLocal = db.categoryDao().getCategoryByYnabId(yc.id)?.id
                    ?: UUID.randomUUID().toString()
                categoryIdByYnab[yc.id] = cLocal
                catCount++
                db.categoryDao().upsertCategory(
                    CategoryEntity(
                        id = cLocal,
                        planId = planId,
                        categoryGroupId = gLocal,
                        name = yc.name,
                        hidden = yc.hidden,
                        note = yc.note,
                        sortOrder = ci,
                        ynabId = yc.id,
                        updatedAt = now,
                        syncStatus = SyncStatus.SYNCED,
                        deleted = false,
                    ),
                )
            }
        }

        onProgress("Payees…")
        val (yPayees, skPayees) = client.listPayees(ynabPlan.id)
        val payeeIdByYnab = mutableMapOf<String, String>()
        for (yp in yPayees) {
            if (yp.deleted) continue
            val localId = db.payeeDao().getByYnabId(yp.id)?.id ?: UUID.randomUUID().toString()
            payeeIdByYnab[yp.id] = localId
            val transferAcct = yp.transferAccountId?.let { accountIdByYnab[it] }
            db.payeeDao().upsert(
                PayeeEntity(
                    id = localId,
                    planId = planId,
                    name = yp.name,
                    transferAccountId = transferAcct,
                    ynabId = yp.id,
                    updatedAt = now,
                    syncStatus = SyncStatus.SYNCED,
                    deleted = false,
                ),
            )
        }
        // Wire account.transferPayeeId from YNAB transfer payee mapping
        for (ya in yAccounts) {
            if (ya.deleted) continue
            val localAcct = accountIdByYnab[ya.id] ?: continue
            val transferPayeeLocal = ya.transferPayeeId?.let { payeeIdByYnab[it] }
            val existing = db.accountDao().getById(localAcct) ?: continue
            if (existing.transferPayeeId != transferPayeeLocal) {
                db.accountDao().upsert(existing.copy(transferPayeeId = transferPayeeLocal, updatedAt = now))
            }
        }

        onProgress("Transactions…")
        val (yTxns, skTxns) = client.listTransactions(ynabPlan.id, sinceDate)
        var subCount = 0
        var txnCount = 0
        for (yt in yTxns) {
            val localAcct = accountIdByYnab[yt.accountId] ?: continue
            val existing = db.transactionDao().getByYnabId(yt.id)
                ?: yt.importId?.let { db.transactionDao().getByImportId(localAcct, it) }
            val localId = existing?.id ?: UUID.randomUUID().toString()
            if (yt.deleted) {
                if (existing != null) {
                    db.transactionDao().upsert(existing.copy(deleted = true, updatedAt = now))
                }
                continue
            }
            txnCount++
            val txn = TransactionEntity(
                id = localId,
                planId = planId,
                accountId = localAcct,
                date = yt.date,
                amountMilli = yt.amount,
                payeeId = yt.payeeId?.let { payeeIdByYnab[it] },
                categoryId = yt.categoryId?.let { categoryIdByYnab[it] },
                memo = yt.memo,
                cleared = YnabMapper.cleared(yt.cleared),
                approved = yt.approved,
                flagColor = YnabMapper.flagColor(yt.flagColor),
                transferAccountId = yt.transferAccountId?.let { accountIdByYnab[it] },
                transferTransactionId = null, // second pass if needed
                matchedTransactionId = null,
                importId = yt.importId,
                importPayeeName = yt.importPayeeName,
                importPayeeNameOriginal = yt.importPayeeNameOriginal,
                ynabId = yt.id,
                updatedAt = now,
                syncStatus = SyncStatus.SYNCED,
                deleted = false,
            )
            val subs = yt.subtransactions.filter { !it.deleted }.map { ys ->
                subCount++
                SubTransactionEntity(
                    id = UUID.randomUUID().toString(),
                    transactionId = localId,
                    amountMilli = ys.amount,
                    payeeId = ys.payeeId?.let { payeeIdByYnab[it] },
                    categoryId = ys.categoryId?.let { categoryIdByYnab[it] },
                    memo = ys.memo,
                    transferAccountId = ys.transferAccountId?.let { accountIdByYnab[it] },
                    ynabId = ys.id,
                    updatedAt = now,
                    deleted = false,
                )
            }
            // Parent category null when split
            val finalTxn = if (subs.isNotEmpty()) txn.copy(categoryId = null) else txn
            db.transactionDao().upsertWithSubs(finalTxn, subs)

            val payeeId = finalTxn.payeeId
            val catId = finalTxn.categoryId
            if (payeeId != null && catId != null && finalTxn.approved) {
                db.payeeDao().upsertMemory(
                    PayeeCategoryMemoryEntity(planId, payeeId, catId, now),
                )
            }
        }

        onProgress("Scheduled…")
        val (ySched, skSched) = client.listScheduled(ynabPlan.id)
        var schedCount = 0
        for (ys in ySched) {
            if (ys.deleted) continue
            val localAcct = accountIdByYnab[ys.accountId] ?: continue
            schedCount++
            val existingId = db.scheduledDao().getByYnabId(ys.id)?.id ?: UUID.randomUUID().toString()
            db.scheduledDao().upsert(
                ScheduledTransactionEntity(
                    id = existingId,
                    planId = planId,
                    accountId = localAcct,
                    dateFirst = ys.dateFirst,
                    dateNext = ys.dateNext,
                    frequency = YnabMapper.frequency(ys.frequency),
                    amountMilli = ys.amount,
                    payeeId = ys.payeeId?.let { payeeIdByYnab[it] },
                    categoryId = ys.categoryId?.let { categoryIdByYnab[it] },
                    memo = ys.memo,
                    flagColor = YnabMapper.flagColor(ys.flagColor),
                    transferAccountId = ys.transferAccountId?.let { accountIdByYnab[it] },
                    ynabId = ys.id,
                    updatedAt = now,
                    syncStatus = SyncStatus.SYNCED,
                    deleted = false,
                ),
            )
        }

        val knowledge = maxOf(skAccounts, skCats, skPayees, skTxns, skSched)
        db.planDao().upsert(plan.copy(serverKnowledge = knowledge, updatedAt = now))

        onProgress("Auditing balances…")
        val audit = ynabBalanceByLocalId.map { (localId, ynabBal) ->
            val acct = db.accountDao().getById(localId)
            val localBal = sumAccountBalance(localId)
            AccountBalanceAudit(
                name = acct?.name ?: localId,
                ynabBalanceMilli = ynabBal,
                localBalanceMilli = localBal,
            )
        }

        onProgress("Done")
        YnabImportReport(
            planName = ynabPlan.name,
            planId = planId,
            accounts = accountIdByYnab.size,
            categoryGroups = groupCount,
            categories = catCount,
            payees = payeeIdByYnab.size,
            transactions = txnCount,
            subtransactions = subCount,
            scheduled = schedCount,
            serverKnowledge = knowledge,
            balanceAudit = audit,
        )
    }

    private fun sumAccountBalance(accountId: String): Long {
        db.openHelper.readableDatabase.query(
            "SELECT COALESCE(SUM(amountMilli),0) FROM transactions WHERE accountId=? AND deleted=0",
            arrayOf(accountId),
        ).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }
}
