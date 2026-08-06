package com.cleaningbutton.r2finance.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleaningbutton.r2finance.data.AppContainer
import com.cleaningbutton.r2finance.data.cloud.SyncCoordinator
import com.cleaningbutton.r2finance.data.repository.AccountWithBalance
import com.cleaningbutton.r2finance.domain.AccountGroup
import com.cleaningbutton.r2finance.domain.AccountType
import com.cleaningbutton.r2finance.domain.Money
import com.cleaningbutton.r2finance.domain.accountGroup
import com.cleaningbutton.r2finance.domain.accountTypeLabel
import com.cleaningbutton.r2finance.domain.inferInstitution
import kotlinx.coroutines.launch

/** Positive balances — YNAB-style green for quick scan. */
private val BalancePositive = Color(0xFF3DCC91)
/** Mild red for debt / negative. */
private val BalanceNegative = Color(0xFFFF8A96)

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
    val pendingCount by sync.pendingCount.collectAsStateWithLifecycle()
    val online by container.connectivityMonitor.online.collectAsStateWithLifecycle()

    // Local-first: Room is always the UI source. Hydrate from DDB only when empty.
    // Process-scoped SyncCoordinator survives navigate → register → back (no re-download).
    LaunchedEffect(Unit) {
        planId = container.ledger.ensureDefaultPlan().id
        container.aggregates.start(planId)
        sync.ensureHydrated(planId)
    }

    // In-memory balances (single-pass) when ready; Room SUM flows as cold-start fallback.
    val agg by container.aggregates.state.collectAsStateWithLifecycle()
    val roomAccounts by remember(planId) {
        container.ledger.observeAccountsWithBalances(planId)
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    val accounts: List<AccountWithBalance> =
        if (agg.ready) agg.accounts else roomAccounts

    val openAccounts = remember(accounts) {
        accounts.filter { !it.account.closed }
    }

    val grouped = remember(openAccounts) {
        AccountGroup.entries.map { group ->
            val rows = openAccounts
                .filter {
                    accountGroup(it.account.type, it.account.onBudget) == group
                }
                .sortedBy { it.account.name.lowercase() }
            val total = rows.sumOf { it.balanceMilli }
            GroupSection(group = group, rows = rows, totalMilli = total)
        }.filter { it.rows.isNotEmpty() }
    }

    fun refreshFromCloud() {
        if (syncing) return
        scope.launch {
            sync.refresh(planId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accounts") },
                actions = {
                    IconButton(
                        enabled = !syncing,
                        onClick = { refreshFromCloud() },
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Sync from cloud")
                    }
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add account")
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
                    OfflineStatusBanner(
                        online = online,
                        pendingCount = pendingCount,
                        syncing = syncing,
                        syncMessage = syncMessage,
                    )
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        grouped.forEach { section ->
                            item(key = "hdr-${section.group.name}") {
                                GroupHeader(
                                    title = section.group.title,
                                    totalMilli = section.totalMilli,
                                )
                            }
                            items(section.rows, key = { it.account.id }) { row ->
                                AccountRow(
                                    row = row,
                                    onClick = { onOpenAccount(row.account.id) },
                                )
                            }
                            item(key = "sp-${section.group.name}") {
                                Spacer(Modifier.height(12.dp))
                            }
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

private data class GroupSection(
    val group: AccountGroup,
    val rows: List<AccountWithBalance>,
    val totalMilli: Long,
)

@Composable
private fun GroupHeader(
    title: String,
    totalMilli: Long,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.8.sp,
        )
        Text(
            text = Money.format(totalMilli),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = balanceColor(totalMilli),
        )
    }
}

@Composable
private fun OfflineStatusBanner(
    online: Boolean,
    pendingCount: Int,
    syncing: Boolean,
    syncMessage: String?,
) {
    val text = when {
        !online && pendingCount > 0 ->
            "Offline · $pendingCount change(s) saved on phone — will upload when online"
        !online -> "Offline · working from phone storage"
        pendingCount > 0 && !syncing ->
            "$pendingCount change(s) waiting to upload to cloud"
        syncing -> syncMessage ?: "Syncing…"
        syncMessage != null && (
            syncMessage.startsWith("Sync") ||
                syncMessage.startsWith("Offline") ||
                syncMessage.startsWith("Upload") ||
                syncMessage.startsWith("Will retry")
            ) -> syncMessage
        else -> null
    } ?: return
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (!online) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun AccountRow(
    row: AccountWithBalance,
    onClick: () -> Unit,
) {
    val brand = remember(row.account.name, row.account.type, row.account.onBudget) {
        inferInstitution(row.account.name, row.account.type, row.account.onBudget)
    }
    ListItem(
        leadingContent = {
            InstitutionIcon(
                mark = brand.mark,
                bg = Color(brand.bg),
                fg = Color(brand.fg),
            )
        },
        headlineContent = {
            Text(
                text = row.account.name,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(accountTypeLabel(row.account.type))
        },
        trailingContent = {
            Text(
                text = Money.format(row.balanceMilli),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = balanceColor(row.balanceMilli),
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
    )
}

@Composable
private fun InstitutionIcon(
    mark: String,
    bg: Color,
    fg: Color,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = mark,
            color = fg,
            fontWeight = FontWeight.Bold,
            fontSize = if (mark.length >= 3) 10.sp else 14.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun balanceColor(milli: Long): Color = when {
    milli > 0L -> BalancePositive
    milli < 0L -> BalanceNegative
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
