package com.cleaningbutton.r2finance.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.cleaningbutton.r2finance.BuildConfig
import com.cleaningbutton.r2finance.R
import com.cleaningbutton.r2finance.data.AppContainer
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

            Text("Data source", style = MaterialTheme.typography.titleMedium)
            Text(
                "Day-to-day UI reads local Room on this phone. Cloud (DynamoDB via " +
                    "R2FinanceAPI) hydrates once when empty and when you tap Sync on Accounts. " +
                    "Navigating accounts does not re-download. YNAB sync, if any, runs only " +
                    "on the AWS backend — not in this app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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
                "Local Room ledger · cloud sync via R2FinanceAPI (DDB). " +
                    "No YNAB API in this app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
