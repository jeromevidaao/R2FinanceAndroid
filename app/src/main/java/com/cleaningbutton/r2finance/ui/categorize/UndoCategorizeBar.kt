package com.cleaningbutton.r2finance.ui.categorize

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleaningbutton.r2finance.data.AppContainer
import com.cleaningbutton.r2finance.data.PendingCategorizeQueue
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.max

/**
 * Bottom overlay after categorize: Undo latest, or List when multiple pending.
 */
@Composable
fun UndoCategorizeOverlay(container: AppContainer) {
    val pending by container.pendingCategorize.pending.collectAsStateWithLifecycle()
    val error by container.pendingCategorize.lastError.collectAsStateWithLifecycle()
    var showList by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(pending.isNotEmpty()) {
        if (pending.isEmpty()) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(400)
        }
    }

    LaunchedEffect(pending.size) {
        if (pending.size <= 1) showList = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (error != null) {
            Surface(
                tonalElevation = 4.dp,
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { container.pendingCategorize.clearError() }) {
                        Text("Dismiss")
                    }
                }
            }
        }

        val latest = pending.firstOrNull()
        if (latest != null) {
            Surface(
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.inverseSurface,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val secs = secondsLeft(latest, now)
                    val multi = pending.size > 1
                    Text(
                        text = if (multi) {
                            "${pending.size} pending · ${latest.label} · ${secs}s"
                        } else {
                            "Categorized · ${latest.label} · ${secs}s"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (multi) {
                        TextButton(onClick = { showList = true }) {
                            Text("List")
                        }
                    }
                    TextButton(onClick = { container.pendingCategorize.undoLatest() }) {
                        Text("Undo")
                    }
                }
            }
        }
    }

    if (showList && pending.size > 1) {
        PendingCategorizeListDialog(
            pending = pending,
            now = now,
            onUndo = { id -> container.pendingCategorize.undo(id) },
            onDismiss = { showList = false },
        )
    }
}

@Composable
private fun PendingCategorizeListDialog(
    pending: List<PendingCategorizeQueue.Entry>,
    now: Long,
    onUndo: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pending categorizes") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(pending, key = { it.id }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                buildString {
                                    append(
                                        if (entry.transactionIds.size == 1) {
                                            "1 transaction"
                                        } else {
                                            "${entry.transactionIds.size} transactions"
                                        },
                                    )
                                    append(" · saves in ")
                                    append(secondsLeft(entry, now))
                                    append("s")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onUndo(entry.id) }) {
                            Text("Undo")
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

private fun secondsLeft(entry: PendingCategorizeQueue.Entry, now: Long): Int {
    return max(0, ceil((entry.expiresAt - now) / 1000.0).toInt())
}
