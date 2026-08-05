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
import com.cleaningbutton.r2finance.data.cloud.SyncCoordinator
import com.cleaningbutton.r2finance.data.repository.AccountWithBalance
import com.cleaningbutton.r2finance.domain.AccountType
import com.cleaningbutton.r2finance.domain.Money
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    container: AppContainer,
    onOpenAccount: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Stable plan id so Room Flow is subscribed on first frame (no null → empty flash).
    var planId by remember { mutableStateOf(SyncCoordinator.DEFAULT_PLAN_ID) }
    var showAdd by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    val sync = container.syncCoordinator
    val syncing by sync.isSyncing.collectAsStateWithLifecycle()
    val syncMessage by sync.statusMessage.collectAsStateWithLifecycle()

    // Local-first: Room is always the UI source. Hydrate from DDB only when empty.
    // Process-scoped SyncCoordinator survives navigate → register → back (no re-download).
    LaunchedEffect(Unit) {
        planId = container.ledger.ensureDefaultPlan().id
        sync.ensureHydrated(planId)
    }

    val accounts by remember(planId) {
        container.ledger.observeAccountsWithBalances(planId)
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    val openAccounts = accounts.filter { !it.account.closed }

    fun refreshFromCloud() {
        if (syncing) return
        scope.launch {
            sync.refresh(planId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("R2Finance") },
                actions = {
                    IconButton(
                        enabled = !syncing,
                        onClick = { refreshFromCloud() },
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
            // Full-screen spinner only on first hydrate when Room is empty.
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
                        text = syncMessage ?: "Loading accounts from cloud…",
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
                        text = "No accounts on this phone yet.",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Your ledger lives on this device (Room). " +
                            "Tap Sync to download accounts and transactions from the cloud once.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    val err = syncMessage
                    if (err != null) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    TextButton(
                        onClick = { refreshFromCloud() },
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        Text("Sync from cloud")
                    }
                }
            }
            else -> {
                Column(modifier = Modifier.padding(padding)) {
                    val msg = syncMessage
                    if (msg != null && (syncing || msg.startsWith("Sync"))) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
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
                        val pid = planId
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
