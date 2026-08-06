package com.cleaningbutton.r2finance.ui.categorize

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.cleaningbutton.r2finance.data.AppContainer
import com.cleaningbutton.r2finance.data.local.entity.CategoryEntity
import com.cleaningbutton.r2finance.data.repository.TransactionRow
import com.cleaningbutton.r2finance.domain.Money
import kotlinx.coroutines.launch

/**
 * Category picker for one or many transactions (bulk).
 * Offline-first Room write (PENDING_PUSH). ConnectivityMonitor flushes later.
 */
@Composable
fun CategorizeDialog(
    container: AppContainer,
    planId: String,
    targets: List<TransactionRow>,
    onDismiss: () -> Unit,
    onDone: (message: String?) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var categories by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    var groupNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val single = targets.singleOrNull()
    val bulk = targets.size > 1

    LaunchedEffect(planId) {
        categories = container.ledger.listAssignableCategories(planId)
        val tree = container.ledger.listCategoryTree(planId)
        groupNames = tree.associate { it.group.id to it.group.name }
    }

    val needle = query.trim().lowercase()
    val filtered = remember(categories, groupNames, needle) {
        categories
            .filter { cat ->
                if (needle.isEmpty()) return@filter true
                val g = groupNames[cat.categoryGroupId].orEmpty()
                cat.name.lowercase().contains(needle) || g.lowercase().contains(needle)
            }
            .groupBy { it.categoryGroupId }
            .toList()
            .sortedBy { (gid, _) -> groupNames[gid] ?: "" }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                when {
                    bulk -> "Categorize ${targets.size} transactions"
                    single?.txn?.categoryId != null -> "Change category"
                    else -> "Categorize"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (bulk) {
                        val net = targets.sumOf { it.txn.amountMilli }
                        "${targets.size} selected · net ${Money.format(net)}"
                    } else {
                        buildString {
                            append(single?.payeeName ?: "No payee")
                            append(" · ")
                            append(single?.txn?.date.orEmpty())
                            append(" · ")
                            append(Money.format(single?.txn?.amountMilli ?: 0L))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!bulk) {
                    single?.categoryName?.let {
                        Text(
                            "Current: $it",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search categories") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(12.dp),
                    )
                } else if (categories.isEmpty()) {
                    Text(
                        "No categories yet. Sync from cloud so YNAB categories " +
                            "appear in R2Finance.",
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                    ) {
                        filtered.forEach { (groupId, cats) ->
                            item(key = "g-$groupId") {
                                Text(
                                    text = groupNames[groupId] ?: "Other",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(vertical = 6.dp),
                                )
                            }
                            items(cats, key = { it.id }) { cat ->
                                TextButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !busy,
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            error = null
                                            val ids = targets.map { it.txn.id }
                                            container.ledger.setCategoryMany(ids, cat.id)
                                            if (container.connectivityMonitor.online.value) {
                                                runCatching {
                                                    container.syncCoordinator.syncWhenOnline(planId)
                                                }
                                            }
                                            busy = false
                                            val offline =
                                                !container.connectivityMonitor.online.value
                                            onDone(
                                                when {
                                                    bulk && offline ->
                                                        "Categorized ${ids.size} · ${cat.name} · offline"
                                                    bulk ->
                                                        "Categorized ${ids.size} · ${cat.name}"
                                                    offline ->
                                                        "Categorized · ${cat.name} · offline, uploads later"
                                                    else ->
                                                        "Categorized · ${cat.name}"
                                                },
                                            )
                                            onDismiss()
                                        }
                                    },
                                ) { Text(cat.name) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") }
        },
    )
}

/** Single-target convenience wrapper (register, etc.). */
@Composable
fun CategorizeDialog(
    container: AppContainer,
    planId: String,
    target: TransactionRow,
    onDismiss: () -> Unit,
    onDone: (message: String?) -> Unit = {},
) {
    CategorizeDialog(
        container = container,
        planId = planId,
        targets = listOf(target),
        onDismiss = onDismiss,
        onDone = onDone,
    )
}
