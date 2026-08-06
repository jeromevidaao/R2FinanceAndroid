package com.cleaningbutton.r2finance.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleaningbutton.r2finance.data.AppContainer
import com.cleaningbutton.r2finance.domain.CategoryColors
import com.cleaningbutton.r2finance.domain.Money
import com.cleaningbutton.r2finance.domain.TrendPoint

internal fun parseHexColor(hex: String): Color {
    val h = hex.removePrefix("#")
    val v = h.toLong(16)
    return Color(
        red = ((v shr 16) and 0xFF) / 255f,
        green = ((v shr 8) and 0xFF) / 255f,
        blue = (v and 0xFF) / 255f,
        alpha = 1f,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    container: AppContainer,
    onOpenSpendingBreakdown: () -> Unit,
) {
    LaunchedEffect(Unit) {
        val planId = container.ledger.ensureDefaultPlan().id
        container.aggregates.start(planId)
        container.syncCoordinator.ensureHydrated(planId)
    }

    // Precomputed on Default in LedgerAggregatesStore — no main-thread scan.
    val agg by container.aggregates.state.collectAsStateWithLifecycle()
    val report = agg.reflectReport
    val stack =
        if (report != null) {
            CategoryColors.buildStack(report.byCategory, agg.colorById, topN = 5)
        } else {
            emptyList()
        }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Reflect") }) },
    ) { padding ->
        when {
            !agg.ready && report == null -> {
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
            }
            agg.ready && agg.txnCount == 0 -> {
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
            }
            report != null -> {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        Text(
                            "How money was spent · income vs spending",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    item {
                        Card(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = onOpenSpendingBreakdown),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        ) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Spending Breakdown",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            report.periodLabel,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = "Open spending breakdown",
                                    )
                                }

                                Text(
                                    Money.format(report.outflowMilli),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    "Total spending",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                if (stack.isNotEmpty()) {
                                    StackedBar(segments = stack)
                                    stack.forEach { seg ->
                                        CategoryRow(
                                            name = seg.name,
                                            amountMilli = seg.amountMilli,
                                            colorHex = seg.colorHex,
                                        )
                                    }
                                } else {
                                    Text(
                                        "No spending in this period.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    "Income vs Spending",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    agg.incomeInsight,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    LegendDot(CategoryColors.INCOME, "Income")
                                    LegendDot(CategoryColors.SPENDING, "Spending")
                                }
                                IncomeSpendingChart(points = agg.incomeTrend)
                            }
                        }
                    }

                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
            else -> {
                // Defensive fallback
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
internal fun StackedBar(segments: List<CategoryColors.StackSegment>) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { seg ->
            val w = (seg.share.toFloat() * 100f).coerceAtLeast(0.5f)
            Box(
                Modifier
                    .weight(w)
                    .fillMaxHeight()
                    .background(parseHexColor(seg.colorHex)),
            )
        }
    }
}

@Composable
internal fun CategoryRow(
    name: String,
    amountMilli: Long,
    colorHex: String,
    share: Double? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(parseHexColor(colorHex)),
            )
            Text(
                name,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                Money.format(amountMilli),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (share != null && share > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 20.dp),
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(share.toFloat().coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(parseHexColor(colorHex)),
                    )
                }
                Text(
                    "${"%.0f".format(share * 100)}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LegendDot(hex: String, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(parseHexColor(hex)),
        )
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun IncomeSpendingChart(points: List<TrendPoint>) {
    if (points.isEmpty()) {
        Text("No activity.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val max =
        points
            .maxOf { maxOf(it.inflowMilli, kotlin.math.abs(it.outflowMilli)) }
            .coerceAtLeast(1L)
    val chartHeight = 120.dp

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        points.forEach { p ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(44.dp),
            ) {
                Row(
                    modifier =
                        Modifier
                            .height(chartHeight)
                            .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    val hIn =
                        (chartHeight.value * (p.inflowMilli.toFloat() / max))
                            .coerceIn(2f, chartHeight.value)
                    val hOut =
                        (chartHeight.value * (kotlin.math.abs(p.outflowMilli).toFloat() / max))
                            .coerceIn(2f, chartHeight.value)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(hIn.dp)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(parseHexColor(CategoryColors.INCOME)),
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .height(hOut.dp)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(parseHexColor(CategoryColors.SPENDING)),
                    )
                }
                Text(
                    if (p.key.length >= 7) p.key.takeLast(2) else p.key,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
    }
}
