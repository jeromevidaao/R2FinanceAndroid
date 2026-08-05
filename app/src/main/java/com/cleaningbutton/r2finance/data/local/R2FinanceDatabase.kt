package com.cleaningbutton.r2finance.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.cleaningbutton.r2finance.data.local.dao.AccountDao
import com.cleaningbutton.r2finance.data.local.dao.CategoryDao
import com.cleaningbutton.r2finance.data.local.dao.PayeeDao
import com.cleaningbutton.r2finance.data.local.dao.PlanDao
import com.cleaningbutton.r2finance.data.local.dao.ScheduledDao
import com.cleaningbutton.r2finance.data.local.dao.TransactionDao
import com.cleaningbutton.r2finance.data.local.entity.AccountEntity
import com.cleaningbutton.r2finance.data.local.entity.CategoryEntity
import com.cleaningbutton.r2finance.data.local.entity.CategoryGroupEntity
import com.cleaningbutton.r2finance.data.local.entity.PayeeCategoryMemoryEntity
import com.cleaningbutton.r2finance.data.local.entity.PayeeEntity
import com.cleaningbutton.r2finance.data.local.entity.PlanEntity
import com.cleaningbutton.r2finance.data.local.entity.ScheduledTransactionEntity
import com.cleaningbutton.r2finance.data.local.entity.SubTransactionEntity
import com.cleaningbutton.r2finance.data.local.entity.TransactionEntity
import com.cleaningbutton.r2finance.domain.AccountType
import com.cleaningbutton.r2finance.domain.ClearedStatus
import com.cleaningbutton.r2finance.domain.FlagColor
import com.cleaningbutton.r2finance.domain.ScheduledFrequency
import com.cleaningbutton.r2finance.domain.SyncStatus

class Converters {
    @TypeConverter fun accountTypeToString(v: AccountType): String = v.name
    @TypeConverter fun stringToAccountType(v: String): AccountType = AccountType.valueOf(v)

    @TypeConverter fun clearedToString(v: ClearedStatus): String = v.name
    @TypeConverter fun stringToCleared(v: String): ClearedStatus = ClearedStatus.valueOf(v)

    @TypeConverter fun flagToString(v: FlagColor): String = v.name
    @TypeConverter fun stringToFlag(v: String): FlagColor = FlagColor.valueOf(v)

    @TypeConverter fun syncToString(v: SyncStatus): String = v.name
    @TypeConverter fun stringToSync(v: String): SyncStatus = SyncStatus.valueOf(v)

    @TypeConverter fun freqToString(v: ScheduledFrequency): String = v.name
    @TypeConverter fun stringToFreq(v: String): ScheduledFrequency = ScheduledFrequency.valueOf(v)
}

@Database(
    entities = [
        PlanEntity::class,
        AccountEntity::class,
        CategoryGroupEntity::class,
        CategoryEntity::class,
        PayeeEntity::class,
        PayeeCategoryMemoryEntity::class,
        TransactionEntity::class,
        SubTransactionEntity::class,
        ScheduledTransactionEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class R2FinanceDatabase : RoomDatabase() {
    abstract fun planDao(): PlanDao
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun payeeDao(): PayeeDao
    abstract fun transactionDao(): TransactionDao
    abstract fun scheduledDao(): ScheduledDao

    companion object {
        @Volatile private var instance: R2FinanceDatabase? = null

        fun get(context: Context): R2FinanceDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    R2FinanceDatabase::class.java,
                    "r2finance.db",
                ).build().also { instance = it }
            }
    }
}
