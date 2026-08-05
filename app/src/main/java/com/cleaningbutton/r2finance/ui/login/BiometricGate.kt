package com.cleaningbutton.r2finance.ui.login

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object BiometricGate {
    fun canAuthenticate(context: Context): Boolean {
        val mgr = BiometricManager.from(context)
        val res = mgr.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
        return res == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun prompt(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onCancel: () -> Unit = {},
        onFail: (String) -> Unit = {},
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            onSuccess()
            return
        }
        if (!canAuthenticate(activity)) {
            onSuccess()
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) {
                        onCancel()
                    } else {
                        onFail(errString.toString())
                    }
                }
            },
        )
        try {
            val info = try {
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock R2Finance")
                    .setSubtitle("Use fingerprint or face")
                    .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_WEAK or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                    )
                    .build()
            } catch (_: Exception) {
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock R2Finance")
                    .setSubtitle("Use fingerprint")
                    .setNegativeButtonText("Cancel")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                    .build()
            }
            prompt.authenticate(info)
        } catch (_: Throwable) {
            onSuccess()
        }
    }
}
