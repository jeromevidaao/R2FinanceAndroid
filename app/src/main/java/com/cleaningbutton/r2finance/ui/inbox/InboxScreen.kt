package com.cleaningbutton.r2finance.ui.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleaningbutton.r2finance.R
import com.cleaningbutton.r2finance.data.AppContainer
import com.cleaningbutton.r2finance.data.cloud.SyncCoordinator
import com.cleaningbutton.r2finance.data.repository.TransactionRow
import com.cleaningbutton.r2finance.domain.Money
import com.cleaningbutton.r2finance.ui.categorize.CategorizeDialog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var planId by remember { mutableStateOf(SyncCoordinator.DEFAULT_PLAN_ID) }
    var categorizeTarget by remember { mutableStateOf<TransactionRow?>(null) }
    var inboxRefreshing by remember { mutableStateOf(false) }
    var refreshMessage by remember { mutableStateOf<String?>(null) }

    val sync = container.syncCoordinator
    val hydrating by sync.isSyncing.collectAsStateWithLifecycle()
    val syncMessage by sync.statusMessage.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        planId = container.ledger.ensureDefaultPlan().id
        sync.ensureHydrated(planId)
    }

    val items by remember(planId) {
        container.ledger.observeInboxRows(planId)
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    fun refreshInbox() {
        if (inboxRefreshing || hydrating) return
        scope.launch {
            inboxRefreshing = true
            refreshMessage = "Refreshing inbox…"
            runCatching {
                container.cloudSync.pullInbox { step -> refreshMessage = step }
            }.onSuccess { report ->
                refreshMessage =
                    "Inbox: ${report.inboxCount} needs attention " +
                        "(${report.transactions} rows loaded)"
            }.onFailure {
                refreshMessage = "Sync failed: ${it.message}"
            }
            inboxRefreshing = false
        }
    }

    val busy = inboxRefreshing || hydrating
    val banner = refreshMessage ?: syncMessage

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (items.isEmpty()) {
                            stringResource(R.string.nav_inbox)
                        } else {
                            "Inbox (${items.size})"
                        },
                    )
                },
                actions = {
                    IconButton(enabled = !busy, onClick = { refreshInbox() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Sync inbox")
                    }
                },
            )
        },
    ) { padding ->
        when {
            busy && items.isEmpty() -> {
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
                        banner ?: "Loading inbox…",
                        modifier = Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.empty_inbox),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "Inbox is computed from local Room (unapproved + uncategorized). " +
                            "Tap Sync to refresh from cloud / YNAB.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    banner?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    TextButton(onClick = { refreshInbox() }) {
                        Text("Sync inbox")
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    banner?.let { msg ->
                        item {
                            Text(
                                msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                    items(items, key = { it.txn.id }) { row ->
                        val txn = row.txn
                        val needsCat = txn.categoryId == null ||
                            row.categoryName.equals("Uncategorized", true)
                        ListItem(
                            headlineContent = {
                                Text(row.payeeName ?: "No payee")
                            },
                            supportingContent = {
                                Text(
                                    buildString {
                                        append(row.accountName ?: "Account")
                                        append(" · ")
                                        append(txn.date)
                                        append(" · ")
                                        append(Money.format(txn.amountMilli))
                                        when {
                                            !txn.approved -> append(" · needs approval")
                                            needsCat -> append(" · uncategorized")
                                            row.categoryName != null ->
                                                append(" · ${row.categoryName}")
                                        }
                                    },
                                )
                            },
                            trailingContent = {
                                Column {
                                    if (!txn.approved) {
                                        TextButton(
                                            onClick = {
                                                scope.launch {
                                                    container.ledger.approve(txn.id)
                                                    val id = txn.ynabId ?: txn.id
                                                    runCatching {
                                                        container.cloudApi.approve(id)
                                                    }.onFailure {
                                                        refreshMessage =
                                                            "Saved locally; cloud approve later: ${it.message}"
                                                    }
                                                }
                                            },
                                        ) { Text("Approve") }
                                    }
                                    if (txn.transferAccountId == null) {
                                        TextButton(onClick = { categorizeTarget = row }) {
                                            Text(if (needsCat) "Categorize" else "Edit cat")
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    val target = categorizeTarget
    if (target != null) {
        CategorizeDialog(
            container = container,
            planId = planId,
            target = target,
            onDismiss = { categorizeTarget = null },
            onDone = { msg -> refreshMessage = msg },
        )
    }
}
