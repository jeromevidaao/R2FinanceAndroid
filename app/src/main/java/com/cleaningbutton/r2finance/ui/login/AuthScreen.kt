package com.cleaningbutton.r2finance.ui.login

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cleaningbutton.r2finance.BuildConfig
import com.cleaningbutton.r2finance.data.auth.AuthApi
import com.cleaningbutton.r2finance.data.auth.AuthException
import com.cleaningbutton.r2finance.data.auth.SessionStore
import kotlinx.coroutines.launch

private enum class Step {
    Loading,
    SetPassword,
    Login,
    ForgotPassword,
    MfaSetup,
    MfaVerify,
    Done,
}

private const val WEBSITE_HOME = "https://finance.i-liquid.be"

@Composable
fun AuthScreen(
    authApi: AuthApi,
    sessionStore: SessionStore,
    onAuthenticated: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val email = BuildConfig.DEFAULT_USER_EMAIL

    var step by remember { mutableStateOf(Step.Loading) }
    var password by remember { mutableStateOf("") }
    var password2 by remember { mutableStateOf("") }
    var totpCode by remember { mutableStateOf("") }
    var mfaSecret by remember { mutableStateOf<String?>(null) }
    var mfaToken by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var statusLine by remember { mutableStateOf("Checking account…") }
    var forgotMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching {
            authApi.bootstrap()
            val st = authApi.status(email)
            step = when {
                st.mustSetPassword -> Step.SetPassword
                !st.mfaEnabled -> Step.Login // password exists but MFA not on — login then setup
                else -> Step.Login
            }
            statusLine = when (step) {
                Step.SetPassword -> "First launch — create your password"
                else -> "Sign in"
            }
        }.onFailure {
            error = it.message ?: "Cannot reach R2FinanceAPI"
            step = Step.Login
        }
    }

    fun saveSession(token: String?, exp: Long?, mail: String?) {
        if (token.isNullOrBlank() || exp == null) return
        sessionStore.saveSession(token, mail ?: email, exp)
        step = Step.Done
        onAuthenticated()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("R2Finance", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(email, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Text(statusLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))

        if (step == Step.Loading) {
            CircularProgressIndicator()
            return@Column
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }

        when (step) {
            Step.SetPassword -> {
                Text(
                    "Choose a strong password (10+ characters). You’ll set up Google Authenticator next.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("New password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password2,
                    onValueChange = { password2 = it },
                    label = { Text("Confirm password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = !busy,
                    onClick = {
                        error = null
                        if (password != password2) {
                            error = "Passwords do not match"
                            return@Button
                        }
                        if (password.length < 10) {
                            error = "Use at least 10 characters"
                            return@Button
                        }
                        busy = true
                        scope.launch {
                            runCatching {
                                authApi.setPassword(email, password)
                                val setup = authApi.mfaSetup(email, password)
                                mfaSecret = setup.secret
                                step = Step.MfaSetup
                                statusLine = "Set up 2FA (authenticator app)"
                            }.onFailure { error = it.message }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Saving…" else "Save password & continue") }
            }

            Step.Login -> {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = !busy && password.isNotBlank(),
                    onClick = {
                        error = null
                        busy = true
                        scope.launch {
                            runCatching {
                                val res = authApi.login(email, password)
                                when {
                                    res.ok && !res.token.isNullOrBlank() ->
                                        saveSession(res.token, res.expiresAt, res.email)
                                    res.next == "set_password" -> {
                                        step = Step.SetPassword
                                        statusLine = "Create your password"
                                    }
                                    res.next == "mfa_setup" -> {
                                        val setup = authApi.mfaSetup(email, password)
                                        mfaSecret = setup.secret
                                        step = Step.MfaSetup
                                        statusLine = "Set up 2FA"
                                    }
                                    res.next == "mfa_verify" -> {
                                        mfaToken = res.mfaToken
                                        step = Step.MfaVerify
                                        statusLine = "Enter authenticator code"
                                    }
                                    else -> error = res.error ?: "Login failed"
                                }
                            }.onFailure { error = it.message }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Signing in…" else "Continue") }
                TextButton(
                    enabled = !busy,
                    onClick = {
                        error = null
                        forgotMessage = null
                        step = Step.ForgotPassword
                        statusLine = "Reset password via email"
                    },
                ) { Text("Forgot password?") }
            }

            Step.ForgotPassword -> {
                Text(
                    "We’ll email a one-time link to $email. Open it on the R2Finance website (finance.i-liquid.be) to choose a new password, then return here to sign in.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                forgotMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                }
                Button(
                    enabled = !busy,
                    onClick = {
                        error = null
                        forgotMessage = null
                        busy = true
                        scope.launch {
                            runCatching {
                                val res = authApi.forgotPassword(email)
                                if (res.error != null) throw AuthException(res.error)
                                forgotMessage = res.message
                                    ?: "Check your email for a reset link to the R2Finance website."
                                statusLine = "Email sent — open the website link"
                            }.onFailure { error = it.message }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Sending…" else "Email me a reset link") }
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE_HOME))
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open R2Finance website") }
                TextButton(
                    onClick = {
                        error = null
                        forgotMessage = null
                        step = Step.Login
                        statusLine = "Sign in"
                    },
                ) { Text("Back to sign in") }
            }

            Step.MfaSetup -> {
                Text(
                    "Add this account in Google Authenticator / Authy. Copy the secret if you can’t scan a QR.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    mfaSecret.orEmpty(),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = {
                        mfaSecret?.let { clipboard.setText(AnnotatedString(it)) }
                    },
                ) { Text("Copy secret") }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = totpCode,
                    onValueChange = { totpCode = it.filter { ch -> ch.isDigit() }.take(6) },
                    label = { Text("6-digit code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = !busy && totpCode.length == 6,
                    onClick = {
                        error = null
                        busy = true
                        scope.launch {
                            runCatching {
                                val res = authApi.mfaEnable(email, password, totpCode)
                                if (res.ok && !res.token.isNullOrBlank()) {
                                    saveSession(res.token, res.expiresAt, res.email)
                                } else {
                                    error = res.error ?: "Could not enable MFA"
                                }
                            }.onFailure { error = it.message }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Verifying…" else "Enable 2FA & enter app") }
            }

            Step.MfaVerify -> {
                OutlinedTextField(
                    value = totpCode,
                    onValueChange = { totpCode = it.filter { ch -> ch.isDigit() }.take(6) },
                    label = { Text("Authenticator code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = !busy && totpCode.length == 6 && mfaToken != null,
                    onClick = {
                        error = null
                        busy = true
                        scope.launch {
                            runCatching {
                                val res = authApi.mfaVerify(mfaToken!!, totpCode)
                                if (res.ok && !res.token.isNullOrBlank()) {
                                    saveSession(res.token, res.expiresAt, res.email)
                                } else {
                                    error = res.error ?: "Invalid code"
                                }
                            }.onFailure { error = it.message }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Verifying…" else "Verify & enter") }
            }

            else -> Unit
        }
    }
}
