package com.cleaningbutton.r2finance.domain

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.cleaningbutton.r2finance.data.local.entity.TransactionEntity
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Google Maps deep links for categorize / approval context.
 * Android stores place label only ([TransactionEntity.locationDisplay]).
 * Only surface a link when a place was found as text — never raw lat/lon;
 * search by payee + place name.
 */
object GoogleMaps {

    /**
     * Build a Google Maps search URL from place text, or null when no place
     * label is available (coordinates alone are not used).
     */
    fun searchUrl(
        payee: String? = null,
        locationDisplay: String? = null,
    ): String? {
        val place = locationDisplay?.trim().orEmpty()
        if (place.isEmpty()) return null
        val name = payee?.trim().orEmpty()
        val parts = mutableListOf<String>()
        if (name.isNotEmpty()) parts.add(name)
        if (!place.equals(name, ignoreCase = true)) {
            parts.add(place)
        }
        val query = parts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        if (query.isEmpty()) return null
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        return "https://www.google.com/maps/search/?api=1&query=$encoded"
    }

    /**
     * Surface a Maps link only when enrich found a place label
     * ([TransactionEntity.locationDisplay]). Restaurant-name guesses without
     * a place are suppressed.
     */
    fun urlForTxn(
        txn: TransactionEntity,
        payee: String? = null,
    ): String? {
        if (txn.locationDisplay.isNullOrBlank()) return null
        val name = payee?.trim()?.takeIf { it.isNotEmpty() }
            ?: txn.plaidMerchantName?.trim()?.takeIf { it.isNotEmpty() }
        return searchUrl(
            payee = name,
            locationDisplay = txn.locationDisplay,
        )
    }

    /** Open Maps (or browser) for [url]; no-op on empty / no handler. */
    fun open(context: Context, url: String?) {
        if (url.isNullOrBlank()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (context !is android.app.Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No browser / maps app — ignore.
        }
    }
}
