package com.cleaningbutton.r2finance.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleaningbutton.r2finance.data.AppContainer
import com.cleaningbutton.r2finance.data.repository.AccountWithBalance
import com.cleaningbutton.r2finance.domain.AccountType
import com.cleaningbutton.r2finance.domain.Money
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    container: AppContainer,
    onOpenAccount: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var planId by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var autoSynced by remember { mutableStateOf(false) }

    fun pullFromCloud(force: Boolean = false) {
        if (syncing) return
        scope.launch {
            syncing = true
            syncMessage = "Syncing from cloud…"
            runCatching {
                container.cloudSync.pullAll { step -> syncMessage = step }
            }.onSuccess { report ->
                planId = "default"
                syncMessage =
                    "Synced “${report.planName}”: ${report.accounts} accounts, " +
                        "${report.transactions} transactions"
            }.onFailure {
                syncMessage = "Sync failed: ${it.message}"
            }
            syncing = false
        }
    }

    LaunchedEffect(Unit) {
        val plan = container.ledger.ensureDefaultPlan()
        planId = plan.id
        // Auto-hydrate from R2FinanceAPI (DDB) when local is empty
        val existing = container.ledger.observeAccountsWithBalances(plan.id)
        // One-shot count via cloud if empty after a short observation is awkward —
        // just auto-pull once if we haven't this session.
        if (!autoSynced) {
            autoSynced = true
            pullFromCloud()
        }
    }

    val accounts by remember(planId) {
        planId?.let { container.ledger.observeAccountsWithBalances(it) } ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    // Prefer open non-closed; balances from txns
    val openAccounts = accounts.filter { !it.account.closed }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("R2Finance") },
                actions = {
                    IconButton(
                        enabled = !syncing,
                        onClick = { pullFromCloud(force = true) },
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Sync from cloud")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add account")
            }
        },
    ) { padding ->
        when {
            syncing && openAccounts.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text(
                        syncMessage ?: "Loading accounts from cloud…",
                        modifier = Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            openAccounts.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "No accounts on this phone yet.",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Your YNAB data is already in the cloud (R2Finance). " +
                            "Tap Sync to download accounts and transactions.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    syncMessage?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    TextButton(
                        onClick = { pullFromCloud(force = true) },
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null)
                        Text("  Sync from cloud", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
            else -> {
                Column(Modifier = Modifier.padding(padding)) {
                    syncMessage?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(openAccounts, key = { it.account.id }) { row ->
                            AccountRow(row = row, onClick = { onOpenAccount(row.account.id) })
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("New account") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pid = planId ?: return@TextButton
                        val name = newName.trim()
                        if (name.isEmpty()) return@TextButton
                        scope.launch {
                            container.ledger.createAccount(
                                planId = pid,
                                name = name,
                                type = AccountType.checking,
                            )
                            newName = ""
                            showAdd = false
                        }
                    },
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AccountRow(
    row: AccountWithBalance,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(row.account.name, fontWeight = FontWeight.SemiBold)
        },
        supportingContent = {
            Text(
                "${row.account.type.name} · " +
                    if (row.account.onBudget) "on budget" else "tracking",
            )
        },
        trailingContent = {
            Text(
                Money.format(row.balanceMilli),
                style = MaterialTheme.typography.titleMedium,
                color = if (row.balanceMilli < 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
    )
}
