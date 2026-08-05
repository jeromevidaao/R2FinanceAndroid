package com.cleaningbutton.r2finance.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Tap Check for updates") }
    var progress by remember { mutableFloatStateOf(-1f) }
    var busy by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_more)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("R2Finance", style = MaterialTheme.typography.headlineSmall)
            Text(
                "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "OTA: self-hosted (not Play Store), same pattern as R2Android.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
            )
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
                                status = "You're on the latest build."
                            }
                            is UpdateCheckResult.Failed -> {
                                status = "Update check: ${result.message}"
                            }
                            is UpdateCheckResult.Available -> {
                                status =
                                    "Update ${result.remote.versionName} (${result.remote.versionCode}) available…"
                                if (!container.updateChecker.canInstallPackages()) {
                                    status = "Allow install unknown apps, then try again."
                                    context.startActivity(
                                        container.updateChecker.intentForUnknownSourcesSettings(),
                                    )
                                    busy = false
                                    return@launch
                                }
                                progress = 0f
                                val fileResult = container.updateChecker.downloadApk(result.remote) {
                                    progress = it
                                }
                                fileResult
                                    .onSuccess { file ->
                                        status = "Installing…"
                                        context.startActivity(
                                            container.updateChecker.installApk(file),
                                        )
                                    }
                                    .onFailure {
                                        status = "Download failed: ${it.message}"
                                    }
                            }
                        }
                        busy = false
                        progress = -1f
                    }
                },
            ) {
                Text(stringResource(R.string.update_install).let { "Check for updates" })
            }
            Text(
                "Phase 1: local Room ledger.\n" +
                    "Phase 2: YNAB migrate.\n" +
                    "Phase 3: R2FinanceAPI (Lambda + DDB) bidirectional sync.\n" +
                    "Phase 4: cut YNAB cord.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
