package com.cleaningbutton.r2finance.ui.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleaningbutton.r2finance.R
import com.cleaningbutton.r2finance.data.AppContainer
import com.cleaningbutton.r2finance.data.local.entity.CategoryEntity
import com.cleaningbutton.r2finance.data.repository.TransactionRow
import com.cleaningbutton.r2finance.domain.Money
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var planId by remember { mutableStateOf<String?>(null) }
    var categories by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    var categorizeTarget by remember { mutableStateOf<TransactionRow?>(null) }

    LaunchedEffect(Unit) {
        val plan = container.ledger.ensureDefaultPlan()
        planId = plan.id
        categories = container.ledger.listCategories(plan.id)
    }

    val items by remember(planId) {
        planId?.let { container.ledger.observeInboxRows(it) } ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_inbox)) }) },
    ) { padding ->
        if (items.isEmpty()) {
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
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(items, key = { it.txn.id }) { row ->
                    val txn = row.txn
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
                                    append(
                                        when {
                                            !txn.approved -> " · needs approval"
                                            txn.categoryId == null -> " · uncategorized"
                                            else -> ""
                                        },
                                    )
                                },
                            )
                        },
                        trailingContent = {
                            Column {
                                if (!txn.approved) {
                                    TextButton(
                                        onClick = {
                                            scope.launch { container.ledger.approve(txn.id) }
                                        },
                                    ) { Text("Approve") }
                                }
                                if (txn.categoryId == null) {
                                    TextButton(onClick = { categorizeTarget = row }) {
                                        Text("Categorize")
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    val target = categorizeTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { categorizeTarget = null },
            title = { Text("Choose category") },
            text = {
                if (categories.isEmpty()) {
                    Text("No categories yet. Create some under Categories, or import from YNAB in More.")
                } else {
                    LazyColumn {
                        items(categories, key = { it.id }) { cat ->
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        container.ledger.setCategory(target.txn.id, cat.id)
                                        if (!target.txn.approved) {
                                            container.ledger.approve(target.txn.id)
                                        }
                                        categorizeTarget = null
                                        planId?.let {
                                            categories = container.ledger.listCategories(it)
                                        }
                                    }
                                },
                            ) { Text(cat.name) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { categorizeTarget = null }) { Text("Close") }
            },
        )
    }
}
