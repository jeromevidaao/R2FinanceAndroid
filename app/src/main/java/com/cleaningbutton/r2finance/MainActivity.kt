package com.cleaningbutton.r2finance

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.cleaningbutton.r2finance.push.PushRegistration
import com.cleaningbutton.r2finance.push.R2FinanceMessagingService
import com.cleaningbutton.r2finance.ui.UpdateGate
import com.cleaningbutton.r2finance.ui.login.AuthScreen
import com.cleaningbutton.r2finance.ui.login.BiometricGate
import com.cleaningbutton.r2finance.ui.navigation.AppNavHost
import com.cleaningbutton.r2finance.ui.theme.R2FinanceTheme
import com.cleaningbutton.r2finance.update.RemoteAppVersion

class MainActivity : FragmentActivity() {

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            PushRegistration.subscribeAndRegister(this)
        }
    }

    private var pendingUpdateFromPush: RemoteAppVersion? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as R2FinanceApplication
        val session = app.container.sessionStore

        pendingUpdateFromPush = remoteFromIntent(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val ok = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!ok) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                PushRegistration.subscribeAndRegister(this)
            }
        } else {
            PushRegistration.subscribeAndRegister(this)
        }

        setContent {
            R2FinanceTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // resolveStartPhase: keeps login across OTA; skips bio after update
                    var phase by remember { mutableStateOf(session.resolveStartPhase()) }
                    var forceUpdate by remember { mutableStateOf(pendingUpdateFromPush) }

                    when (phase) {
                        "auth" -> AuthScreen(
                            authApi = app.container.authApi,
                            sessionStore = session,
                            onAuthenticated = {
                                session.markUnlocked()
                                PushRegistration.subscribeAndRegister(this@MainActivity)
                                app.container.warmAuthenticatedCaches()
                                phase = "app"
                            },
                        )
                        "bio" -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                            LaunchedEffect(Unit) {
                                BiometricGate.prompt(
                                    activity = this@MainActivity,
                                    onSuccess = {
                                        session.markUnlocked()
                                        app.container.warmAuthenticatedCaches()
                                        phase = "app"
                                    },
                                    onCancel = {
                                        // Stay on bio — do NOT clear session (would force full re-login)
                                        // User can try again by reopening app
                                        finish()
                                    },
                                    onFail = {
                                        // Soft fail → enter app with valid session
                                        session.markUnlocked()
                                        app.container.warmAuthenticatedCaches()
                                        phase = "app"
                                    },
                                )
                            }
                        }
                        else -> {
                            // Already unlocked this process / post-OTA skip — warm connectors once.
                            LaunchedEffect(Unit) {
                                app.container.warmAuthenticatedCaches()
                            }
                            UpdateGate(
                                container = app.container,
                                forceRemote = forceUpdate,
                                onForceConsumed = { forceUpdate = null },
                            ) {
                                AppNavHost(container = app.container)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun remoteFromIntent(intent: android.content.Intent?): RemoteAppVersion? {
        if (intent == null) return null
        val type = intent.getStringExtra(R2FinanceMessagingService.EXTRA_FCM_TYPE) ?: return null
        if (type != R2FinanceMessagingService.TYPE_APP_UPDATE) return null
        val apkUrl = intent.getStringExtra(R2FinanceMessagingService.EXTRA_APK_URL) ?: return null
        val vc = intent.getStringExtra(R2FinanceMessagingService.EXTRA_VERSION_CODE)?.toIntOrNull()
            ?: return null
        return RemoteAppVersion(
            versionCode = vc,
            versionName = intent.getStringExtra(R2FinanceMessagingService.EXTRA_VERSION_NAME).orEmpty(),
            apkUrl = apkUrl,
            releaseNotes = intent.getStringExtra(R2FinanceMessagingService.EXTRA_RELEASE_NOTES).orEmpty(),
        )
    }
}
