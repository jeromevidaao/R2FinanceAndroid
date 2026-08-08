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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import com.cleaningbutton.r2finance.domain.Money
import com.cleaningbutton.r2finance.domain.RelativeDate
import com.cleaningbutton.r2finance.ui.categorize.CategorizeDialog
import com.cleaningbutton.r2finance.ui.category.CategoryChip
import com.cleaningbutton.r2finance.ui.category.groupInboxByCategory
import com.cleaningbutton.r2finance.ui.category.parseHexColor
import kotlinx.coroutines.launch

/**
 * Categorization list (needs-attention):
 * - unapproved + uncategorized, grouped by category for bulk approve
 * - vertical category color rail along each group
 * - multi-select → Approve / Categorize (local-first, silent background push)
 *
 * **Local-first / paint cache immediately:**
 * List comes from process-scoped [AppContainer.inboxCache] (Room → RAM).
 * Tab enter only re-binds the cache — no [SyncCoordinator.ensureHydrated]
 * (that runs once at process warmup).
 * **Pull-to-refresh / toolbar:** push pending + delta + inbox heal from R2Finance
 * while keeping the list painted.
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
    val pullState = rememberPullToRefreshState()

    val sync = container.syncCoordinator
    val items by container.inboxCache.rows.collectAsStateWithLifecycle()
    val inboxReady by container.inboxCache.ready.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        val plan = container.ledger.ensureDefaultPlan()
        planId = plan.id
        // Paint RAM/Room immediately (cache survives tab dispose/recreate).
        // Do not hydrate here — process warmup + reconnect already do that.
        container.inboxCache.start(plan.id)
    }

    LaunchedEffect(items) {
        val live = items.map { it.txn.id }.toSet()
        if (selectedIds.any { it !in live }) {
            selectedIds = selectedIds.intersect(live)
        }
    }

    fun refreshInbox() {
        if (inboxRefreshing) return
        scope.launch {
            inboxRefreshing = true
            refreshMessage = "Refreshing from R2Finance…"
            // Manual / pull-down: keep Room list painted; land latest cloud state.
            // 1) Push offline queue + delta (or full if due) + server tick
            val ledgerResult = sync.refresh(planId)
            ledgerResult.onFailure {
                refreshMessage = "Sync failed: ${it.message}"
            }
            // 2) Inbox heal so needs-attention matches /v1/inbox
            val inboxResult = runCatching {
                container.cloudSync.pullInbox { step -> refreshMessage = step }
            }
            inboxResult
                .onSuccess { report ->
                    val mode = ledgerResult.getOrNull()?.mode
                    val modeLabel = mode?.let { " · $it" }.orEmpty()
                    refreshMessage =
                        "${report.inboxCount} need attention" +
                            " (${report.transactions} loaded$modeLabel)"
                }
                .onFailure {
                    if (ledgerResult.isFailure) {
                        refreshMessage = "Sync failed: ${it.message}"
                    } else {
                        refreshMessage = "Inbox refresh failed: ${it.message}"
                    }
                }
            inboxRefreshing = false
        }
    }

    fun approveSelected() {
        if (selectedIds.isEmpty()) return
        val ids = selectedIds.toList()
        // Optimistic: clear selection immediately; Room drop + silent push.
        selectedIds = emptySet()
        refreshMessage =
            if (container.connectivityMonitor.online.value) {
                "Approved ${ids.size}"
            } else {
                "Approved ${ids.size} · offline"
            }
        container.applicationScope.launch {
            runCatching { container.ledger.approveMany(ids) }
            if (container.connectivityMonitor.online.value) {
                runCatching { container.syncCoordinator.pushPendingSilent(planId) }
            }
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

    // Prefer any cached rows immediately. "All clear" only after Room is ready and empty.
    val busy = inboxRefreshing
    val banner = refreshMessage
    val hasSelection = selectedIds.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (hasSelection) {
                                "${selectedIds.size} selected"
                            } else if (items.isNotEmpty()) {
                                stringResource(R.string.inbox_title_with_count, items.size)
                            } else {
                                stringResource(R.string.nav_inbox)
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
                            modifier = Modifier.padding(start = 8.dp),
                        ) { Text("Approve") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                categorizeTargets = selectedRows.filter {
                                    it.txn.transferAccountId == null
                                }
                            },
                            enabled = selectedRows.any { it.txn.transferAccountId == null },
                        ) { Text("Categorize") }
                    },
                )
            }
        },
    ) { padding ->
        // Pull down → same path as toolbar refresh (R2Finance latest).
        PullToRefreshBox(
            isRefreshing = inboxRefreshing,
            onRefresh = { refreshInbox() },
            state = pullState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                items.isNotEmpty() -> {
                    // Always paint Room/cache first (tab switch, mid-delta, mid-refresh).
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
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
                !inboxReady -> {
                    // First Room emit only — pull-to-refresh never blanks a ready empty list.
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Text(
                            banner ?: "Loading from this phone…",
                            modifier = Modifier.padding(top = 16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                else -> {
                    // Ready + empty — still pullable for "get latest from R2Finance".
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
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
                        Text(
                            "Pull down to refresh from R2Finance",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
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
                // Stay on this screen; list already drops rows via Room Flow.
                if (msg != null) refreshMessage = msg
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
                        append(RelativeDate.formatFriendly(txn.date))
                        append(" · ")
                        append(row.accountName ?: "Account")
                        append(" · ")
                        append(approvalStatusLabel(txn.approved))
                        val loc = txn.locationDisplay
                        if (!loc.isNullOrBlank()) {
                            append(" · ")
                            append(loc)
                        } else {
                            val pfc = txn.plaidPfc
                            if (!pfc.isNullOrBlank()) {
                                append(" · ")
                                append(pfc)
                            }
                        }
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

/** Status UI = YNAB `approved` (not bank cleared / uncleared / reconciled). */
private fun approvalStatusLabel(approved: Boolean): String =
    if (approved) "Approved" else "Needs approval"
