package com.cleaningbutton.r2finance.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleaningbutton.r2finance.R
import com.cleaningbutton.r2finance.data.AppContainer
import com.cleaningbutton.r2finance.domain.Money

/**
 * Home — paint-only from process-scoped [AppContainer.aggregates] + plan name.
 * No network, no ensureHydrated, no per-tab Room warm (done at process start).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenSpending: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenReports: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenMore: () -> Unit,
) {
    val planName by container.planName.collectAsStateWithLifecycle()
    val agg by container.aggregates.state.collectAsStateWithLifecycle()
    val home = agg.home

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.nav_home))
                        Text(
                            planName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatCard(
                    modifier = Modifier.fillMaxWidth(),
                    label = "Accounts",
                    // Keep last numbers while computing; only "…" before first ready.
                    value = if (agg.ready || agg.home.openAccountCount > 0 || agg.home.accountsTotal != 0L) {
                        Money.format(home.accountsTotal)
                    } else {
                        "…"
                    },
                    hint = if (agg.ready) {
                        "${home.openAccountCount} open"
                    } else if (home.openAccountCount > 0) {
                        "${home.openAccountCount} open"
                    } else {
                        "Computing…"
                    },
                )
            }

            if (home.inboxCount > 0) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "${home.inboxCount} to categorize or approve",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Select transactions, categorize in bulk, then approve.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Button(onClick = onOpenSpending) {
                                Text(stringResource(R.string.open_spending))
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "Quick links",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.nav_spending)) },
                            supportingContent = { Text("Categorize and approve") },
                            modifier = Modifier.clickable(onClick = onOpenSpending),
                        )
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.nav_account)) },
                            supportingContent = { Text("Balances and registers") },
                            modifier = Modifier.clickable(onClick = onOpenAccounts),
                        )
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.nav_report)) },
                            supportingContent = { Text("Spending insights (Reflect)") },
                            modifier = Modifier.clickable(onClick = onOpenReports),
                        )
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.nav_categories)) },
                            supportingContent = { Text("Category groups") },
                            modifier = Modifier.clickable(onClick = onOpenCategories),
                        )
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.nav_more)) },
                            supportingContent = { Text("Sync, OTA, plan details") },
                            modifier = Modifier.clickable(onClick = onOpenMore),
                        )
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = onOpenSpending,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.open_spending))
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    hint: String,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
