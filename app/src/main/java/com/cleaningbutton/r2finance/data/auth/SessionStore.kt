package com.cleaningbutton.r2finance.data.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.cleaningbutton.r2finance.BuildConfig

/**
 * Login session survives **app updates** as long as the APK is signed with the same
 * upload keystore (CI OTA). Data lives in EncryptedSharedPreferences under the app id.
 *
 * After an OTA restart we auto-unlock once ([consumePostUpdateUnlock]) so the user is
 * not forced through password/MFA again.
 */
class SessionStore(context: Context) {
    private val prefs = runCatching {
        val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "r2finance_session",
            alias,
            context.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        context.applicationContext.getSharedPreferences("r2finance_session_plain", Context.MODE_PRIVATE)
    }

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(v) = prefs.edit().putString(KEY_TOKEN, v).apply()

    var email: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(v) = prefs.edit().putString(KEY_EMAIL, v).apply()

    var expiresAt: Long
        get() = prefs.getLong(KEY_EXP, 0L)
        set(v) = prefs.edit().putLong(KEY_EXP, v).apply()

    var biometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIO, false)
        set(v) = prefs.edit().putBoolean(KEY_BIO, v).apply()

    private var lastVersionCode: Int
        get() = prefs.getInt(KEY_LAST_VC, 0)
        set(v) = prefs.edit().putInt(KEY_LAST_VC, v).apply()

    /**
     * In-memory only for this process. After OTA, [onAppStart] may set this true so
     * we skip fingerprint immediately after update.
     */
    var unlockedThisProcess: Boolean = false

    fun isSessionValid(): Boolean {
        val t = token
        return !t.isNullOrBlank() && expiresAt > System.currentTimeMillis()
    }

    fun saveSession(token: String, email: String, expiresAt: Long) {
        this.token = token
        this.email = email
        this.expiresAt = expiresAt
        biometricEnabled = true
        unlockedThisProcess = true
        lastVersionCode = BuildConfig.VERSION_CODE
    }

    /**
     * Call once at process start. Returns preferred start phase: "auth" | "bio" | "app".
     */
    fun resolveStartPhase(): String {
        if (!isSessionValid()) {
            // Still record version so a future update can detect the jump
            if (lastVersionCode == 0) lastVersionCode = BuildConfig.VERSION_CODE
            return "auth"
        }

        val previous = lastVersionCode
        val current = BuildConfig.VERSION_CODE
        // OTA just installed a newer build — keep login, skip biometric this open
        if (previous > 0 && current > previous) {
            lastVersionCode = current
            unlockedThisProcess = true
            return "app"
        }
        lastVersionCode = current

        if (unlockedThisProcess) return "app"
        if (biometricEnabled) return "bio"
        unlockedThisProcess = true
        return "app"
    }

    fun markUnlocked() {
        unlockedThisProcess = true
        lastVersionCode = BuildConfig.VERSION_CODE
    }

    /** Explicit logout only — never call this on biometric cancel. */
    fun clear() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_EMAIL)
            .remove(KEY_EXP)
            // Keep lastVersionCode so we still detect OTA after re-login
            .apply()
        unlockedThisProcess = false
        // biometricEnabled stays true preference; session gone so login required
    }

    companion object {
        private const val KEY_TOKEN = "token"
        private const val KEY_EMAIL = "email"
        private const val KEY_EXP = "expiresAt"
        private const val KEY_BIO = "biometric"
        private const val KEY_LAST_VC = "lastVersionCode"
    }
}
