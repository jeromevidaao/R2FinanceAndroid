package com.cleaningbutton.r2finance.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.cleaningbutton.r2finance.BuildConfig
import com.cleaningbutton.r2finance.R
import com.cleaningbutton.r2finance.data.AppContainer
import com.cleaningbutton.r2finance.data.ynab.YnabImportReport
import com.cleaningbutton.r2finance.domain.Money
import com.cleaningbutton.r2finance.update.UpdateCheckResult
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(container: AppContainer) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var otaStatus by remember { mutableStateOf("Tap Check for updates") }
    var progress by remember { mutableFloatStateOf(-1f) }
    var busy by remember { mutableStateOf(false) }

    var tokenInput by remember {
        mutableStateOf(container.ynabTokenStore.getToken().orEmpty())
    }
    var tokenVisible by remember { mutableStateOf(false) }
    var tokenSaved by remember { mutableStateOf(container.ynabTokenStore.hasToken()) }
    var importStatus by remember {
        mutableStateOf(
            "Import pulls accounts, categories, payees, transactions from YNAB.",
        )
    }
    var lastReport by remember { mutableStateOf<YnabImportReport?>(null) }
    var importBusy by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_more)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("R2Finance", style = MaterialTheme.typography.headlineSmall)
            Text(
                "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium,
            )

            Text("YNAB import (Phase 2)", style = MaterialTheme.typography.titleMedium)
            Text(
                "How to get a token (YNAB only shows it ONCE):\n" +
                    "1. Open app.ynab.com/settings/developer on a laptop/browser\n" +
                    "2. Personal Access Tokens → New Token → enter YNAB password → Generate\n" +
                    "3. Copy the FULL token from the BANNER AT THE TOP " +
                    "(not the table row with XXXXXXXXXX-…)\n" +
                    "4. Paste here, tap the eye to verify, then Save + Import.\n" +
                    "If you only see redacted tokens, generate a new one and copy immediately.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = { Text("YNAB Personal Access Token") },
                singleLine = true,
                visualTransformation = if (tokenVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            imageVector = if (tokenVisible) {
                                Icons.Default.VisibilityOff
                            } else {
                                Icons.Default.Visibility
                            },
                            contentDescription = if (tokenVisible) "Hide token" else "Show token",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (tokenInput.isNotBlank()) {
                Text(
                    "Length ${tokenInput.trim().length} chars" +
                        if (tokenInput.contains("X", ignoreCase = false) &&
                            tokenInput.contains("XXXX")
                        ) {
                            " — looks redacted; paste the full one-time token from the top banner"
                        } else {
                            ""
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = {
                    container.ynabTokenStore.setToken(tokenInput)
                    tokenSaved = container.ynabTokenStore.hasToken()
                    importStatus = if (tokenSaved) {
                        "Token saved (${tokenInput.trim().length} chars)."
                    } else {
                        "Token cleared."
                    }
                },
            ) { Text(if (tokenSaved) "Update token" else "Save token") }
            Button(
                enabled = tokenInput.isNotBlank() && !importBusy,
                onClick = {
                    importBusy = true
                    importStatus = "Testing token…"
                    // Ensure latest field value is used for the request
                    container.ynabTokenStore.setToken(tokenInput)
                    tokenSaved = container.ynabTokenStore.hasToken()
                    scope.launch {
                        runCatching {
                            container.ynabClient.listPlans()
                        }.onSuccess { plans ->
                            importStatus =
                                "Token OK — ${plans.size} plan(s): " +
                                    plans.joinToString { it.name }
                        }.onFailure {
                            importStatus = "Token test failed: ${it.message}"
                        }
                        importBusy = false
                    }
                },
            ) { Text("Test token (list plans)") }
            if (tokenSaved) {
                TextButton(
                    onClick = {
                        container.ynabTokenStore.setToken(null)
                        tokenInput = ""
                        tokenSaved = false
                        importStatus = "Token cleared."
                    },
                ) { Text("Clear token") }
            }
            Button(
                enabled = tokenSaved && !importBusy,
                onClick = {
                    importBusy = true
                    importStatus = "Starting import…"
                    lastReport = null
                    scope.launch {
                        runCatching {
                            container.ynabImporter.importDefaultPlan { step ->
                                importStatus = step
                            }
                        }.onSuccess { report ->
                            lastReport = report
                            val mismatches = report.balanceAudit.count { !it.matches }
                            importStatus =
                                "Imported “${report.planName}”: " +
                                    "${report.accounts} accounts, ${report.categories} categories, " +
                                    "${report.payees} payees, ${report.transactions} txns" +
                                    if (mismatches > 0) " — $mismatches balance mismatch(es) (see below)"
                                    else " — balances match YNAB"
                        }.onFailure {
                            importStatus = "Import failed: ${it.message}"
                        }
                        importBusy = false
                    }
                },
            ) { Text(if (importBusy) "Importing…" else "Import from YNAB") }
            Text(importStatus, style = MaterialTheme.typography.bodyMedium)
            lastReport?.let { report ->
                Text(
                    "server_knowledge=${report.serverKnowledge} · " +
                        "groups=${report.categoryGroups} · scheduled=${report.scheduled} · " +
                        "subs=${report.subtransactions}",
                    style = MaterialTheme.typography.bodySmall,
                )
                report.balanceAudit.take(12).forEach { a ->
                    val mark = if (a.matches) "✓" else "≠"
                    Text(
                        "$mark ${a.name}: YNAB ${Money.format(a.ynabBalanceMilli)} · " +
                            "local ${Money.format(a.localBalanceMilli)}" +
                            if (!a.matches) " (Δ ${Money.format(a.deltaMilli)})" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (a.matches) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                if (report.balanceAudit.size > 12) {
                    Text("…and ${report.balanceAudit.size - 12} more accounts")
                }
            }

            Text("App updates (OTA)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Self-hosted, not Play Store — same pattern as R2Android.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(otaStatus, style = MaterialTheme.typography.bodyMedium)
            if (progress >= 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    progress = -1f
                    scope.launch {
                        when (val result = container.updateChecker.check()) {
                            is UpdateCheckResult.UpToDate -> {
                                otaStatus = "You're on the latest build."
                            }
                            is UpdateCheckResult.Failed -> {
                                otaStatus = "Update check: ${result.message}"
                            }
                            is UpdateCheckResult.Available -> {
                                otaStatus =
                                    "Update ${result.remote.versionName} (${result.remote.versionCode})…"
                                if (!container.updateChecker.canInstallPackages()) {
                                    otaStatus = "Allow install unknown apps, then try again."
                                    context.startActivity(
                                        container.updateChecker.intentForUnknownSourcesSettings(),
                                    )
                                    busy = false
                                    return@launch
                                }
                                progress = 0f
                                container.updateChecker.downloadApk(result.remote) {
                                    progress = it
                                }.onSuccess { file ->
                                    otaStatus = "Installing…"
                                    context.startActivity(container.updateChecker.installApk(file))
                                }.onFailure {
                                    otaStatus = "Download failed: ${it.message}"
                                }
                            }
                        }
                        busy = false
                        progress = -1f
                    }
                },
            ) { Text("Check for updates") }

            Text(
                "Phase 1 local ledger · Phase 2 YNAB import (this screen) · " +
                    "Phase 3 R2FinanceAPI dual-sync · Phase 4 cut YNAB cord.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
