package com.cleaningbutton.r2finance.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cleaningbutton.r2finance.domain.AccountType
import com.cleaningbutton.r2finance.domain.ClearedStatus
import com.cleaningbutton.r2finance.domain.FlagColor
import com.cleaningbutton.r2finance.domain.ScheduledFrequency
import com.cleaningbutton.r2finance.domain.SyncStatus

@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey val id: String,
    val name: String,
    val currencyCode: String = "USD",
    val dateFormat: String? = null,
    /** YNAB plan id while linked. */
    val ynabId: String? = null,
    val serverKnowledge: Long = 0,
    val ynabSyncEnabled: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY,
    val deleted: Boolean = false,
)

@Entity(
    tableName = "accounts",
    foreignKeys = [
        ForeignKey(
            entity = PlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("planId"), Index("ynabId"), Index("transferPayeeId")],
)
data class AccountEntity(
    @PrimaryKey val id: String,
    val planId: String,
    val name: String,
    val type: AccountType,
    val onBudget: Boolean = true,
    val closed: Boolean = false,
    val note: String? = null,
    /** Payee used when transferring *into* this account. */
    val transferPayeeId: String? = null,
    val lastReconciledAt: Long? = null,
    val ynabId: String? = null,
    /**
     * Authoritative working balance from R2FinanceAPI/YNAB (milliunits).
     * Prefer this for Home / account totals — summing Room txs drifts when
     * the local ledger is partial, mid-sync, or missing older history.
     * Null until the first successful cloud account upsert.
     */
    val balanceMilli: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY,
    val deleted: Boolean = false,
)

@Entity(
    tableName = "category_groups",
    foreignKeys = [
        ForeignKey(
            entity = PlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("planId"), Index("ynabId")],
)
data class CategoryGroupEntity(
    @PrimaryKey val id: String,
    val planId: String,
    val name: String,
    val hidden: Boolean = false,
    val internal: Boolean = false,
    val sortOrder: Int = 0,
    val ynabId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY,
    val deleted: Boolean = false,
)

@Entity(
    tableName = "categories",
    foreignKeys = [
        ForeignKey(
            entity = PlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CategoryGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryGroupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("planId"), Index("categoryGroupId"), Index("ynabId")],
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    val planId: String,
    val categoryGroupId: String,
    val name: String,
    val hidden: Boolean = false,
    val internal: Boolean = false,
    val note: String? = null,
    val sortOrder: Int = 0,
    /** Hex color from cloud DDB (Reflect / charts); null until first sync. */
    val color: String? = null,
    val ynabId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY,
    val deleted: Boolean = false,
)

@Entity(
    tableName = "payees",
    foreignKeys = [
        ForeignKey(
            entity = PlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("planId"), Index("ynabId"), Index("transferAccountId")],
)
data class PayeeEntity(
    @PrimaryKey val id: String,
    val planId: String,
    val name: String,
    /** If set, this payee is the transfer target for [transferAccountId]. */
    val transferAccountId: String? = null,
    val ynabId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY,
    val deleted: Boolean = false,
)

/** Last-used category for auto-categorize (YNAB product behavior). */
@Entity(
    tableName = "payee_category_memory",
    primaryKeys = ["planId", "payeeId"],
    indices = [Index("payeeId"), Index("categoryId")],
)
data class PayeeCategoryMemoryEntity(
    val planId: String,
    val payeeId: String,
    val categoryId: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = PlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("planId"),
        Index("accountId"),
        Index("payeeId"),
        Index("categoryId"),
        Index("ynabId"),
        // import_id dedupe is enforced in importer (nullable unique is awkward in SQLite).
        Index(value = ["accountId", "importId"]),
        Index("date"),
    ],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val planId: String,
    val accountId: String,
    /** ISO date YYYY-MM-DD. */
    val date: String,
    val amountMilli: Long,
    val payeeId: String? = null,
    val categoryId: String? = null,
    val memo: String? = null,
    val cleared: ClearedStatus = ClearedStatus.uncleared,
    val approved: Boolean = true,
    val flagColor: FlagColor = FlagColor.none,
    val flagName: String? = null,
    val transferAccountId: String? = null,
    val transferTransactionId: String? = null,
    val matchedTransactionId: String? = null,
    val importId: String? = null,
    val importPayeeName: String? = null,
    val importPayeeNameOriginal: String? = null,
    val ynabId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY,
    val deleted: Boolean = false,
    /** Plaid match id when cloud enrichment linked this spend. */
    val plaidTransactionId: String? = null,
    val plaidMerchantName: String? = null,
    /** Raw Plaid name (Venmo: Person "note"). */
    val plaidName: String? = null,
    /** Venmo Personal note as "Person - note". */
    val plaidDescription: String? = null,
    val plaidPaymentChannel: String? = null,
    val plaidPfc: String? = null,
    /**
     * Short place label for categorize UI:
     * US → "City, ST"; outside US → "City, Country".
     */
    val locationDisplay: String? = null,
    val locationSource: String? = null,
    /** Amazon order enrichment from Chrome extension → DDB. */
    val amazonOrderNumber: String? = null,
    val amazonOrderUrl: String? = null,
    /** Pipe-joined item titles for Room simplicity. */
    val amazonItemsJoined: String? = null,
    val amazonItemsSummary: String? = null,
)

@Entity(
    tableName = "subtransactions",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("transactionId"), Index("categoryId"), Index("ynabId")],
)
data class SubTransactionEntity(
    @PrimaryKey val id: String,
    val transactionId: String,
    val amountMilli: Long,
    val payeeId: String? = null,
    val categoryId: String? = null,
    val memo: String? = null,
    val transferAccountId: String? = null,
    val transferTransactionId: String? = null,
    val ynabId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

@Entity(
    tableName = "scheduled_transactions",
    foreignKeys = [
        ForeignKey(
            entity = PlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("planId"), Index("accountId"), Index("ynabId")],
)
data class ScheduledTransactionEntity(
    @PrimaryKey val id: String,
    val planId: String,
    val accountId: String,
    val dateFirst: String,
    val dateNext: String,
    val frequency: ScheduledFrequency = ScheduledFrequency.monthly,
    val amountMilli: Long,
    val payeeId: String? = null,
    val categoryId: String? = null,
    val memo: String? = null,
    val flagColor: FlagColor = FlagColor.none,
    val transferAccountId: String? = null,
    val ynabId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY,
    val deleted: Boolean = false,
)
