package com.cleaningbutton.r2finance.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cleaningbutton.r2finance.MainActivity
import com.cleaningbutton.r2finance.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM for R2Finance — primarily [TYPE_APP_UPDATE] after OTA publish.
 * Data-only messages so [onMessageReceived] always runs.
 */
class R2FinanceMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "FCM token refreshed …${token.takeLast(8)}")
        PushRegistration.subscribeAndRegister(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val type = data["type"] ?: "generic"
        val title = message.notification?.title
            ?: data["title"]
            ?: "R2Finance"
        val body = message.notification?.body
            ?: data["body"]
            ?: when (type) {
                TYPE_APP_UPDATE -> "A new app version is ready to install."
                TYPE_LOGIN -> "Someone signed in to R2Finance."
                else -> "New notification"
            }

        Log.i(TAG, "FCM type=$type title=$title")
        showNotification(title, body, type, data)
    }

    private fun showNotification(
        title: String,
        body: String,
        type: String,
        data: Map<String, String>,
    ) {
        ensureChannel()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_FCM_TYPE, type)
            data["versionCode"]?.let { putExtra(EXTRA_VERSION_CODE, it) }
            data["versionName"]?.let { putExtra(EXTRA_VERSION_NAME, it) }
            data["apkUrl"]?.let { putExtra(EXTRA_APK_URL, it) }
            data["releaseNotes"]?.let { putExtra(EXTRA_RELEASE_NOTES, it) }
        }
        val pi = PendingIntent.getActivity(
            this,
            type.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setCategory(
                if (type == TYPE_LOGIN) NotificationCompat.CATEGORY_MESSAGE
                else NotificationCompat.CATEGORY_STATUS,
            )
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        // Stable-ish id for login so rapid re-login updates rather than floods
        val id =
            if (type == TYPE_LOGIN) {
                (data["email"] ?: type).hashCode()
            } else {
                (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
            }
        nm?.notify(id, n)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "R2Finance alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Sign-in alerts, app updates, and R2Finance notices"
            },
        )
    }

    companion object {
        private const val TAG = "R2FinanceFCM"
        const val CHANNEL_ID = "r2finance_updates"
        const val TYPE_APP_UPDATE = "app_update"
        const val TYPE_LOGIN = "login"
        const val TOPIC = "r2finance_updates"

        const val EXTRA_FCM_TYPE = "r2f_fcm_type"
        const val EXTRA_VERSION_CODE = "r2f_versionCode"
        const val EXTRA_VERSION_NAME = "r2f_versionName"
        const val EXTRA_APK_URL = "r2f_apkUrl"
        const val EXTRA_RELEASE_NOTES = "r2f_releaseNotes"
    }
}
