package com.cleaningbutton.r2finance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.cleaningbutton.r2finance.data.local.entity.AccountEntity
import com.cleaningbutton.r2finance.data.local.entity.CategoryEntity
import com.cleaningbutton.r2finance.data.local.entity.CategoryGroupEntity
import com.cleaningbutton.r2finance.data.local.entity.PayeeCategoryMemoryEntity
import com.cleaningbutton.r2finance.data.local.entity.PayeeEntity
import com.cleaningbutton.r2finance.data.local.entity.PlanEntity
import com.cleaningbutton.r2finance.data.local.entity.ScheduledTransactionEntity
import com.cleaningbutton.r2finance.data.local.entity.SubTransactionEntity
import com.cleaningbutton.r2finance.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanDao {
    @Query("SELECT * FROM plans WHERE deleted = 0 ORDER BY name")
    fun observePlans(): Flow<List<PlanEntity>>

    @Query("SELECT * FROM plans WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PlanEntity?

    @Query("SELECT * FROM plans WHERE ynabId = :ynabId LIMIT 1")
    suspend fun getByYnabId(ynabId: String): PlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plan: PlanEntity)

    @Query("SELECT * FROM plans WHERE deleted = 0 LIMIT 1")
    suspend fun getDefault(): PlanEntity?
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts WHERE planId = :planId AND deleted = 0 AND closed = 0 ORDER BY name")
    fun observeOpenAccounts(planId: String): Flow<List<AccountEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM accounts
        WHERE planId = :planId AND deleted = 0 AND closed = 0
        """,
    )
    suspend fun countOpen(planId: String): Int

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE ynabId = :ynabId LIMIT 1")
    suspend fun getByYnabId(ynabId: String): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AccountEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(accounts: List<AccountEntity>)

    @Query(
        """
        UPDATE accounts
        SET deleted = 1, updatedAt = :now
        WHERE planId = :planId
          AND syncStatus != 'PENDING_PUSH'
          AND deleted = 0
        """,
    )
    suspend fun softDeleteAllSynced(planId: String, now: Long)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM category_groups WHERE planId = :planId AND deleted = 0 ORDER BY sortOrder, name")
    fun observeGroups(planId: String): Flow<List<CategoryGroupEntity>>

    @Query("SELECT * FROM categories WHERE planId = :planId AND deleted = 0 ORDER BY sortOrder, name")
    fun observeCategories(planId: String): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(group: CategoryGroupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroups(groups: List<CategoryGroupEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategory(category: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(categories: List<CategoryEntity>)

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun getCategory(id: String): CategoryEntity?

    @Query("SELECT * FROM categories WHERE ynabId = :ynabId LIMIT 1")
    suspend fun getCategoryByYnabId(ynabId: String): CategoryEntity?

    @Query("SELECT * FROM category_groups WHERE ynabId = :ynabId LIMIT 1")
    suspend fun getGroupByYnabId(ynabId: String): CategoryGroupEntity?

    @Query("SELECT * FROM categories WHERE planId = :planId AND deleted = 0 AND hidden = 0 ORDER BY name")
    suspend fun listCategories(planId: String): List<CategoryEntity>

    @Query("SELECT * FROM category_groups WHERE planId = :planId AND deleted = 0 ORDER BY sortOrder, name")
    suspend fun listGroups(planId: String): List<CategoryGroupEntity>
}

@Dao
interface PayeeDao {
    @Query("SELECT * FROM payees WHERE planId = :planId AND deleted = 0 ORDER BY name")
    fun observePayees(planId: String): Flow<List<PayeeEntity>>

    @Query("SELECT * FROM payees WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PayeeEntity?

    @Query("SELECT * FROM payees WHERE planId = :planId AND name = :name AND deleted = 0 LIMIT 1")
    suspend fun getByName(planId: String, name: String): PayeeEntity?

    @Query("SELECT * FROM payees WHERE ynabId = :ynabId LIMIT 1")
    suspend fun getByYnabId(ynabId: String): PayeeEntity?

    @Query(
        """
        SELECT * FROM payees
        WHERE planId = :planId AND syncStatus = 'PENDING_PUSH'
        ORDER BY updatedAt ASC
        """,
    )
    suspend fun listPendingPush(planId: String): List<PayeeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(payee: PayeeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(payees: List<PayeeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(memory: PayeeCategoryMemoryEntity)

    @Query("SELECT * FROM payee_category_memory WHERE planId = :planId AND payeeId = :payeeId LIMIT 1")
    suspend fun getMemory(planId: String, payeeId: String): PayeeCategoryMemoryEntity?
}

@Dao
interface TransactionDao {
    @Query(
        """
        SELECT * FROM transactions
        WHERE accountId = :accountId AND deleted = 0
        ORDER BY date DESC, updatedAt DESC
        """,
    )
    fun observeByAccount(accountId: String): Flow<List<TransactionEntity>>

    /**
     * Spending / needs-attention (YNAB-style):
     * - unapproved (always, including transfers)
     * - on-budget, no category / Uncategorized, not a transfer, no splits
     * Approve without a category still removes unapproved rows; uncategorized-approved
     * stay until categorized.
     */
    @Query(
        """
        SELECT t.* FROM transactions t
        INNER JOIN accounts a ON a.id = t.accountId
        WHERE t.planId = :planId AND t.deleted = 0
          AND (
            t.approved = 0
            OR (
              a.onBudget = 1
              AND t.transferAccountId IS NULL
              AND NOT EXISTS (
                SELECT 1 FROM subtransactions s
                WHERE s.transactionId = t.id AND s.deleted = 0
              )
              AND (
                t.categoryId IS NULL
                OR t.categoryId IN (
                  SELECT c.id FROM categories c
                  WHERE c.planId = :planId
                    AND c.deleted = 0
                    AND LOWER(c.name) = 'uncategorized'
                )
              )
            )
          )
        ORDER BY t.date DESC
        """,
    )
    fun observeInbox(planId: String): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT COALESCE(SUM(amountMilli), 0) FROM transactions
        WHERE accountId = :accountId AND deleted = 0
        """,
    )
    fun observeBalanceMilli(accountId: String): Flow<Long>

    @Query(
        """
        SELECT COALESCE(SUM(amountMilli), 0) FROM transactions
        WHERE accountId = :accountId AND deleted = 0
          AND cleared IN ('cleared', 'reconciled')
        """,
    )
    fun observeClearedBalanceMilli(accountId: String): Flow<Long>

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TransactionEntity?

    @Query(
        """
        SELECT id FROM transactions
        WHERE planId = :planId AND syncStatus = 'PENDING_PUSH' AND deleted = 0
        """,
    )
    suspend fun pendingPushIds(planId: String): List<String>

    @Query(
        """
        SELECT * FROM transactions
        WHERE planId = :planId AND syncStatus = 'PENDING_PUSH'
        ORDER BY updatedAt ASC
        """,
    )
    suspend fun listPendingPush(planId: String): List<TransactionEntity>

    @Query(
        """
        SELECT COUNT(*) FROM transactions
        WHERE planId = :planId AND syncStatus = 'PENDING_PUSH'
        """,
    )
    suspend fun countPendingPush(planId: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM transactions
        WHERE planId = :planId AND syncStatus = 'PENDING_PUSH'
        """,
    )
    fun observePendingPushCount(planId: String): Flow<Int>

    @Query("SELECT * FROM transactions WHERE ynabId = :ynabId LIMIT 1")
    suspend fun getByYnabId(ynabId: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE accountId = :accountId AND importId = :importId LIMIT 1")
    suspend fun getByImportId(accountId: String, importId: String): TransactionEntity?

    @Query(
        """
        SELECT COUNT(*) FROM transactions
        WHERE planId = :planId AND deleted = 0
        """,
    )
    suspend fun countForPlan(planId: String): Int

    /** All non-deleted transactions for analytics / reports. */
    @Query(
        """
        SELECT * FROM transactions
        WHERE planId = :planId AND deleted = 0
        ORDER BY date DESC, updatedAt DESC
        """,
    )
    fun observeForPlan(planId: String): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT * FROM transactions
        WHERE planId = :planId AND deleted = 0
        ORDER BY date DESC, updatedAt DESC
        """,
    )
    suspend fun listForPlan(planId: String): List<TransactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(txn: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(txns: List<TransactionEntity>)

    @Update
    suspend fun update(txn: TransactionEntity)

    /**
     * Full-resync reconcile: soft-delete every synced (non-pending) live row so
     * a subsequent upsert of the server live set heals drift without huge IN lists.
     */
    @Query(
        """
        UPDATE transactions
        SET deleted = 1, updatedAt = :now
        WHERE planId = :planId
          AND syncStatus != 'PENDING_PUSH'
          AND deleted = 0
        """,
    )
    suspend fun softDeleteAllSynced(planId: String, now: Long)

    @Query("SELECT * FROM subtransactions WHERE transactionId = :txnId AND deleted = 0")
    suspend fun getSubs(txnId: String): List<SubTransactionEntity>

    /** All non-deleted split lines for a plan (joined via parent txn). */
    @Query(
        """
        SELECT s.* FROM subtransactions s
        INNER JOIN transactions t ON t.id = s.transactionId
        WHERE t.planId = :planId AND s.deleted = 0 AND t.deleted = 0
        """,
    )
    fun observeSubsForPlan(planId: String): Flow<List<SubTransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSubs(subs: List<SubTransactionEntity>)

    @Transaction
    suspend fun upsertWithSubs(txn: TransactionEntity, subs: List<SubTransactionEntity>) {
        upsert(txn)
        if (subs.isNotEmpty()) upsertSubs(subs)
    }
}

@Dao
interface ScheduledDao {
    @Query("SELECT * FROM scheduled_transactions WHERE ynabId = :ynabId LIMIT 1")
    suspend fun getByYnabId(ynabId: String): ScheduledTransactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ScheduledTransactionEntity)
}
