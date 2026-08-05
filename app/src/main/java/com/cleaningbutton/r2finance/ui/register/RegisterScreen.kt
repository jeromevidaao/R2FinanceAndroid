package com.cleaningbutton.r2finance.ui.register

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleaningbutton.r2finance.R
import com.cleaningbutton.r2finance.data.AppContainer
import com.cleaningbutton.r2finance.data.local.entity.AccountEntity
import com.cleaningbutton.r2finance.data.repository.TransactionRow
import com.cleaningbutton.r2finance.domain.Money
import com.cleaningbutton.r2finance.ui.categorize.CategorizeDialog
import java.time.LocalDate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    container: AppContainer,
    accountId: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf<AccountEntity?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var payee by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    var categorizeTarget by remember { mutableStateOf<TransactionRow?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(accountId) {
        account = container.ledger.getAccount(accountId)
    }

    val planId = account?.planId
    val txns by remember(accountId, planId) {
        if (planId != null) {
            container.ledger.observeRegisterRows(accountId, planId)
        } else {
            flowOf(emptyList())
        }
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(account?.name ?: "Register") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add transaction")
            }
        },
    ) { padding ->
        if (txns.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.empty_register),
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
                statusMessage?.let { msg ->
                    item {
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                items(txns, key = { it.txn.id }) { row ->
                    TransactionRowItem(
                        row = row,
                        onCategorize = {
                            if (row.txn.transferAccountId == null) {
                                categorizeTarget = row
                            }
                        },
                    )
                }
            }
        }
    }

    val target = categorizeTarget
    val pid = planId
    if (target != null && pid != null) {
        CategorizeDialog(
            container = container,
            planId = pid,
            target = target,
            onDismiss = { categorizeTarget = null },
            onDone = { msg -> statusMessage = msg },
        )
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add transaction") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("Amount (e.g. -12.50 outflow)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = payee,
                        onValueChange = { payee = it },
                        label = { Text("Payee") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = memo,
                        onValueChange = { memo = it },
                        label = { Text("Memo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val acct = account ?: return@TextButton
                        val major = amountText.toDoubleOrNull() ?: return@TextButton
                        val milli = Money.fromMajorUnits(major)
                        scope.launch {
                            container.ledger.addTransaction(
                                planId = acct.planId,
                                accountId = acct.id,
                                date = LocalDate.now().toString(),
                                amountMilli = milli,
                                payeeName = payee.ifBlank { null },
                                categoryId = null,
                                memo = memo.ifBlank { null },
                                approved = true,
                            )
                            payee = ""
                            amountText = ""
                            memo = ""
                            showAdd = false
                        }
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TransactionRowItem(
    row: TransactionRow,
    onCategorize: () -> Unit,
) {
    val txn = row.txn
    val uncategorized = txn.categoryId == null && txn.transferAccountId == null
    ListItem(
        modifier = Modifier.clickable(
            enabled = txn.transferAccountId == null,
            onClick = onCategorize,
        ),
        headlineContent = {
            Text(row.payeeName ?: "No payee")
        },
        supportingContent = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(txn.date)
                    Text(
                        buildString {
                            append(if (txn.approved) txn.cleared.name else "unapproved")
                            when {
                                row.categoryName != null -> append(" · ${row.categoryName}")
                                uncategorized -> append(" · uncategorized · tap to set")
                                txn.transferAccountId != null -> append(" · transfer")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (uncategorized) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                txn.memo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        trailingContent = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Text(
                    Money.format(txn.amountMilli),
                    color = if (txn.amountMilli < 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                if (txn.transferAccountId == null) {
                    TextButton(onClick = onCategorize) {
                        Text(if (uncategorized) "Categorize" else "Edit")
                    }
                }
            }
        },
    )
}
