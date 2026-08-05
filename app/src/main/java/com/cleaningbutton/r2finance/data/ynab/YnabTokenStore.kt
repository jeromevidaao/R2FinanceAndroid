package com.cleaningbutton.r2finance.data.ynab

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Personal Access Token storage. Never log or commit the token.
 * Prefer Phase 3 server-side Secrets Manager for long-term dual-sync.
 */
class YnabTokenStore(context: Context) {
    private val prefs: SharedPreferences = runCatching {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "r2finance_ynab_secure",
            masterKeyAlias,
            context.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        // Fallback if crypto provider unavailable (tests / rare devices)
        context.applicationContext.getSharedPreferences("r2finance_ynab", Context.MODE_PRIVATE)
    }

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun setToken(token: String?) {
        prefs.edit().apply {
            if (token.isNullOrBlank()) remove(KEY_TOKEN) else putString(KEY_TOKEN, token.trim())
            apply()
        }
    }

    fun hasToken(): Boolean = !getToken().isNullOrBlank()

    companion object {
        private const val KEY_TOKEN = "pat"
    }
}
