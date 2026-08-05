package com.cleaningbutton.r2finance.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cleaningbutton.r2finance.update.RemoteAppVersion

@Composable
fun AppUpdateDialog(
    remote: RemoteAppVersion,
    downloading: Boolean,
    progress: Float,
    statusMessage: String?,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {
            if (!downloading) onLater()
        },
        title = { Text("Update available") },
        text = {
            Column {
                Text(
                    text = "Version ${remote.versionName.ifBlank { remote.versionCode.toString() }} is ready.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (remote.releaseNotes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = remote.releaseNotes.take(400),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Your login and data stay on this phone after install.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                if (downloading) {
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) })
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (progress >= 1f) {
                            "Opening installer…"
                        } else {
                            "Downloading… ${(progress * 100).toInt()}%"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!statusMessage.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = statusMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onUpdate, enabled = !downloading) {
                Text(if (downloading) "Please wait…" else "Update")
            }
        },
        dismissButton = {
            TextButton(onClick = onLater, enabled = !downloading) {
                Text("Later")
            }
        },
    )
}
