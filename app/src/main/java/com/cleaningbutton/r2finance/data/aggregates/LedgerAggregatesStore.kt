package com.cleaningbutton.r2finance.data.aggregates

import com.cleaningbutton.r2finance.data.local.entity.AccountEntity
import com.cleaningbutton.r2finance.data.local.entity.CategoryEntity
import com.cleaningbutton.r2finance.data.local.entity.CategoryGroupEntity
import com.cleaningbutton.r2finance.data.local.entity.PayeeEntity
import com.cleaningbutton.r2finance.data.local.entity.SubTransactionEntity
import com.cleaningbutton.r2finance.data.local.entity.TransactionEntity
import com.cleaningbutton.r2finance.data.repository.AccountWithBalance
import com.cleaningbutton.r2finance.data.repository.LedgerRepository
import com.cleaningbutton.r2finance.domain.Analytics
import com.cleaningbutton.r2finance.domain.AnalyticsSplitLine
import com.cleaningbutton.r2finance.domain.AnalyticsTxn
import com.cleaningbutton.r2finance.domain.CategoryColors
import com.cleaningbutton.r2finance.domain.ClearedStatus
import com.cleaningbutton.r2finance.domain.PeriodMode
import com.cleaningbutton.r2finance.domain.PresetId
import com.cleaningbutton.r2finance.domain.SpendingReport
import com.cleaningbutton.r2finance.domain.TrendPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-scoped in-memory ledger aggregates.
 *
 * Room remains source of truth; this store:
 * - Maps transactions (+ splits) once on [Dispatchers.Default]
 * - Precomputes Reflect / Spending Breakdown / Home totals
 * - Keeps the previous snapshot on screen while recomputing (no zero-flash)
 * - Debounces during bulk cloud hydrate so UI stays smooth
 */
data class HomeAggregates(
    val onBudgetTotal: Long = 0L,
    val trackingTotal: Long = 0L,
    val onBudgetCount: Int = 0,
    val trackingCount: Int = 0,
    val inboxCount: Int = 0,
)

data class LedgerAggregates(
    /** True after at least one successful background build. */
    val ready: Boolean = false,
    /** True while a rebuild is in flight (previous numbers still valid). */
    val computing: Boolean = false,
    val planId: String = "",
    val txnCount: Int = 0,
    val analyticsTxns: List<AnalyticsTxn> = emptyList(),
    val months: List<String> = emptyList(),
    val colorById: Map<String, String> = emptyMap(),
    val categoryNames: Map<String, String> = emptyMap(),
    val groupNames: Map<String, String> = emptyMap(),
    val payeeNames: Map<String, String> = emptyMap(),
    val accountNames: Map<String, String> = emptyMap(),
    /** Reflect: current calendar month (or latest with activity). */
    val reflectMonthKey: String = "",
    val reflectReport: SpendingReport? = null,
    val incomeTrend: List<TrendPoint> = emptyList(),
    val incomeInsight: String = "",
    /** PresetId.key → report */
    val presetReports: Map<String, SpendingReport> = emptyMap(),
    /** YYYY-MM → report (all months that have activity + current) */
    val monthReports: Map<String, SpendingReport> = emptyMap(),
    val home: HomeAggregates = HomeAggregates(),
    val accounts: List<AccountWithBalance> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerAggregatesStore(
    private val ledger: LedgerRepository,
    appScope: CoroutineScope,
) {
    private val scope = appScope + Dispatchers.Default
    private val startMutex = Mutex()
    private var collectJob: Job? = null
    private var startedPlanId: String? = null

    private val _state = MutableStateFlow(LedgerAggregates())
    val state: StateFlow<LedgerAggregates> = _state.asStateFlow()

    /**
     * Begin (or switch) observing [planId]. Safe to call multiple times; idempotent per plan.
     * Starts immediately so Home/Reflect open with warm aggregates after first Room emit.
     */
    suspend fun start(planId: String) {
        startMutex.withLock {
            if (startedPlanId == planId && collectJob?.isActive == true) return
            collectJob?.cancel()
            startedPlanId = planId
            _state.value = _state.value.copy(planId = planId, computing = true)
            collectJob =
                scope.launch {
                    // Nested combine (max 5 arity on kotlinx combine helpers).
                    val ledgerCore =
                        combine(
                            ledger.observePlanTransactions(planId),
                            ledger.observePlanSubTransactions(planId),
                            ledger.observeCategories(planId),
                            ledger.observeCategoryGroups(planId),
                            ledger.observePayees(planId),
                        ) { txns, subs, cats, groups, payees ->
                            LedgerCore(txns, subs, cats, groups, payees)
                        }
                    combine(
                        ledgerCore,
                        ledger.observeOpenAccounts(planId),
                    ) { core, accts ->
                        Snap(
                            transactions = core.transactions,
                            subs = core.subs,
                            categories = core.categories,
                            groups = core.groups,
                            payees = core.payees,
                            accounts = accts,
                        )
                    }
                        .debounce(80)
                        .distinctUntilChanged()
                        .mapLatest { snap ->
                            _state.value = _state.value.copy(computing = true)
                            withContext(Dispatchers.Default) {
                                AggregatesBuilder.build(planId, snap)
                            }
                        }
                        .collect { built ->
                            _state.value = built
                        }
                }
        }
    }

    /** Instant lookup; builds from cached analytics on Default if missing (rare). */
    suspend fun report(
        mode: PeriodMode,
        periodKey: String,
    ): SpendingReport? {
        val s = _state.value
        if (!s.ready && s.analyticsTxns.isEmpty()) return null
        cachedReport(s, mode, periodKey)?.let { return it }
        return withContext(Dispatchers.Default) {
            buildOne(s, mode, periodKey)
        }
    }

    fun reportOrNull(mode: PeriodMode, periodKey: String): SpendingReport? {
        val s = _state.value
        return cachedReport(s, mode, periodKey)
    }

    companion object {
        fun cachedReport(
            s: LedgerAggregates,
            mode: PeriodMode,
            periodKey: String,
        ): SpendingReport? =
            when (mode) {
                PeriodMode.MONTH -> s.monthReports[periodKey]
                PeriodMode.PRESET -> s.presetReports[periodKey]
                PeriodMode.YEAR -> null
                PeriodMode.ALL -> s.presetReports[PresetId.ALL.key]
            }

        fun buildOne(
            s: LedgerAggregates,
            mode: PeriodMode,
            periodKey: String,
        ): SpendingReport =
            Analytics.buildSpendingReport(
                transactions = s.analyticsTxns,
                mode = mode,
                periodKey = periodKey,
                categoryNames = s.categoryNames,
                groupNames = s.groupNames,
                payeeNames = s.payeeNames,
                accountNames = s.accountNames,
                approvedOnly = true,
            )
    }
}

private data class LedgerCore(
    val transactions: List<TransactionEntity>,
    val subs: List<SubTransactionEntity>,
    val categories: List<CategoryEntity>,
    val groups: List<CategoryGroupEntity>,
    val payees: List<PayeeEntity>,
)

internal data class Snap(
    val transactions: List<TransactionEntity>,
    val subs: List<SubTransactionEntity>,
    val categories: List<CategoryEntity>,
    val groups: List<CategoryGroupEntity>,
    val payees: List<PayeeEntity>,
    val accounts: List<AccountEntity>,
)

/**
 * Pure builder so unit tests can cover aggregate assembly without Room.
 */
object AggregatesBuilder {
    internal fun build(planId: String, snap: Snap): LedgerAggregates {
        val catGroup = snap.categories.associate { it.id to it.categoryGroupId }
        val catNames = snap.categories.associate { it.id to it.name }
        val groupNames = snap.groups.associate { it.id to it.name }
        val payeeNames = snap.payees.associate { it.id to it.name }
        val accountNames = snap.accounts.associate { it.id to it.name }
        val colorById =
            snap.categories.mapNotNull { c ->
                val hex = c.color
                if (CategoryColors.isHex(hex)) c.id to hex!! else null
            }.toMap()

        val subsByTxn =
            snap.subs.groupBy { it.transactionId }

        val analyticsTxns =
            snap.transactions.map { t ->
                val lines = subsByTxn[t.id].orEmpty()
                AnalyticsTxn(
                    date = t.date,
                    amountMilli = t.amountMilli,
                    categoryId = t.categoryId,
                    categoryGroupId = t.categoryId?.let { catGroup[it] },
                    payeeId = t.payeeId,
                    accountId = t.accountId,
                    transferAccountId = t.transferAccountId,
                    approved = t.approved,
                    splitLines =
                        lines.map { s ->
                            AnalyticsSplitLine(
                                amountMilli = s.amountMilli,
                                categoryId = s.categoryId,
                                categoryGroupId = s.categoryId?.let { catGroup[it] },
                                payeeId = s.payeeId,
                                transferAccountId = s.transferAccountId,
                            )
                        },
                )
            }

        val months = Analytics.listMonths(analyticsTxns)
        val cur = Analytics.currentMonthKey()
        val reflectMonthKey =
            when {
                months.isEmpty() -> cur
                cur in months -> cur
                else -> months.first()
            }

        fun report(mode: PeriodMode, key: String) =
            Analytics.buildSpendingReport(
                transactions = analyticsTxns,
                mode = mode,
                periodKey = key,
                categoryNames = catNames,
                groupNames = groupNames,
                payeeNames = payeeNames,
                accountNames = accountNames,
                approvedOnly = true,
            )

        val monthKeysToCache =
            (months + reflectMonthKey + Analytics.lastNMonthKeys(reflectMonthKey, 6))
                .toSet()
                .filter { it.isNotBlank() }

        val monthReports = monthKeysToCache.associateWith { report(PeriodMode.MONTH, it) }

        val presetReports =
            PresetId.entries.associate { p ->
                p.key to report(PeriodMode.PRESET, p.key)
            }

        val reflectReport = monthReports[reflectMonthKey] ?: report(PeriodMode.MONTH, reflectMonthKey)

        val incomeTrend =
            Analytics.lastNMonthKeys(reflectMonthKey, 6).map { ym ->
                val r = monthReports[ym] ?: report(PeriodMode.MONTH, ym)
                TrendPoint(
                    key = ym,
                    label = r.periodLabel,
                    inflowMilli = r.inflowMilli,
                    outflowMilli = r.outflowMilli,
                    netMilli = r.netMilli,
                    count = r.count,
                )
            }
        val insight = Analytics.incomeVsSpendingInsight(incomeTrend)

        // Account balances — single pass over transactions (no N Room SUM flows).
        val bal = HashMap<String, Long>()
        val clearedBal = HashMap<String, Long>()
        for (t in snap.transactions) {
            bal[t.accountId] = (bal[t.accountId] ?: 0L) + t.amountMilli
            if (t.cleared == ClearedStatus.cleared || t.cleared == ClearedStatus.reconciled) {
                clearedBal[t.accountId] = (clearedBal[t.accountId] ?: 0L) + t.amountMilli
            }
        }
        val accountsWithBal =
            snap.accounts
                .filter { !it.closed }
                .sortedBy { it.name.lowercase() }
                .map { acct ->
                    AccountWithBalance(
                        account = acct,
                        balanceMilli = bal[acct.id] ?: 0L,
                        clearedBalanceMilli = clearedBal[acct.id] ?: 0L,
                    )
                }

        val open = accountsWithBal
        val onBudget = open.filter { it.account.onBudget }
        val tracking = open.filter { !it.account.onBudget }
        val inboxCount = snap.transactions.count { !it.approved }

        return LedgerAggregates(
            ready = true,
            computing = false,
            planId = planId,
            txnCount = snap.transactions.size,
            analyticsTxns = analyticsTxns,
            months = months,
            colorById = colorById,
            categoryNames = catNames,
            groupNames = groupNames,
            payeeNames = payeeNames,
            accountNames = accountNames,
            reflectMonthKey = reflectMonthKey,
            reflectReport = reflectReport,
            incomeTrend = incomeTrend,
            incomeInsight = insight,
            presetReports = presetReports,
            monthReports = monthReports,
            home =
                HomeAggregates(
                    onBudgetTotal = onBudget.sumOf { it.balanceMilli },
                    trackingTotal = tracking.sumOf { it.balanceMilli },
                    onBudgetCount = onBudget.size,
                    trackingCount = tracking.size,
                    inboxCount = inboxCount,
                ),
            accounts = accountsWithBal,
        )
    }

    /** Test-friendly entry that builds from domain pieces without Room entities. */
    fun buildFromAnalytics(
        planId: String,
        analyticsTxns: List<AnalyticsTxn>,
        categoryNames: Map<String, String> = emptyMap(),
        groupNames: Map<String, String> = emptyMap(),
        payeeNames: Map<String, String> = emptyMap(),
        accountNames: Map<String, String> = emptyMap(),
        colorById: Map<String, String> = emptyMap(),
        accounts: List<AccountEntity> = emptyList(),
        rawTxns: List<TransactionEntity> = emptyList(),
    ): LedgerAggregates {
        val months = Analytics.listMonths(analyticsTxns)
        val cur = Analytics.currentMonthKey()
        val reflectMonthKey =
            when {
                months.isEmpty() -> cur
                cur in months -> cur
                else -> months.first()
            }

        fun report(mode: PeriodMode, key: String) =
            Analytics.buildSpendingReport(
                transactions = analyticsTxns,
                mode = mode,
                periodKey = key,
                categoryNames = categoryNames,
                groupNames = groupNames,
                payeeNames = payeeNames,
                accountNames = accountNames,
                approvedOnly = true,
            )

        val monthKeys =
            (months + reflectMonthKey + Analytics.lastNMonthKeys(reflectMonthKey, 6))
                .toSet()
                .filter { it.isNotBlank() }
        val monthReports = monthKeys.associateWith { report(PeriodMode.MONTH, it) }
        val presetReports =
            PresetId.entries.associate { p -> p.key to report(PeriodMode.PRESET, p.key) }
        val reflectReport = monthReports[reflectMonthKey] ?: report(PeriodMode.MONTH, reflectMonthKey)
        val incomeTrend =
            Analytics.lastNMonthKeys(reflectMonthKey, 6).map { ym ->
                val r = monthReports[ym] ?: report(PeriodMode.MONTH, ym)
                TrendPoint(
                    key = ym,
                    label = r.periodLabel,
                    inflowMilli = r.inflowMilli,
                    outflowMilli = r.outflowMilli,
                    netMilli = r.netMilli,
                    count = r.count,
                )
            }

        val bal = HashMap<String, Long>()
        val clearedBal = HashMap<String, Long>()
        for (t in rawTxns) {
            bal[t.accountId] = (bal[t.accountId] ?: 0L) + t.amountMilli
            if (t.cleared == ClearedStatus.cleared || t.cleared == ClearedStatus.reconciled) {
                clearedBal[t.accountId] = (clearedBal[t.accountId] ?: 0L) + t.amountMilli
            }
        }
        val accountsWithBal =
            accounts.filter { !it.closed }.map { acct ->
                AccountWithBalance(
                    account = acct,
                    balanceMilli = bal[acct.id] ?: 0L,
                    clearedBalanceMilli = clearedBal[acct.id] ?: 0L,
                )
            }

        return LedgerAggregates(
            ready = true,
            computing = false,
            planId = planId,
            txnCount = analyticsTxns.size,
            analyticsTxns = analyticsTxns,
            months = months,
            colorById = colorById,
            categoryNames = categoryNames,
            groupNames = groupNames,
            payeeNames = payeeNames,
            accountNames = accountNames,
            reflectMonthKey = reflectMonthKey,
            reflectReport = reflectReport,
            incomeTrend = incomeTrend,
            incomeInsight = Analytics.incomeVsSpendingInsight(incomeTrend),
            presetReports = presetReports,
            monthReports = monthReports,
            home =
                HomeAggregates(
                    onBudgetTotal =
                        accountsWithBal.filter { it.account.onBudget }.sumOf { it.balanceMilli },
                    trackingTotal =
                        accountsWithBal.filter { !it.account.onBudget }.sumOf { it.balanceMilli },
                    onBudgetCount = accountsWithBal.count { it.account.onBudget },
                    trackingCount = accountsWithBal.count { !it.account.onBudget },
                    inboxCount = analyticsTxns.count { !it.approved },
                ),
            accounts = accountsWithBal,
        )
    }
}
