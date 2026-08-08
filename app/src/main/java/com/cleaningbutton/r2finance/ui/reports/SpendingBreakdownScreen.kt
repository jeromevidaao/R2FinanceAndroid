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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleaningbutton.r2finance.data.AppContainer
import com.cleaningbutton.r2finance.data.aggregates.LedgerAggregatesStore
import com.cleaningbutton.r2finance.domain.Analytics
import com.cleaningbutton.r2finance.domain.CategoryColors
import com.cleaningbutton.r2finance.domain.Money
import com.cleaningbutton.r2finance.domain.PeriodMode
import com.cleaningbutton.r2finance.domain.PresetId
import com.cleaningbutton.r2finance.domain.SpendingReport

private enum class BreakdownView { MONTH, PRESETS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendingBreakdownScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    var view by remember { mutableStateOf(BreakdownView.MONTH) }
    var monthKey by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf(PresetId.LAST_3) }
    var lazyReport by remember { mutableStateOf<SpendingReport?>(null) }

    LaunchedEffect(Unit) {
        val planId = container.ledger.ensureDefaultPlan().id
        // RAM only — no per-screen cloud hydrate.
        container.aggregates.start(planId)
    }

    val agg by container.aggregates.state.collectAsStateWithLifecycle()

    LaunchedEffect(agg.months, agg.reflectMonthKey) {
        if (monthKey.isEmpty() || (agg.months.isNotEmpty() && monthKey !in agg.months)) {
            monthKey =
                when {
                    agg.reflectMonthKey.isNotEmpty() -> agg.reflectMonthKey
                    agg.months.isEmpty() -> Analytics.currentMonthKey()
                    else -> agg.months.first()
                }
        }
    }

    val mode = if (view == BreakdownView.MONTH) PeriodMode.MONTH else PeriodMode.PRESET
    val periodKey =
        if (view == BreakdownView.MONTH) {
            monthKey.ifEmpty { agg.reflectMonthKey.ifEmpty { Analytics.currentMonthKey() } }
        } else {
            preset.key
        }

    // Prefer in-memory cache; only hit Default for uncached periods.
    val cached = LedgerAggregatesStore.cachedReport(agg, mode, periodKey)
    LaunchedEffect(agg.ready, mode, periodKey, agg.txnCount) {
        if (cached != null) {
            lazyReport = cached
            return@LaunchedEffect
        }
        if (!agg.ready || agg.analyticsTxns.isEmpty()) {
            lazyReport = null
            return@LaunchedEffect
        }
        lazyReport = container.aggregates.report(mode, periodKey)
    }

    val report = cached ?: lazyReport
    val stack =
        if (report != null) {
            CategoryColors.buildStack(report.byCategory, agg.colorById, topN = 8)
        } else {
            emptyList()
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
        if (!agg.ready && report == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(
                    "Computing spending…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

            if (view == BreakdownView.MONTH && agg.months.isNotEmpty()) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        agg.months.take(36).forEach { key ->
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
                        if (report == null) {
                            CircularProgressIndicator(Modifier.padding(8.dp))
                        } else {
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
                                Money.formatSpend(report.outflowMilli),
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
            }

            item {
                Text(
                    "Categories",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (report == null) {
                item {
                    Text(
                        "Loading…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (report.byCategory.isEmpty()) {
                item {
                    Text(
                        "No spending in this period.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(report.byCategory, key = { it.id }) { row ->
                    val hex = CategoryColors.colorHex(row.id, agg.colorById, row.name)
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
