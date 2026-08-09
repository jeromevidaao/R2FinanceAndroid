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
 * Only surface a link when a place was found as text — never raw lat/lon.
 *
 * Query quality: payee + city/region. Drop leading POI fragments in
 * locationDisplay that do not match the payee (defensive if a full
 * "Sister's cafe, Bellevue, WA" ever lands in locationDisplay).
 */
object GoogleMaps {

    /** Bare lat/lon pair like "47.6,-122.3" — not a found place label. */
    private val COORD_ONLY_RE =
        Regex("""^-?\d{1,3}(?:\.\d+)?\s*,\s*-?\d{1,3}(?:\.\d+)?$""")

    private val GENERIC_PLACE_WORDS = setOf(
        "cafe", "coffee", "restaurant", "bar", "grill", "kitchen",
        "bistro", "shop", "store", "market", "food",
    )

    /** True when [s] is only a coordinate pair (not a city/address label). */
    fun isCoordinateQuery(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        return COORD_ONLY_RE.matches(t)
    }

    /** Non-empty place text that is not a bare coordinate pair. */
    fun isPlaceLabel(s: String?): Boolean {
        val t = s?.trim().orEmpty()
        if (t.isEmpty()) return false
        if (isCoordinateQuery(t)) return false
        return true
    }

    /** Street-like line contains a digit (house/route number). */
    fun isStreetAddress(s: String?): Boolean {
        val t = s?.trim().orEmpty()
        if (t.isEmpty() || isCoordinateQuery(t)) return false
        return t.any { it.isDigit() }
    }

    private fun normTokens(s: String): Set<String> =
        s.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 }
            .toSet()

    /**
     * Soft relatedness for payee vs a place/POI fragment.
     * Rejects "Don's Cafe" ↔ "Sister's cafe".
     */
    fun placeNameRelated(a: String?, b: String?): Boolean {
        val na = a?.trim()?.lowercase().orEmpty()
        val nb = b?.trim()?.lowercase().orEmpty()
        if (na.isEmpty() || nb.isEmpty()) return false
        if (na == nb) return true
        if (na.contains(nb) || nb.contains(na)) return true
        val A = normTokens(na)
        val B = normTokens(nb)
        if (A.isEmpty() || B.isEmpty()) return false
        var distinctive = 0
        for (t in A) {
            if (t in B && t !in GENERIC_PLACE_WORDS) distinctive += 1
        }
        if (distinctive >= 1) return true
        val hit = A.count { it in B }
        val union = (A + B).size
        return union > 0 && hit.toDouble() / union >= 0.5
    }

    /**
     * If [locationDisplay] starts with a POI name that does not match [payee],
     * drop that head and keep the geographic tail (city, region, …).
     * "Sister's cafe, Bellevue, WA, 98005, US" + payee Don's Cafe
     * → "Bellevue, WA, 98005, US"
     */
    fun cleanPlaceForPayee(payee: String?, locationDisplay: String?): String? {
        val place = locationDisplay?.trim().orEmpty()
        if (!isPlaceLabel(place)) return null
        val name = payee?.trim().orEmpty()
        if (name.isEmpty()) return place
        if (placeNameRelated(name, place)) return place

        // Split on comma: first segment is often a wrong POI name.
        val parts = place.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return place

        val head = parts.first()
        // Keep head only if street-like or related to payee.
        val keepHead = isStreetAddress(head) || placeNameRelated(name, head)
        val tail = if (keepHead) parts else parts.drop(1)
        val cleaned = tail.joinToString(", ").trim()
        return cleaned.takeIf { isPlaceLabel(it) } ?: place
    }

    /**
     * Build a Google Maps search URL from place text, or null when no place
     * label is available (coordinates alone are not used).
     */
    fun searchUrl(
        payee: String? = null,
        locationDisplay: String? = null,
    ): String? {
        val place = cleanPlaceForPayee(payee, locationDisplay) ?: return null
        val name = payee?.trim().orEmpty()
        val parts = mutableListOf<String>()
        if (name.isNotEmpty()) parts.add(name)
        if (!place.equals(name, ignoreCase = true) &&
            !parts.joinToString(" ").contains(place, ignoreCase = true)
        ) {
            parts.add(place)
        }
        val query = parts.joinToString(", ").replace(Regex("\\s+"), " ").trim()
        if (query.isEmpty() || isCoordinateQuery(query)) return null
        if (name.isNotEmpty() && query.equals(name, ignoreCase = true)) return null
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        return "https://www.google.com/maps/search/?api=1&query=$encoded"
    }

    /**
     * Surface a Maps link only when enrich found a place label
     * ([TransactionEntity.locationDisplay]). Restaurant-name guesses without
     * a place are suppressed. Coordinate-only labels are not links.
     */
    fun urlForTxn(
        txn: TransactionEntity,
        payee: String? = null,
    ): String? {
        if (!isPlaceLabel(txn.locationDisplay)) return null
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
        // Defense: never open a bare lat/lon pin if a bad URL slipped through.
        val query = url.substringAfter("query=", missingDelimiterValue = "")
            .substringBefore('&')
            .let {
                try {
                    java.net.URLDecoder.decode(it, StandardCharsets.UTF_8.name())
                } catch (_: Exception) {
                    it
                }
            }
        if (query.isNotEmpty() && isCoordinateQuery(query)) return
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
