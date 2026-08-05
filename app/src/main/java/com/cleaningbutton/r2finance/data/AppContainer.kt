package com.cleaningbutton.r2finance.data

import android.content.Context
import com.cleaningbutton.r2finance.data.local.R2FinanceDatabase
import com.cleaningbutton.r2finance.data.repository.LedgerRepository
import com.cleaningbutton.r2finance.data.ynab.YnabClient
import com.cleaningbutton.r2finance.data.ynab.YnabImporter
import com.cleaningbutton.r2finance.data.ynab.YnabTokenStore
import com.cleaningbutton.r2finance.update.AppUpdateChecker

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val database: R2FinanceDatabase = R2FinanceDatabase.get(appContext)
    val ledger: LedgerRepository = LedgerRepository(database)
    val updateChecker: AppUpdateChecker = AppUpdateChecker(appContext)
    val ynabTokenStore: YnabTokenStore = YnabTokenStore(appContext)
    val ynabClient: YnabClient = YnabClient(tokenProvider = { ynabTokenStore.getToken() })
    val ynabImporter: YnabImporter = YnabImporter(database, ynabClient)
}
