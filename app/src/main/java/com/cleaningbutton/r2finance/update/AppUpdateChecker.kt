package com.cleaningbutton.r2finance.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.cleaningbutton.r2finance.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

sealed class UpdateCheckResult {
    data object UpToDate : UpdateCheckResult()
    data class Available(val remote: RemoteAppVersion) : UpdateCheckResult()
    data class Failed(val message: String) : UpdateCheckResult()
}

/**
 * Self-hosted OTA (not Play Store): fetch [BuildConfig.UPDATE_MANIFEST_URL],
 * compare versionCode, download APK, install — same pattern as R2Android.
 */
class AppUpdateChecker(
    private val context: Context,
    private val client: OkHttpClient = defaultClient(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(BuildConfig.UPDATE_MANIFEST_URL)
                .header("Cache-Control", "no-cache")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@runCatching UpdateCheckResult.Failed("Update check HTTP ${resp.code}")
                }
                val body = resp.body?.string().orEmpty()
                val remote = json.decodeFromString(RemoteAppVersion.serializer(), body)
                if (remote.versionCode > BuildConfig.VERSION_CODE) {
                    UpdateCheckResult.Available(remote)
                } else {
                    UpdateCheckResult.UpToDate
                }
            }
        }.getOrElse {
            Log.w(TAG, "Update check failed: ${it.message}")
            UpdateCheckResult.Failed(it.message ?: "Update check failed")
        }
    }

    suspend fun downloadApk(
        remote: RemoteAppVersion,
        onProgress: (Float) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "R2Finance-update.apk")
            if (out.exists()) out.delete()

            val req = Request.Builder().url(remote.apkUrl).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Download HTTP ${resp.code}")
                val body = resp.body ?: error("Empty APK body")
                val total = body.contentLength().coerceAtLeast(0L)
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var readTotal = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            readTotal += n
                            if (total > 0) onProgress(readTotal.toFloat() / total.toFloat())
                        }
                        output.flush()
                    }
                }
            }
            if (out.length() < 10_000L) error("Downloaded APK looks too small (${out.length()} bytes)")
            onProgress(1f)
            out
        }
    }

    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun intentForUnknownSourcesSettings(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
    }

    fun installApk(file: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    companion object {
        private const val TAG = "R2FinanceOTA"

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
    }
}
