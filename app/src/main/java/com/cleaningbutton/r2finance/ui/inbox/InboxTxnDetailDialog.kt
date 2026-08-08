package com.cleaningbutton.r2finance.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cleaningbutton.r2finance.data.AppContainer
import com.cleaningbutton.r2finance.data.repository.TransactionRow
import com.cleaningbutton.r2finance.domain.GoogleMaps
import com.cleaningbutton.r2finance.domain.Money
import com.cleaningbutton.r2finance.domain.RelativeDate
import com.cleaningbutton.r2finance.ui.category.CategoryChip
import com.cleaningbutton.r2finance.ui.category.categoryChipForRow
import kotlinx.coroutines.launch

/**
 * Single-transaction detail: edit payee, amount, memo; approve or open categorize.
 */
@Composable
fun InboxTxnDetailDialog(
    container: AppContainer,
    planId: String,
    row: TransactionRow,
    onDismiss: () -> Unit,
    onMessage: (String?) -> Unit,
    onCategorize: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val txn = row.txn
    var payee by remember {
        // Prefer resolved display payee (Plaid CC payment form, etc.).
        mutableStateOf(row.payeeName.orEmpty())
    }
    val mapsUrl = remember(txn.id, payee) {
        GoogleMaps.urlForTxn(txn, payee.ifBlank { row.payeeName })
    }
    var amountText by remember {
        mutableStateOf(
            Money.toMajorDecimal(txn.amountMilli).stripTrailingZeros().toPlainString(),
        )
    }
    var memo by remember { mutableStateOf(txn.memo.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save(
        alsoApprove: Boolean = false,
        closeAfter: Boolean = true,
    ) {
        val milli = amountText.toDoubleOrNull()?.let { Money.fromMajorUnits(it) }
        if (milli == null) {
            error = "Enter a valid amount (e.g. -12.50)"
            return
        }
        scope.launch {
            busy = true
            error = null
            container.ledger.updateTransactionDetails(
                transactionId = txn.id,
                amountMilli = milli,
                memo = memo,
                clearMemo = memo.isBlank(),
                payeeName = payee,
                approved = if (alsoApprove) true else null,
            )
            // Local first, silent push only — never full cloud pull after edit.
            if (container.connectivityMonitor.online.value) {
                container.applicationScope.launch {
                    runCatching { container.syncCoordinator.pushPendingSilent(planId) }
                }
            }
            onMessage(
                when {
                    alsoApprove && !container.connectivityMonitor.online.value ->
                        "Saved + approved · offline"
                    alsoApprove -> "Saved + approved"
                    !container.connectivityMonitor.online.value ->
                        "Saved · offline"
                    else -> "Saved"
                },
            )
            busy = false
            if (closeAfter) onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    buildString {
                        append(row.accountName ?: "Account")
                        append(" · ")
                        append(RelativeDate.formatFriendly(txn.date))
                        append(" · ")
                        append(if (txn.approved) "Approved" else "Needs approval")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!txn.locationDisplay.isNullOrBlank() || mapsUrl != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        txn.locationDisplay?.takeIf { it.isNotBlank() }?.let { loc ->
                            Text(
                                "📍 $loc",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        if (mapsUrl != null) {
                            Text(
                                "Google Maps",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    GoogleMaps.open(context, mapsUrl)
                                },
                            )
                        }
                    }
                }
                CategoryChip(
                    model = categoryChipForRow(row, row.categoryGroupName),
                )
                OutlinedTextField(
                    value = payee,
                    onValueChange = { payee = it },
                    label = { Text("Payee") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount (negative = outflow)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = memo,
                    onValueChange = { memo = it },
                    label = { Text("Memo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { save(alsoApprove = false) },
                enabled = !busy,
            ) { Text("Save") }
        },
        dismissButton = {
            Column {
                if (txn.transferAccountId == null) {
                    TextButton(
                        onClick = {
                            // Save fields first so detail edits stick, then categorize.
                            val milli = amountText.toDoubleOrNull()?.let { Money.fromMajorUnits(it) }
                            if (milli == null) {
                                error = "Enter a valid amount first"
                                return@TextButton
                            }
                            scope.launch {
                                busy = true
                                container.ledger.updateTransactionDetails(
                                    transactionId = txn.id,
                                    amountMilli = milli,
                                    memo = memo,
                                    clearMemo = memo.isBlank(),
                                    payeeName = payee,
                                )
                                busy = false
                                onCategorize()
                            }
                        },
                        enabled = !busy,
                    ) { Text("Categorize") }
                }
                if (!txn.approved) {
                    TextButton(
                        onClick = { save(alsoApprove = true) },
                        enabled = !busy,
                    ) { Text("Approve") }
                }
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") }
            }
        },
    )
}
