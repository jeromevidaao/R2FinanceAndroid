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
 * Android stores place label only ([TransactionEntity.locationDisplay]);
 * search by payee + city so restaurant places open with useful results.
 */
object GoogleMaps {

    private val RESTAURANT_PFC_RE =
        Regex(
            "FOOD|RESTAURANT|COFFEE|CAFE|BAR|DINING|FAST.?FOOD|TAKEOUT|BAKERY|BREWERY|PUB|NIGHTLIFE",
            RegexOption.IGNORE_CASE,
        )

    /** True when Plaid personal finance category looks like food/restaurant. */
    fun isRestaurantPlace(plaidPfc: String?): Boolean {
        if (plaidPfc.isNullOrBlank()) return false
        return RESTAURANT_PFC_RE.containsMatchIn(plaidPfc)
    }

    /**
     * Build a Google Maps search URL, or null when nothing useful to search.
     */
    fun searchUrl(
        payee: String? = null,
        locationDisplay: String? = null,
        lat: Double? = null,
        lon: Double? = null,
    ): String? {
        if (lat != null && lon != null && lat.isFinite() && lon.isFinite()) {
            return "https://www.google.com/maps/search/?api=1&query=$lat,$lon"
        }
        val name = payee?.trim().orEmpty()
        val place = locationDisplay?.trim().orEmpty()
        val parts = mutableListOf<String>()
        if (name.isNotEmpty()) parts.add(name)
        if (place.isNotEmpty() && !place.equals(name, ignoreCase = true)) {
            parts.add(place)
        }
        val query = parts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        if (query.isEmpty()) return null
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        return "https://www.google.com/maps/search/?api=1&query=$encoded"
    }

    /**
     * When to surface a Maps link during categorization:
     * - any row with a place pin (locationDisplay), or
     * - restaurant-like PFC with a resolvable payee name.
     */
    fun urlForTxn(
        txn: TransactionEntity,
        payee: String? = null,
    ): String? {
        val hasPlace = !txn.locationDisplay.isNullOrBlank()
        val name = payee?.trim()?.takeIf { it.isNotEmpty() }
            ?: txn.plaidMerchantName?.trim()?.takeIf { it.isNotEmpty() }
        if (!hasPlace && !(isRestaurantPlace(txn.plaidPfc) && name != null)) {
            return null
        }
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
