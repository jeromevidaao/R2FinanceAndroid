package com.cleaningbutton.r2finance.domain

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.cleaningbutton.r2finance.data.local.entity.TransactionEntity
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Open a matched Amazon order in the Shopping app (preferred) or browser.
 * Mirrors [GoogleMaps] ACTION_VIEW pattern used on Categorize / inbox.
 */
object AmazonOrderLink {

    /** https order-details (canonical; App Links often open Shopping). */
    private fun enc(n: String): String =
        URLEncoder.encode(n, StandardCharsets.UTF_8.name())

    fun httpsUrl(orderNumber: String?, fallback: String? = null): String? {
        val n = orderNumber?.trim().orEmpty()
        if (n.isNotEmpty()) {
            return "https://www.amazon.com/your-orders/order-details?orderID=" + enc(n)
        }
        return fallback?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Shopping-app deep link (`com.amazon.mobile.shopping.web://…`).
     * Falls back to null when no order number.
     */
    fun appDeepLink(orderNumber: String?): String? {
        val n = orderNumber?.trim().orEmpty()
        if (n.isEmpty()) return null
        return "com.amazon.mobile.shopping.web://www.amazon.com" +
            "/gp/your-account/order-details?orderID=" + enc(n)
    }

    fun httpsUrlForTxn(txn: TransactionEntity): String? =
        httpsUrl(txn.amazonOrderNumber, txn.amazonOrderUrl)

    fun appDeepLinkForTxn(txn: TransactionEntity): String? =
        appDeepLink(txn.amazonOrderNumber)

    /** Try Shopping app deep link, then https; no-op when neither works. */
    fun open(context: Context, txn: TransactionEntity) {
        open(context, appDeepLinkForTxn(txn), httpsUrlForTxn(txn))
    }

    fun open(context: Context, appUrl: String?, httpsUrl: String?) {
        val candidates = listOfNotNull(
            appUrl?.trim()?.takeIf { it.isNotEmpty() },
            httpsUrl?.trim()?.takeIf { it.isNotEmpty() },
        ).distinct()
        for (url in candidates) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            if (context !is android.app.Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // try next
            }
        }
    }
}
