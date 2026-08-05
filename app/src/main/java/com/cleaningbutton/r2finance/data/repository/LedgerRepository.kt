package com.cleaningbutton.r2finance.data.repository

import com.cleaningbutton.r2finance.data.local.R2FinanceDatabase
import com.cleaningbutton.r2finance.data.local.entity.AccountEntity
import com.cleaningbutton.r2finance.data.local.entity.CategoryEntity
import com.cleaningbutton.r2finance.data.local.entity.CategoryGroupEntity
import com.cleaningbutton.r2finance.data.local.entity.PayeeCategoryMemoryEntity
import com.cleaningbutton.r2finance.data.local.entity.PayeeEntity
import com.cleaningbutton.r2finance.data.local.entity.PlanEntity
import com.cleaningbutton.r2finance.data.local.entity.SubTransactionEntity
import com.cleaningbutton.r2finance.data.local.entity.TransactionEntity
import com.cleaningbutton.r2finance.domain.AccountType
import com.cleaningbutton.r2finance.domain.ClearedStatus
import com.cleaningbutton.r2finance.domain.DomainRules
import com.cleaningbutton.r2finance.domain.SyncStatus
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalCoroutinesApi::class)

data class AccountWithBalance(
    val account: AccountEntity,
    val balanceMilli: Long,
    val clearedBalanceMilli: Long,
)

data class CategoryTreeNode(
    val group: CategoryGroupEntity,
    val categories: List<CategoryEntity>,
)

/** Register / inbox row with resolved names for UI. */
data class TransactionRow(
    val txn: TransactionEntity,
    val payeeName: String?,
    val categoryName: String?,
    val accountName: String? = null,
)

class LedgerRepository(
    private val db: R2FinanceDatabase,
) {
    private val plans = db.planDao()
    private val accounts = db.accountDao()
    private val categories = db.categoryDao()
    private val payees = db.payeeDao()
    private val txns = db.transactionDao()

    fun observePlans() = plans.observePlans()

    suspend fun ensureDefaultPlan(): PlanEntity {
        plans.getDefault()?.let { return it }
        val plan = PlanEntity(
            id = UUID.randomUUID().toString(),
            name = "My Plan",
            syncStatus = SyncStatus.LOCAL_ONLY,
        )
        plans.upsert(plan)
        return plan
    }

    fun observeAccountsWithBalances(planId: String): Flow<List<AccountWithBalance>> {
        return accounts.observeOpenAccounts(planId).flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf(emptyList())
            } else {
                val flows = list.map { acct ->
                    combine(
                        txns.observeBalanceMilli(acct.id),
                        txns.observeClearedBalanceMilli(acct.id),
                    ) { bal, cleared ->
                        AccountWithBalance(acct, bal, cleared)
                    }
                }
                combine(flows) { it.toList() }
            }
        }
    }

    fun observeRegister(accountId: String) = txns.observeByAccount(accountId)

    fun observeInbox(planId: String) = txns.observeInbox(planId)

    fun observeRegisterRows(accountId: String, planId: String): Flow<List<TransactionRow>> {
        return combine(
            txns.observeByAccount(accountId),
            payees.observePayees(planId),
            categories.observeCategories(planId),
        ) { list, payeeList, catList ->
            val payeeMap = payeeList.associateBy { it.id }
            val catMap = catList.associateBy { it.id }
            list.map { t ->
                TransactionRow(
                    txn = t,
                    payeeName = t.payeeId?.let { payeeMap[it]?.name },
                    categoryName = when {
                        t.categoryId != null -> catMap[t.categoryId]?.name
                        else -> null
                    },
                )
            }
        }
    }

    fun observeInboxRows(planId: String): Flow<List<TransactionRow>> {
        return combine(
            txns.observeInbox(planId),
            payees.observePayees(planId),
            categories.observeCategories(planId),
            accounts.observeOpenAccounts(planId),
        ) { list, payeeList, catList, acctList ->
            val payeeMap = payeeList.associateBy { it.id }
            val catMap = catList.associateBy { it.id }
            val acctMap = acctList.associateBy { it.id }
            list.map { t ->
                TransactionRow(
                    txn = t,
                    payeeName = t.payeeId?.let { payeeMap[it]?.name },
                    categoryName = t.categoryId?.let { catMap[it]?.name },
                    accountName = acctMap[t.accountId]?.name,
                )
            }
        }
    }

    fun observeCategoryTree(planId: String): Flow<List<CategoryTreeNode>> {
        return combine(
            categories.observeGroups(planId),
            categories.observeCategories(planId),
        ) { groups, cats ->
            groups.map { g ->
                CategoryTreeNode(
                    group = g,
                    categories = cats.filter { it.categoryGroupId == g.id },
                )
            }
        }
    }

    suspend fun listCategories(planId: String): List<CategoryEntity> =
        categories.listCategories(planId)

    suspend fun countTransactions(planId: String): Int = txns.countForPlan(planId)

    suspend fun createAccount(
        planId: String,
        name: String,
        type: AccountType,
        onBudget: Boolean = true,
    ): AccountEntity {
        val acct = AccountEntity(
            id = UUID.randomUUID().toString(),
            planId = planId,
            name = name,
            type = type,
            onBudget = onBudget,
            syncStatus = SyncStatus.PENDING_PUSH,
        )
        accounts.upsert(acct)
        return acct
    }

    suspend fun createCategoryGroup(planId: String, name: String): CategoryGroupEntity {
        val g = CategoryGroupEntity(
            id = UUID.randomUUID().toString(),
            planId = planId,
            name = name,
            syncStatus = SyncStatus.PENDING_PUSH,
        )
        categories.upsertGroup(g)
        return g
    }

    suspend fun createCategory(
        planId: String,
        groupId: String,
        name: String,
    ): CategoryEntity {
        val c = CategoryEntity(
            id = UUID.randomUUID().toString(),
            planId = planId,
            categoryGroupId = groupId,
            name = name,
            syncStatus = SyncStatus.PENDING_PUSH,
        )
        categories.upsertCategory(c)
        return c
    }

    suspend fun ensurePayee(planId: String, name: String): PayeeEntity {
        val trimmed = name.trim()
        payees.getByName(planId, trimmed)?.let { return it }
        val p = PayeeEntity(
            id = UUID.randomUUID().toString(),
            planId = planId,
            name = trimmed,
            syncStatus = SyncStatus.PENDING_PUSH,
        )
        payees.upsert(p)
        return p
    }

    /**
     * Create a simple (non-split) transaction.
     */
    suspend fun addTransaction(
        planId: String,
        accountId: String,
        date: String,
        amountMilli: Long,
        payeeName: String?,
        categoryId: String?,
        memo: String?,
        approved: Boolean = true,
        cleared: ClearedStatus = ClearedStatus.uncleared,
    ): TransactionEntity {
        val payeeId = payeeName?.trim()?.takeIf { it.isNotEmpty() }?.let {
            ensurePayee(planId, it).id
        }
        val txn = TransactionEntity(
            id = UUID.randomUUID().toString(),
            planId = planId,
            accountId = accountId,
            date = date,
            amountMilli = amountMilli,
            payeeId = payeeId,
            categoryId = categoryId,
            memo = memo,
            approved = approved,
            cleared = cleared,
            syncStatus = SyncStatus.PENDING_PUSH,
        )
        txns.upsert(txn)
        if (payeeId != null && categoryId != null) {
            payees.upsertMemory(
                PayeeCategoryMemoryEntity(planId, payeeId, categoryId),
            )
        }
        return txn
    }

    suspend fun setCategory(transactionId: String, categoryId: String?) {
        val txn = txns.getById(transactionId) ?: return
        val updated = txn.copy(
            categoryId = categoryId,
            updatedAt = System.currentTimeMillis(),
            syncStatus = SyncStatus.PENDING_PUSH,
        )
        txns.update(updated)
        val payeeId = updated.payeeId
        if (payeeId != null && categoryId != null) {
            payees.upsertMemory(
                PayeeCategoryMemoryEntity(updated.planId, payeeId, categoryId),
            )
        }
    }

    suspend fun approve(transactionId: String) {
        val txn = txns.getById(transactionId) ?: return
        txns.update(
            txn.copy(
                approved = true,
                updatedAt = System.currentTimeMillis(),
                syncStatus = SyncStatus.PENDING_PUSH,
            ),
        )
    }

    suspend fun addSplitTransaction(
        planId: String,
        accountId: String,
        date: String,
        amountMilli: Long,
        payeeName: String?,
        memo: String?,
        lines: List<Pair<Long, String?>>, // amountMilli to categoryId
    ): TransactionEntity {
        require(DomainRules.splitAmountsValid(amountMilli, lines.map { it.first })) {
            "Split lines must sum to parent amount"
        }
        val payeeId = payeeName?.trim()?.takeIf { it.isNotEmpty() }?.let {
            ensurePayee(planId, it).id
        }
        val txnId = UUID.randomUUID().toString()
        val txn = TransactionEntity(
            id = txnId,
            planId = planId,
            accountId = accountId,
            date = date,
            amountMilli = amountMilli,
            payeeId = payeeId,
            categoryId = null,
            memo = memo,
            approved = true,
            syncStatus = SyncStatus.PENDING_PUSH,
        )
        val subs = lines.map { (amt, catId) ->
            SubTransactionEntity(
                id = UUID.randomUUID().toString(),
                transactionId = txnId,
                amountMilli = amt,
                categoryId = catId,
            )
        }
        txns.upsertWithSubs(txn, subs)
        return txn
    }

    suspend fun getAccount(id: String) = accounts.getById(id)
}
