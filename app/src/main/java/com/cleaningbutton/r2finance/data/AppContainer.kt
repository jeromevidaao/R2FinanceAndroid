package com.cleaningbutton.r2finance.data

import android.content.Context
import com.cleaningbutton.r2finance.data.aggregates.LedgerAggregatesStore
import com.cleaningbutton.r2finance.data.auth.AuthApi
import com.cleaningbutton.r2finance.data.auth.SessionStore
import com.cleaningbutton.r2finance.data.cloud.CloudApi
import com.cleaningbutton.r2finance.data.cloud.CloudSync
import com.cleaningbutton.r2finance.data.cloud.ConnectivityMonitor
import com.cleaningbutton.r2finance.data.cloud.SyncCoordinator
import com.cleaningbutton.r2finance.data.local.R2FinanceDatabase
import com.cleaningbutton.r2finance.data.repository.LedgerRepository
import com.cleaningbutton.r2finance.update.AppUpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    /** Process-scoped work (aggregates, warm-up). Survives Activity recreate. */
    val applicationScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: R2FinanceDatabase = R2FinanceDatabase.get(appContext)
    val ledger: LedgerRepository = LedgerRepository(database)
    val updateChecker: AppUpdateChecker = AppUpdateChecker(appContext)
    val sessionStore: SessionStore = SessionStore(appContext)
    val authApi: AuthApi = AuthApi()
    /** All ledger/sync calls send Bearer token from encrypted session store. */
    val cloudApi: CloudApi = CloudApi(tokenProvider = { sessionStore.token })
    val cloudSync: CloudSync = CloudSync(database, cloudApi)
    /** Process-scoped: Room is source of truth; cloud pull/push is background. */
    val syncCoordinator: SyncCoordinator = SyncCoordinator(appContext, database, cloudSync)

    /**
     * In-memory Reflect / Home / account totals. Built on Default; UI just collects.
     * Warm-started from [startBackgroundWarmup].
     */
    val aggregates: LedgerAggregatesStore = LedgerAggregatesStore(ledger, applicationScope)

    /**
     * Auto-flush offline queue when the network returns.
     * Started from [com.cleaningbutton.r2finance.R2FinanceApplication].
     */
    val connectivityMonitor: ConnectivityMonitor = ConnectivityMonitor(appContext) {
        syncCoordinator.syncWhenOnline()
    }

    /**
     * Categorize: local Room immediately, cloud push after ~10s with undo.
     * Shared across screens via [applicationScope].
     */
    val pendingCategorize: PendingCategorizeQueue =
        PendingCategorizeQueue(
            scope = applicationScope,
            ledger = ledger,
            connectivityMonitor = connectivityMonitor,
            syncCoordinator = syncCoordinator,
        )

    /**
     * Categorization list that survives bottom-nav remounts — always Room-first.
     */
    val inboxCache: InboxCache = InboxCache(ledger, applicationScope)

    /**
     * Bank connectors / capital for Accounts — process RAM, not re-fetched on tab enter.
     */
    val connectorsCache: ConnectorsCache = ConnectorsCache(cloudApi, applicationScope)

    private val _planName = MutableStateFlow("R2Finance")
    /** Plan display name for Home top bar (set once at warm). */
    val planName: StateFlow<String> = _planName.asStateFlow()

    /**
     * Process start only (not per-tab):
     * 1. Paint-ready RAM: aggregates + Categorization + plan name from Room
     * 2. One silent cloud hydrate (delta, or full if Room empty)
     * 3. Connectors warm when session token already present
     *
     * Screens must **not** re-call [SyncCoordinator.ensureHydrated] on bottom-nav
     * enter — tab switches should only collect process-scoped StateFlows.
     */
    fun startBackgroundWarmup() {
        applicationScope.launch {
            val plan = ledger.ensureDefaultPlan()
            _planName.value = plan.name.ifBlank { "R2Finance" }
            aggregates.start(plan.id)
            // Room Categorization list ready before first tab open (no empty flash).
            inboxCache.start(plan.id)
            // Single process hydrate; ConnectivityMonitor handles reconnect later.
            syncCoordinator.ensureHydrated(plan.id)
            // Connectors need Bearer — skip until logged in ([warmAuthenticatedCaches]).
            if (sessionStore.isSessionValid()) {
                connectorsCache.ensureWarm(probeBalancesIfNeeded = true)
            }
        }
    }

    /**
     * Call after login / bio unlock when token is live so Accounts is warm
     * before the user opens that tab.
     */
    fun warmAuthenticatedCaches() {
        applicationScope.launch {
            if (!sessionStore.isSessionValid()) return@launch
            connectorsCache.ensureWarm(probeBalancesIfNeeded = true)
        }
    }
}
