package com.cleaningbutton.r2finance.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleaningbutton.r2finance.data.AppContainer
import com.cleaningbutton.r2finance.data.cloud.SyncCoordinator
import com.cleaningbutton.r2finance.data.local.entity.AccountEntity
import com.cleaningbutton.r2finance.data.local.entity.CategoryEntity
import com.cleaningbutton.r2finance.data.local.entity.CategoryGroupEntity
import com.cleaningbutton.r2finance.data.local.entity.PayeeEntity
import com.cleaningbutton.r2finance.data.local.entity.TransactionEntity
import com.cleaningbutton.r2finance.domain.Analytics
import com.cleaningbutton.r2finance.domain.AnalyticsTxn
import com.cleaningbutton.r2finance.domain.Money
import com.cleaningbutton.r2finance.domain.PeriodMode
import com.cleaningbutton.r2finance.domain.RankRow
import com.cleaningbutton.r2finance.domain.SpendingReport
import com.cleaningbutton.r2finance.domain.TrendPoint
import kotlinx.coroutines.flow.combine

private enum class ReportTab(val label: String) {
    Overview("Overview"),
    Categories("Categories"),
    Groups("Groups"),
    Payees("Payees"),
    Accounts("Accounts"),
    Trends("Trends"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(container: AppContainer) {
    var planId by remember { mutableStateOf(SyncCoordinator.DEFAULT_PLAN_ID) }
    var mode by remember { mutableStateOf(PeriodMode.MONTH) }
    var periodKey by remember { mutableStateOf("") }
    var tabIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        planId = container.ledger.ensureDefaultPlan().id
        container.syncCoordinator.ensureHydrated(planId)
    }

    val ledgerState by remember(planId) {
        combine(
            container.ledger.observePlanTransactions(planId),
            container.ledger.observeCategories(planId),
            container.ledger.observeCategoryGroups(planId),
            container.ledger.observePayees(planId),
            container.ledger.observeAccountsWithBalances(planId),
        ) { txns, cats, groups, payees, accts ->
            LedgerSnap(
                transactions = txns,
                categories = cats,
                groups = groups,
                payees = payees,
                accounts = accts.map { it.account },
            )
        }
    }.collectAsStateWithLifecycle(
        initialValue = LedgerSnap(),
    )

    val analyticsTxns =
        remember(ledgerState.transactions, ledgerState.categories) {
            val catGroup = ledgerState.categories.associate { it.id to it.categoryGroupId }
            ledgerState.transactions.map { t ->
                t.toAnalytics(catGroup[t.categoryId])
            }
        }

    val months = remember(analyticsTxns) { Analytics.listMonths(analyticsTxns) }
    val years = remember(analyticsTxns) { Analytics.listYears(analyticsTxns) }

    LaunchedEffect(mode, months, years, analyticsTxns) {
        periodKey =
            when (mode) {
                PeriodMode.ALL -> "all"
                PeriodMode.YEAR -> {
                    if (periodKey in years) periodKey
                    else Analytics.defaultPeriodKey(PeriodMode.YEAR, analyticsTxns)
                }
                PeriodMode.MONTH -> {
                    if (periodKey in months) periodKey
                    else Analytics.defaultPeriodKey(PeriodMode.MONTH, analyticsTxns)
                }
            }
    }

    val effectiveKey =
        remember(mode, periodKey, analyticsTxns) {
            when {
                mode == PeriodMode.ALL -> "all"
                periodKey.isNotEmpty() -> periodKey
                else -> Analytics.defaultPeriodKey(mode, analyticsTxns)
            }
        }

    val report: SpendingReport =
        remember(analyticsTxns, mode, effectiveKey, ledgerState) {
            Analytics.buildSpendingReport(
                transactions = analyticsTxns,
                mode = mode,
                periodKey = effectiveKey,
                categoryNames = ledgerState.categories.associate { it.id to it.name },
                groupNames = ledgerState.groups.associate { it.id to it.name },
                payeeNames = ledgerState.payees.associate { it.id to it.name },
                accountNames = ledgerState.accounts.associate { it.id to it.name },
            )
        }

    val selectedPeriod = if (periodKey.isNotEmpty()) periodKey else effectiveKey

    Scaffold(
        topBar = { TopAppBar(title = { Text("Reports") }) },
    ) { padding ->
        if (analyticsTxns.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "No transactions yet.\nSync from Accounts to load your ledger.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "YNAB-style spending analytics",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    PeriodMode.entries.forEach { m ->
                        FilterChip(
                            selected = mode == m,
                            onClick = { mode = m },
                            label = {
                                Text(
                                    when (m) {
                                        PeriodMode.MONTH -> "Month"
                                        PeriodMode.YEAR -> "Year"
                                        PeriodMode.ALL -> "All time"
                                    },
                                )
                            },
                        )
                    }
                }
            }

            if (mode == PeriodMode.MONTH && months.isNotEmpty()) {
                item {
                    PeriodChips(
                        options = months,
                        selected = selectedPeriod,
                        labelOf = { Analytics.formatPeriodLabel(PeriodMode.MONTH, it) },
                        onSelect = { periodKey = it },
                    )
                }
            }
            if (mode == PeriodMode.YEAR && years.isNotEmpty()) {
                item {
                    PeriodChips(
                        options = years,
                        selected = selectedPeriod,
                        labelOf = { it },
                        onSelect = { periodKey = it },
                    )
                }
            }

            item {
                Text(
                    report.periodLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            item {
                StatGrid(report)
            }

            item {
                ScrollableTabRow(
                    selectedTabIndex = tabIndex,
                    edgePadding = 0.dp,
                ) {
                    ReportTab.entries.forEachIndexed { i, t ->
                        Tab(
                            selected = tabIndex == i,
                            onClick = { tabIndex = i },
                            text = { Text(t.label) },
                        )
                    }
                }
            }

            when (ReportTab.entries[tabIndex]) {
                ReportTab.Overview -> {
                    item {
                        SectionCard(title = trendTitle(mode, selectedPeriod)) {
                            MiniTrendChart(
                                points = overviewTrend(report, mode, analyticsTxns, ledgerState),
                                showInflow = mode != PeriodMode.MONTH,
                            )
                        }
                    }
                    item {
                        SectionCard(title = "Top categories") {
                            RankList(report.byCategory.take(8), showShare = true)
                        }
                    }
                    item {
                        SectionCard(title = "Top payees") {
                            RankList(report.byPayee.take(8), showShare = true)
                        }
                    }
                }
                ReportTab.Categories -> {
                    item {
                        SectionCard(title = "Spending by category") {
                            RankList(report.byCategory, showShare = true)
                        }
                    }
                }
                ReportTab.Groups -> {
                    item {
                        SectionCard(title = "Spending by category group") {
                            RankList(report.byGroup, showShare = true)
                        }
                    }
                }
                ReportTab.Payees -> {
                    item {
                        SectionCard(title = "Spending by payee") {
                            RankList(report.byPayee, showShare = true)
                        }
                    }
                }
                ReportTab.Accounts -> {
                    item {
                        SectionCard(title = "Activity by account (net)") {
                            RankList(report.byAccount, showShare = false)
                        }
                    }
                }
                ReportTab.Trends -> {
                    item {
                        SectionCard(title = "Monthly income vs expense") {
                            MiniTrendChart(
                                points =
                                    when (mode) {
                                        PeriodMode.YEAR -> report.monthlyTrend
                                        PeriodMode.ALL -> report.monthlyTrend.takeLast(24)
                                        PeriodMode.MONTH ->
                                            overviewTrend(
                                                report,
                                                mode,
                                                analyticsTxns,
                                                ledgerState,
                                            )
                                    },
                                showInflow = true,
                            )
                        }
                    }
                    if (report.yearlyTrend.size > 1) {
                        item {
                            SectionCard(title = "Yearly totals") {
                                TrendTable(report.yearlyTrend.asReversed())
                            }
                        }
                    }
                    if (mode == PeriodMode.YEAR && report.monthlyTrend.isNotEmpty()) {
                        item {
                            SectionCard(title = "Month-by-month · $selectedPeriod") {
                                TrendTable(report.monthlyTrend.asReversed())
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private data class LedgerSnap(
    val transactions: List<TransactionEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val groups: List<CategoryGroupEntity> = emptyList(),
    val payees: List<PayeeEntity> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
)

private fun TransactionEntity.toAnalytics(groupId: String?): AnalyticsTxn =
    AnalyticsTxn(
        date = date,
        amountMilli = amountMilli,
        categoryId = categoryId,
        categoryGroupId = groupId,
        payeeId = payeeId,
        accountId = accountId,
        transferAccountId = transferAccountId,
    )

private fun trendTitle(mode: PeriodMode, periodKey: String): String =
    when (mode) {
        PeriodMode.ALL -> "Income vs expense (yearly / monthly)"
        PeriodMode.YEAR -> "Monthly spending · $periodKey"
        PeriodMode.MONTH -> "Recent months (outflow)"
    }

private fun overviewTrend(
    report: SpendingReport,
    mode: PeriodMode,
    analyticsTxns: List<AnalyticsTxn>,
    snap: LedgerSnap,
): List<TrendPoint> {
    return when (mode) {
        PeriodMode.YEAR -> report.monthlyTrend
        PeriodMode.ALL ->
            if (report.yearlyTrend.size > 1) report.yearlyTrend else report.monthlyTrend.takeLast(12)
        PeriodMode.MONTH -> {
            val months = Analytics.listMonths(analyticsTxns).asReversed()
            val idx = months.indexOf(report.periodKey)
            val end = if (idx >= 0) idx + 1 else months.size
            val slice = months.subList(maxOf(0, end - 6), end)
            slice.map { ym ->
                Analytics
                    .buildSpendingReport(
                        transactions = analyticsTxns,
                        mode = PeriodMode.MONTH,
                        periodKey = ym,
                        categoryNames = snap.categories.associate { it.id to it.name },
                        groupNames = snap.groups.associate { it.id to it.name },
                        payeeNames = snap.payees.associate { it.id to it.name },
                        accountNames = snap.accounts.associate { it.id to it.name },
                    ).let { r ->
                        TrendPoint(
                            key = ym,
                            label = r.periodLabel,
                            inflowMilli = r.inflowMilli,
                            outflowMilli = r.outflowMilli,
                            netMilli = r.netMilli,
                            count = r.count,
                        )
                    }
            }
        }
    }
}

@Composable
private fun PeriodChips(
    options: List<String>,
    selected: String,
    labelOf: (String) -> String,
    onSelect: (String) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        options.take(36).forEach { key ->
            FilterChip(
                selected = selected == key,
                onClick = { onSelect(key) },
                label = {
                    Text(
                        labelOf(key),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun StatGrid(report: SpendingReport) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("Inflow", Money.format(report.inflowMilli), Modifier.weight(1f), positive = true)
            StatCard("Outflow", Money.format(report.outflowMilli), Modifier.weight(1f), positive = false)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("Net", Money.format(report.netMilli), Modifier.weight(1f), positive = report.netMilli >= 0)
            val fourthLabel =
                if (report.mode == PeriodMode.MONTH) "Transactions" else "Avg monthly out"
            val fourthValue =
                if (report.mode == PeriodMode.MONTH) {
                    report.count.toString()
                } else {
                    Money.format(report.avgMonthlyOutflowMilli)
                }
            StatCard(fourthLabel, fourthValue, Modifier.weight(1f), positive = null)
        }
        if (report.mode != PeriodMode.MONTH) {
            Text(
                "${report.count} transactions · ${report.monthsCovered} months",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    positive: Boolean?,
) {
    Card(
        modifier = modifier,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color =
                    when (positive) {
                        true -> Color(0xFF1B7A57)
                        false -> Color(0xFFB3261E)
                        null -> MaterialTheme.colorScheme.onSurface
                    },
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun RankList(
    rows: List<RankRow>,
    showShare: Boolean,
) {
    if (rows.isEmpty()) {
        Text(
            "No spending in this period.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        row.name,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        Money.format(row.amountMilli),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color =
                            if (row.amountMilli < 0) {
                                Color(0xFFB3261E)
                            } else if (row.amountMilli > 0) {
                                Color(0xFF1B7A57)
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )
                }
                if (showShare && row.share > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LinearProgressIndicator(
                            progress = { row.share.toFloat().coerceIn(0f, 1f) },
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                            color = Color(0xFFB3261E),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Text(
                            "${"%.1f".format(row.share * 100)}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniTrendChart(
    points: List<TrendPoint>,
    showInflow: Boolean,
) {
    if (points.isEmpty()) {
        Text(
            "No activity.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val max =
        points
            .maxOf {
                if (showInflow) {
                    maxOf(it.inflowMilli, kotlin.math.abs(it.outflowMilli))
                } else {
                    kotlin.math.abs(it.outflowMilli)
                }
            }.coerceAtLeast(1L)
    val chartHeight = 110.dp

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        points.forEach { p ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(40.dp),
            ) {
                Row(
                    modifier =
                        Modifier
                            .height(chartHeight)
                            .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    if (showInflow) {
                        val hIn =
                            (chartHeight.value * (p.inflowMilli.toFloat() / max))
                                .coerceIn(2f, chartHeight.value)
                        Box(
                            Modifier
                                .weight(1f)
                                .height(hIn.dp)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(Color(0xFF1B7A57)),
                        )
                    }
                    val hOut =
                        (chartHeight.value * (kotlin.math.abs(p.outflowMilli).toFloat() / max))
                            .coerceIn(2f, chartHeight.value)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(hOut.dp)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(Color(0xFFB3261E)),
                    )
                }
                Text(
                    if (p.key.length == 7) p.key.takeLast(2) else p.key.takeLast(2),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TrendTable(points: List<TrendPoint>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("Period", Modifier.weight(1.2f), style = MaterialTheme.typography.labelSmall)
            Text("In", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            Text("Out", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            Text("Net", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
        }
        points.forEach { p ->
            Row(Modifier.fillMaxWidth()) {
                Text(
                    p.label,
                    Modifier.weight(1.2f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(Money.format(p.inflowMilli), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(Money.format(p.outflowMilli), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(Money.format(p.netMilli), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
