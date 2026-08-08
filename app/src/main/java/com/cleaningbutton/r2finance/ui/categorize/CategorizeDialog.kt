package com.cleaningbutton.r2finance.ui.categorize

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cleaningbutton.r2finance.data.AppContainer
import com.cleaningbutton.r2finance.data.local.entity.CategoryEntity
import com.cleaningbutton.r2finance.data.repository.TransactionRow
import com.cleaningbutton.r2finance.domain.Money
import com.cleaningbutton.r2finance.domain.RelativeDate
import com.cleaningbutton.r2finance.ui.category.CategoryChip
import com.cleaningbutton.r2finance.ui.category.categoryChipForCategory
import com.cleaningbutton.r2finance.ui.category.categoryChipForRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Category picker for one or many transactions (bulk).
 *
 * Super-fast path:
 * 1. Room write (local) → inbox Flow drops the rows
 * 2. Close dialog → back on Categorization list (no navigation)
 * 3. Silent push PENDING_PUSH (no HTTP pull / no full list refresh)
 * Offline: ConnectivityMonitor flushes when the network returns.
 */
@Composable
fun CategorizeDialog(
    container: AppContainer,
    planId: String,
    targets: List<TransactionRow>,
    onDismiss: () -> Unit,
    onDone: (message: String?) -> Unit = {},
) {
    var categories by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    var groupNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var query by remember { mutableStateOf("") }

    val single = targets.singleOrNull()
    val bulk = targets.size > 1
    val locationLine = remember(targets) {
        if (!bulk) {
            single?.txn?.locationDisplay?.takeIf { it.isNotBlank() }
        } else {
            val locs = targets.mapNotNull { it.txn.locationDisplay?.takeIf { s -> s.isNotBlank() } }.distinct()
            when {
                locs.isEmpty() -> null
                locs.size <= 3 -> locs.joinToString(" · ")
                else -> locs.take(2).joinToString(" · ") + " +${locs.size - 2} more"
            }
        }
    }
    val pfcHint = if (!bulk) single?.txn?.plaidPfc?.takeIf { it.isNotBlank() } else null

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

    fun pickCategory(cat: CategoryEntity) {
        val ids = targets.map { it.txn.id }
        val offline = !container.connectivityMonitor.online.value
        val doneMsg =
            when {
                bulk && offline -> "Categorized ${ids.size} · offline"
                bulk -> "Categorized ${ids.size}"
                offline -> "Categorized · offline"
                else -> null
            }
        // Room first (ms) so the list drops rows, then dismiss + silent push.
        // Never call syncWhenOnline / pull — that was reloading the whole list.
        container.applicationScope.launch {
            runCatching { container.ledger.setCategoryMany(ids, cat.id) }
            withContext(Dispatchers.Main.immediate) {
                onDone(doneMsg)
                onDismiss()
            }
            if (container.connectivityMonitor.online.value) {
                runCatching { container.syncCoordinator.pushPendingSilent(planId) }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
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
                            append(RelativeDate.formatFriendly(single?.txn?.date))
                            append(" · ")
                            append(Money.format(single?.txn?.amountMilli ?: 0L))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (locationLine != null) {
                    Text(
                        buildString {
                            append("📍 ")
                            append(locationLine)
                            if (pfcHint != null) {
                                append(" · ")
                                append(pfcHint)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!bulk && single != null) {
                    CategoryChip(
                        model = categoryChipForRow(single, single.categoryGroupName),
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search categories") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (categories.isEmpty()) {
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
                                val gName = groupNames[cat.categoryGroupId]
                                TextButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { pickCategory(cat) },
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Start,
                                    ) {
                                        CategoryChip(
                                            model = categoryChipForCategory(cat.name, gName),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
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
