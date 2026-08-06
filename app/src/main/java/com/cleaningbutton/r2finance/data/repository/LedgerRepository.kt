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
    val categoryGroupName: String? = null,
    /** Hex color from category DDB row (Reflect / rails); null if unset. */
    val categoryColor: String? = null,
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
        // Stable id so cloud sync (R2FinanceAPI plan "default") matches local Room.
        plans.getById("default")?.let { return it }
        plans.getDefault()?.let { return it }
        val plan = PlanEntity(
            id = "default",
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
            categories.observeGroups(planId),
            accounts.observeOpenAccounts(planId),
        ) { list, payeeList, catList, groupList, acctList ->
            val payeeMap = payeeList.associateBy { it.id }
            val catMap = catList.associateBy { it.id }
            val groupMap = groupList.associateBy { it.id }
            val acctMap = acctList.associateBy { it.id }
            list.map { t ->
                val cat = t.categoryId?.let { catMap[it] }
                TransactionRow(
                    txn = t,
                    payeeName = t.payeeId?.let { payeeMap[it]?.name },
                    categoryName = cat?.name,
                    accountName = acctMap[t.accountId]?.name,
                    categoryGroupName = cat?.categoryGroupId?.let { groupMap[it]?.name },
                    categoryColor = cat?.color,
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

    /** Full plan transactions for reports (Room is source of truth). */
    fun observePlanTransactions(planId: String): Flow<List<TransactionEntity>> =
        txns.observeForPlan(planId)

    fun observePayees(planId: String) = payees.observePayees(planId)

    fun observeCategories(planId: String) = categories.observeCategories(planId)

    fun observeCategoryGroups(planId: String) = categories.observeGroups(planId)

    /** One-shot category tree for categorize picker headers. */
    suspend fun listCategoryTree(planId: String): List<CategoryTreeNode> {
        val groups = categories.listGroups(planId)
        val cats = categories.listCategories(planId)
        return groups.map { g ->
            CategoryTreeNode(
                group = g,
                categories = cats.filter { it.categoryGroupId == g.id },
            )
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
            approved = true,
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

    /** Bulk categorize (same category on many inbox rows). Marks approved. */
    suspend fun setCategoryMany(transactionIds: Collection<String>, categoryId: String?) {
        for (id in transactionIds) {
            setCategory(id, categoryId)
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

    suspend fun approveMany(transactionIds: Collection<String>) {
        for (id in transactionIds) {
            approve(id)
        }
    }

    /**
     * Detail edit for inbox / register: memo, amount, optional payee rename.
     * Offline-first Room write (PENDING_PUSH).
     */
    suspend fun updateTransactionDetails(
        transactionId: String,
        amountMilli: Long? = null,
        memo: String? = null,
        clearMemo: Boolean = false,
        payeeName: String? = null,
        categoryId: String? = null,
        setCategory: Boolean = false,
        approved: Boolean? = null,
    ) {
        val txn = txns.getById(transactionId) ?: return
        var payeeId = txn.payeeId
        if (payeeName != null) {
            val trimmed = payeeName.trim()
            payeeId = if (trimmed.isEmpty()) {
                null
            } else {
                ensurePayee(txn.planId, trimmed).id
            }
        }
        val updated = txn.copy(
            amountMilli = amountMilli ?: txn.amountMilli,
            memo = when {
                clearMemo -> null
                memo != null -> memo.ifBlank { null }
                else -> txn.memo
            },
            payeeId = payeeId,
            categoryId = if (setCategory) categoryId else txn.categoryId,
            approved = approved ?: txn.approved,
            updatedAt = System.currentTimeMillis(),
            syncStatus = SyncStatus.PENDING_PUSH,
        )
        txns.update(updated)
        if (setCategory && payeeId != null && categoryId != null) {
            payees.upsertMemory(
                PayeeCategoryMemoryEntity(updated.planId, payeeId, categoryId),
            )
        }
    }

    /** Categories suitable for the categorize picker (hide internal / CC payments / hidden). */
    suspend fun listAssignableCategories(planId: String): List<CategoryEntity> {
        val groups = categories.listGroups(planId).associateBy { it.id }
        return categories.listCategories(planId).filter { cat ->
            if (cat.hidden || cat.deleted) return@filter false
            val g = groups[cat.categoryGroupId]
            if (g?.hidden == true || g?.deleted == true) return@filter false
            val gName = g?.name.orEmpty()
            if (gName.equals("Internal Master Category", ignoreCase = true)) return@filter false
            if (gName.equals("Credit Card Payments", ignoreCase = true)) return@filter false
            if (cat.name.equals("Uncategorized", ignoreCase = true)) return@filter false
            if (cat.name.contains("Ready to Assign", ignoreCase = true)) return@filter false
            true
        }
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
