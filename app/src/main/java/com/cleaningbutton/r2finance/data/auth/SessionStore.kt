package com.cleaningbutton.r2finance.data.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

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

    /** True after full password+MFA login until process death / logout; biometric reopens. */
    var unlockedThisInstall: Boolean = false

    fun isSessionValid(): Boolean {
        val t = token
        return !t.isNullOrBlank() && expiresAt > System.currentTimeMillis()
    }

    fun saveSession(token: String, email: String, expiresAt: Long) {
        this.token = token
        this.email = email
        this.expiresAt = expiresAt
        unlockedThisInstall = true
    }

    fun clear() {
        prefs.edit().clear().apply()
        unlockedThisInstall = false
    }

    companion object {
        private const val KEY_TOKEN = "token"
        private const val KEY_EMAIL = "email"
        private const val KEY_EXP = "expiresAt"
        private const val KEY_BIO = "biometric"
    }
}
