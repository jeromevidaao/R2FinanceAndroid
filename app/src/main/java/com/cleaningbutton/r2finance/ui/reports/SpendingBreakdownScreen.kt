package com.cleaningbutton.r2finance.ui.reports

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleaningbutton.r2finance.data.AppContainer
import com.cleaningbutton.r2finance.data.cloud.SyncCoordinator
import com.cleaningbutton.r2finance.domain.Analytics
import com.cleaningbutton.r2finance.domain.CategoryColors
import com.cleaningbutton.r2finance.domain.Money
import com.cleaningbutton.r2finance.domain.PeriodMode
import com.cleaningbutton.r2finance.domain.PresetId
import kotlinx.coroutines.flow.combine

private enum class BreakdownView { MONTH, PRESETS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendingBreakdownScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    var planId by remember { mutableStateOf(SyncCoordinator.DEFAULT_PLAN_ID) }
    var view by remember { mutableStateOf(BreakdownView.MONTH) }
    var monthKey by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf(PresetId.LAST_3) }

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
    }.collectAsStateWithLifecycle(initialValue = LedgerSnap())

    val analyticsTxns =
        remember(ledgerState.transactions, ledgerState.categories) {
            val catGroup = ledgerState.categories.associate { it.id to it.categoryGroupId }
            ledgerState.transactions.map { t -> t.toAnalytics(catGroup[t.categoryId]) }
        }

    val months = remember(analyticsTxns) { Analytics.listMonths(analyticsTxns) }

    LaunchedEffect(months, analyticsTxns) {
        if (monthKey.isEmpty() || monthKey !in months) {
            val cur = Analytics.currentMonthKey()
            monthKey =
                when {
                    months.isEmpty() -> cur
                    cur in months -> cur
                    else -> months.first()
                }
        }
    }

    val colorById =
        remember(ledgerState.categories) {
            ledgerState.categories.mapNotNull { c ->
                val hex = c.color
                if (CategoryColors.isHex(hex)) c.id to hex!! else null
            }.toMap()
        }

    val mode = if (view == BreakdownView.MONTH) PeriodMode.MONTH else PeriodMode.PRESET
    val periodKey =
        if (view == BreakdownView.MONTH) {
            monthKey.ifEmpty { Analytics.currentMonthKey() }
        } else {
            preset.key
        }

    val report =
        remember(analyticsTxns, mode, periodKey, ledgerState) {
            Analytics.buildSpendingReport(
                transactions = analyticsTxns,
                mode = mode,
                periodKey = periodKey,
                categoryNames = ledgerState.categories.associate { it.id to it.name },
                groupNames = ledgerState.groups.associate { it.id to it.name },
                payeeNames = ledgerState.payees.associate { it.id to it.name },
                accountNames = ledgerState.accounts.associate { it.id to it.name },
            )
        }

    val stack =
        remember(report, colorById) {
            CategoryColors.buildStack(report.byCategory, colorById, topN = 8)
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spending Breakdown") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = view == BreakdownView.MONTH,
                        onClick = { view = BreakdownView.MONTH },
                        label = { Text("Month") },
                    )
                    FilterChip(
                        selected = view == BreakdownView.PRESETS,
                        onClick = { view = BreakdownView.PRESETS },
                        label = { Text("Presets") },
                    )
                }
            }

            if (view == BreakdownView.MONTH && months.isNotEmpty()) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        months.take(36).forEach { key ->
                            FilterChip(
                                selected = monthKey == key,
                                onClick = { monthKey = key },
                                label = {
                                    Text(
                                        Analytics.formatPeriodLabel(PeriodMode.MONTH, key),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            if (view == BreakdownView.PRESETS) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        PresetId.entries.forEach { p ->
                            FilterChip(
                                selected = preset == p,
                                onClick = { preset = p },
                                label = { Text(p.label) },
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            report.periodLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Total Spending",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            Money.format(report.outflowMilli),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        if (stack.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            StackedBar(segments = stack)
                        }
                    }
                }
            }

            item {
                Text(
                    "Categories",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (report.byCategory.isEmpty()) {
                item {
                    Text(
                        "No spending in this period.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(report.byCategory, key = { it.id }) { row ->
                    val hex = CategoryColors.colorHex(row.id, colorById, row.name)
                    CategoryRow(
                        name = row.name,
                        amountMilli = row.amountMilli,
                        colorHex = hex,
                        share = row.share,
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
