package com.cleaningbutton.r2finance

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.fragment.app.FragmentActivity
import com.cleaningbutton.r2finance.ui.UpdateGate
import com.cleaningbutton.r2finance.ui.login.AuthScreen
import com.cleaningbutton.r2finance.ui.login.BiometricGate
import com.cleaningbutton.r2finance.ui.navigation.AppNavHost
import com.cleaningbutton.r2finance.ui.theme.R2FinanceTheme

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as R2FinanceApplication
        val session = app.container.sessionStore

        setContent {
            R2FinanceTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var phase by remember {
                        mutableStateOf(
                            when {
                                !session.isSessionValid() -> "auth"
                                session.unlockedThisInstall -> "app"
                                session.biometricEnabled -> "bio"
                                else -> "app"
                            },
                        )
                    }

                    when (phase) {
                        "auth" -> AuthScreen(
                            authApi = app.container.authApi,
                            sessionStore = session,
                            onAuthenticated = { phase = "app" },
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
                                        session.unlockedThisInstall = true
                                        phase = "app"
                                    },
                                    onCancel = {
                                        session.clear()
                                        phase = "auth"
                                    },
                                    onFail = {
                                        session.unlockedThisInstall = true
                                        phase = "app"
                                    },
                                )
                            }
                        }
                        else -> UpdateGate(container = app.container) {
                            AppNavHost(container = app.container)
                        }
                    }
                }
            }
        }
    }
}
