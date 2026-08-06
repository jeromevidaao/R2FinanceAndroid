package com.cleaningbutton.r2finance.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleaningbutton.r2finance.R
import com.cleaningbutton.r2finance.data.AppContainer
import com.cleaningbutton.r2finance.data.cloud.SyncCoordinator
import com.cleaningbutton.r2finance.data.repository.TransactionRow
import com.cleaningbutton.r2finance.domain.ClearedStatus
import com.cleaningbutton.r2finance.domain.Money
import com.cleaningbutton.r2finance.ui.categorize.CategorizeDialog
import com.cleaningbutton.r2finance.ui.category.CategoryChip
import com.cleaningbutton.r2finance.ui.category.groupInboxByCategory
import com.cleaningbutton.r2finance.ui.category.parseHexColor
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * YNAB-style Spending / To approve:
 * - unapproved only, grouped by category for bulk approve
 * - vertical category color rail along each group
 * - multi-select → Approve / Categorize
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var planId by remember { mutableStateOf(SyncCoordinator.DEFAULT_PLAN_ID) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var categorizeTargets by remember { mutableStateOf<List<TransactionRow>?>(null) }
    var detailTarget by remember { mutableStateOf<TransactionRow?>(null) }
    var inboxRefreshing by remember { mutableStateOf(false) }
    var refreshMessage by remember { mutableStateOf<String?>(null) }
    var actionBusy by remember { mutableStateOf(false) }

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

    LaunchedEffect(items) {
        val live = items.map { it.txn.id }.toSet()
        if (selectedIds.any { it !in live }) {
            selectedIds = selectedIds.intersect(live)
        }
    }

    fun refreshInbox() {
        if (inboxRefreshing || hydrating) return
        scope.launch {
            inboxRefreshing = true
            refreshMessage = "Refreshing…"
            runCatching {
                container.cloudSync.pullInbox { step -> refreshMessage = step }
            }.onSuccess { report ->
                refreshMessage =
                    "${report.inboxCount} need attention (${report.transactions} loaded)"
            }.onFailure {
                refreshMessage = "Sync failed: ${it.message}"
            }
            inboxRefreshing = false
        }
    }

    fun bestEffortSync() {
        if (container.connectivityMonitor.online.value) {
            scope.launch {
                runCatching { container.syncCoordinator.syncWhenOnline(planId) }
            }
        }
    }

    fun approveSelected() {
        if (selectedIds.isEmpty() || actionBusy) return
        val ids = selectedIds.toList()
        scope.launch {
            actionBusy = true
            container.ledger.approveMany(ids)
            selectedIds = emptySet()
            refreshMessage =
                if (container.connectivityMonitor.online.value) {
                    "Approved ${ids.size}"
                } else {
                    "Approved ${ids.size} · offline, uploads later"
                }
            bestEffortSync()
            actionBusy = false
        }
    }

    fun toggleGroup(ids: List<String>) {
        val allSelected = ids.isNotEmpty() && ids.all { it in selectedIds }
        selectedIds = if (allSelected) {
            selectedIds - ids.toSet()
        } else {
            selectedIds + ids
        }
    }

    val selectedRows = remember(items, selectedIds) {
        items.filter { it.txn.id in selectedIds }
    }
    val selectedNet = remember(selectedRows) {
        selectedRows.sumOf { it.txn.amountMilli }
    }
    val categoryGroups = remember(items) { groupInboxByCategory(items) }

    val busy = inboxRefreshing || hydrating
    val banner = refreshMessage ?: syncMessage
    val hasSelection = selectedIds.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (hasSelection) {
                                "${selectedIds.size} selected"
                            } else if (items.isEmpty()) {
                                stringResource(R.string.nav_inbox)
                            } else {
                                stringResource(R.string.inbox_title_with_count, items.size)
                            },
                        )
                        if (hasSelection) {
                            Text(
                                "Net ${Money.format(selectedNet)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (items.isNotEmpty()) {
                            Text(
                                stringResource(R.string.inbox_subtitle),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (hasSelection) {
                        TextButton(
                            onClick = { selectedIds = emptySet() },
                            enabled = !actionBusy,
                        ) { Text("Clear") }
                    } else if (items.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                selectedIds = items.map { it.txn.id }.toSet()
                            },
                        ) { Text("Select all") }
                    }
                    IconButton(enabled = !busy, onClick = { refreshInbox() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Sync")
                    }
                },
            )
        },
        bottomBar = {
            if (hasSelection) {
                BottomAppBar(
                    actions = {
                        OutlinedButton(
                            onClick = { approveSelected() },
                            enabled = !actionBusy,
                            modifier = Modifier.padding(start = 8.dp),
                        ) { Text("Approve") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                categorizeTargets = selectedRows.filter {
                                    it.txn.transferAccountId == null
                                }
                            },
                            enabled = !actionBusy &&
                                selectedRows.any { it.txn.transferAccountId == null },
                        ) { Text("Categorize") }
                    },
                )
            }
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
                        banner ?: "Loading…",
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
                        stringResource(R.string.inbox_empty_hint),
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
                        Text("Sync")
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(
                        top = 8.dp,
                        bottom = if (hasSelection) 8.dp else 24.dp,
                    ),
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
                    categoryGroups.forEach { group ->
                        val groupIds = group.rows.map { it.txn.id }
                        val selectedInGroup = groupIds.count { it in selectedIds }
                        val allGroupSelected =
                            groupIds.isNotEmpty() && selectedInGroup == groupIds.size
                        item(key = "h-${group.key}") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .width(10.dp)
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(parseHexColor(group.railColorHex)),
                                )
                                CategoryChip(model = group.chip)
                                Text(
                                    "${group.rows.size}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { toggleGroup(groupIds) }) {
                                    Text(if (allGroupSelected) "Deselect" else "Select")
                                }
                            }
                        }
                        itemsIndexed(
                            group.rows,
                            key = { _, row -> row.txn.id },
                        ) { idx, row ->
                            InboxTxnRow(
                                row = row,
                                selected = row.txn.id in selectedIds,
                                railColorHex = group.railColorHex,
                                isGroupFirst = idx == 0,
                                isGroupLast = idx == group.rows.lastIndex,
                                onToggleSelect = {
                                    selectedIds =
                                        if (row.txn.id in selectedIds) {
                                            selectedIds - row.txn.id
                                        } else {
                                            selectedIds + row.txn.id
                                        }
                                },
                                onOpenDetail = { detailTarget = row },
                            )
                        }
                    }
                }
            }
        }
    }

    val catTargets = categorizeTargets
    if (!catTargets.isNullOrEmpty()) {
        CategorizeDialog(
            container = container,
            planId = planId,
            targets = catTargets,
            onDismiss = { categorizeTargets = null },
            onDone = { msg ->
                refreshMessage = msg
                selectedIds = emptySet()
            },
        )
    }

    val detail = detailTarget
    if (detail != null) {
        InboxTxnDetailDialog(
            container = container,
            planId = planId,
            row = detail,
            onDismiss = { detailTarget = null },
            onMessage = { refreshMessage = it },
            onCategorize = {
                detailTarget = null
                categorizeTargets = listOf(detail)
            },
        )
    }
}

@Composable
private fun InboxTxnRow(
    row: TransactionRow,
    selected: Boolean,
    railColorHex: String,
    isGroupFirst: Boolean,
    isGroupLast: Boolean,
    onToggleSelect: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    val txn = row.txn
    val amountColor = if (txn.amountMilli < 0) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val railColor = parseHexColor(railColorHex)
    val railTop = if (isGroupFirst) 8.dp else 0.dp
    val railBottom = if (isGroupLast) 8.dp else 0.dp
    val railShape = when {
        isGroupFirst && isGroupLast -> RoundedCornerShape(3.dp)
        isGroupFirst -> RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
        isGroupLast -> RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp)
        else -> RoundedCornerShape(0.dp)
    }

    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenDetail)
                .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(12.dp)
                    .height(56.dp)
                    .padding(top = railTop, bottom = railBottom, end = 8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .align(Alignment.CenterStart)
                        .clip(railShape)
                        .background(railColor),
                )
            }
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelect() },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    row.payeeName ?: txn.importPayeeName ?: "No payee",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    buildString {
                        append(formatInboxDateShort(txn.date))
                        append(" · ")
                        append(row.accountName ?: "Account")
                        append(" · ")
                        append(clearedLabel(txn.cleared, txn.approved))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                txn.memo?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                Money.format(txn.amountMilli),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = amountColor,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
}

private fun clearedLabel(cleared: ClearedStatus, approved: Boolean): String {
    if (!approved) return "uncleared"
    return when (cleared) {
        ClearedStatus.reconciled -> "reconciled"
        ClearedStatus.cleared -> "cleared"
        ClearedStatus.uncleared -> "uncleared"
    }
}

private fun formatInboxDateShort(iso: String): String {
    return runCatching {
        val d = LocalDate.parse(iso)
        val fmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
        d.format(fmt)
    }.getOrDefault(iso)
}
