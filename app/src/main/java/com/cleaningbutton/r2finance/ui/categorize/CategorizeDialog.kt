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
 * Shared categorize picker: offline-first Room write (PENDING_PUSH).
 * ConnectivityMonitor / manual Sync uploads to DDB; YNAB is backend later.
 */
@Composable
fun CategorizeDialog(
    container: AppContainer,
    planId: String,
    target: TransactionRow,
    onDismiss: () -> Unit,
    onDone: (message: String?) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var categories by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    var groupNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

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
        title = { Text("Choose category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    buildString {
                        append(target.payeeName ?: "No payee")
                        append(" · ")
                        append(target.txn.date)
                        append(" · ")
                        append(Money.format(target.txn.amountMilli))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                target.categoryName?.let {
                    Text(
                        "Current: $it",
                        style = MaterialTheme.typography.labelMedium,
                    )
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
                                            // Room only — works in airplane mode for hours.
                                            container.ledger.setCategory(target.txn.id, cat.id)
                                            // Best-effort flush if already online (no UI wait on fail).
                                            if (container.connectivityMonitor.online.value) {
                                                runCatching {
                                                    container.syncCoordinator.syncWhenOnline(planId)
                                                }
                                            }
                                            busy = false
                                            onDone(
                                                if (container.connectivityMonitor.online.value) {
                                                    "Categorized · ${cat.name}"
                                                } else {
                                                    "Categorized · ${cat.name} · offline, uploads later"
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
