package com.cleaningbutton.r2finance.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import com.cleaningbutton.r2finance.data.AppContainer
import com.cleaningbutton.r2finance.update.RemoteAppVersion
import com.cleaningbutton.r2finance.update.UpdateCheckResult
import kotlinx.coroutines.launch

/**
 * Silent OTA check after login. Shows [AppUpdateDialog] when a newer versionCode is published.
 */
@Composable
fun UpdateGate(
    container: AppContainer,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var remote by remember { mutableStateOf<RemoteAppVersion?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        when (val result = container.updateChecker.check()) {
            is UpdateCheckResult.Available -> remote = result.remote
            is UpdateCheckResult.UpToDate, is UpdateCheckResult.Failed -> Unit
        }
    }

    content()

    val r = remote
    if (r != null) {
        AppUpdateDialog(
            remote = r,
            downloading = downloading,
            progress = progress,
            statusMessage = status,
            onLater = { remote = null },
            onUpdate = {
                scope.launch {
                    status = null
                    if (!container.updateChecker.canInstallPackages()) {
                        status = "Allow R2Finance to install apps, then try again."
                        (context as? FragmentActivity)?.startActivity(
                            container.updateChecker.intentForUnknownSourcesSettings(),
                        )
                        return@launch
                    }
                    downloading = true
                    progress = 0f
                    container.updateChecker.downloadApk(r) { progress = it }
                        .onSuccess { file ->
                            progress = 1f
                            context.startActivity(container.updateChecker.installApk(file))
                            remote = null
                        }
                        .onFailure {
                            status = it.message ?: "Download failed"
                            Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                        }
                    downloading = false
                }
            },
        )
    }
}
