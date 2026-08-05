package com.cleaningbutton.r2finance.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Subscribes this install to FCM topic [R2FinanceMessagingService.TOPIC] for OTA pings.
 * No-ops when Firebase / google-services.json is missing.
 */
object PushRegistration {
    private const val TAG = "R2FinancePush"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(R2FinanceMessagingService.CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                R2FinanceMessagingService.CHANNEL_ID,
                "R2Finance updates",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "App update notifications"
            },
        )
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun subscribeAndRegister(context: Context) {
        ensureChannel(context)
        if (!isFirebaseAvailable(context)) {
            Log.i(TAG, "Firebase not available — skip FCM subscribe")
            return
        }
        scope.launch {
            runCatching {
                val token = FirebaseMessaging.getInstance().token.await()
                Log.i(TAG, "FCM token …${token.takeLast(8)}")
                FirebaseMessaging.getInstance()
                    .subscribeToTopic(R2FinanceMessagingService.TOPIC)
                    .await()
                Log.i(TAG, "Subscribed to topic ${R2FinanceMessagingService.TOPIC}")
            }.onFailure {
                Log.w(TAG, "FCM subscribe failed: ${it.message}")
            }
        }
    }

    private fun isFirebaseAvailable(context: Context): Boolean {
        return try {
            if (FirebaseApp.getApps(context).isEmpty()) false
            else {
                FirebaseMessaging.getInstance()
                true
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Firebase unavailable: ${t.message}")
            false
        }
    }
}
