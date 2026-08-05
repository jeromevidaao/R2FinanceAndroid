package com.cleaningbutton.r2finance.data

import android.content.Context
import com.cleaningbutton.r2finance.data.auth.AuthApi
import com.cleaningbutton.r2finance.data.auth.SessionStore
import com.cleaningbutton.r2finance.data.cloud.CloudApi
import com.cleaningbutton.r2finance.data.cloud.CloudSync
import com.cleaningbutton.r2finance.data.cloud.ConnectivityMonitor
import com.cleaningbutton.r2finance.data.cloud.SyncCoordinator
import com.cleaningbutton.r2finance.data.local.R2FinanceDatabase
import com.cleaningbutton.r2finance.data.repository.LedgerRepository
import com.cleaningbutton.r2finance.update.AppUpdateChecker

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val database: R2FinanceDatabase = R2FinanceDatabase.get(appContext)
    val ledger: LedgerRepository = LedgerRepository(database)
    val updateChecker: AppUpdateChecker = AppUpdateChecker(appContext)
    val sessionStore: SessionStore = SessionStore(appContext)
    val authApi: AuthApi = AuthApi()
    val cloudApi: CloudApi = CloudApi()
    val cloudSync: CloudSync = CloudSync(database, cloudApi)
    /** Process-scoped: Room is source of truth; cloud pull/push is background. */
    val syncCoordinator: SyncCoordinator = SyncCoordinator(appContext, database, cloudSync)

    /**
     * Auto-flush offline queue when the network returns.
     * Started from [com.cleaningbutton.r2finance.R2FinanceApplication].
     */
    val connectivityMonitor: ConnectivityMonitor = ConnectivityMonitor(appContext) {
        syncCoordinator.syncWhenOnline()
    }
}
