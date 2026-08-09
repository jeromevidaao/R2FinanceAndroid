package com.cleaningbutton.r2finance.domain

import com.cleaningbutton.r2finance.data.local.entity.TransactionEntity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleMapsTest {

    @Test
    fun search_url_null_without_place() {
        assertNull(
            GoogleMaps.searchUrl(
                payee = "Voyager Cafe",
                locationDisplay = null,
            ),
        )
        assertNull(
            GoogleMaps.searchUrl(
                payee = "Voyager Cafe",
                locationDisplay = "   ",
            ),
        )
    }

    @Test
    fun search_url_combines_payee_and_city() {
        val url = GoogleMaps.searchUrl(
            payee = "Voyager Cafe",
            locationDisplay = "Seattle, WA",
        )
        assertNotNull(url)
        assertTrue(url!!.contains("query="))
        assertTrue(url.contains("Voyager") || url.contains("Voyager+Cafe"))
        assertTrue(url.contains("Seattle"))
        // Never a raw lat/lon pin query
        assertTrue(!url.matches(Regex(".*query=\\d+\\.\\d+,-?\\d+\\.\\d+.*")))
    }

    @Test
    fun url_for_txn_with_place_pin() {
        val txn = TransactionEntity(
            id = "t1",
            planId = "p",
            accountId = "a",
            date = "2026-08-01",
            amountMilli = -12000,
            locationDisplay = "Seattle, WA",
            plaidMerchantName = "Voyager Cafe",
        )
        val url = GoogleMaps.urlForTxn(txn, "Voyager Cafe")
        assertNotNull(url)
        assertTrue(url!!.contains("Seattle"))
    }

    @Test
    fun no_url_for_restaurant_without_place() {
        val txn = TransactionEntity(
            id = "t2",
            planId = "p",
            accountId = "a",
            date = "2026-08-01",
            amountMilli = -8000,
            plaidPfc = "FOOD_AND_DRINK_RESTAURANTS",
            plaidMerchantName = "Don's Cafe",
        )
        assertNull(GoogleMaps.urlForTxn(txn, "Don's Cafe"))
    }

    @Test
    fun no_url_for_generic_transfer() {
        val txn = TransactionEntity(
            id = "t3",
            planId = "p",
            accountId = "a",
            date = "2026-08-01",
            amountMilli = -100000,
            plaidPfc = "TRANSFER_OUT",
            plaidMerchantName = "Payment",
        )
        assertNull(GoogleMaps.urlForTxn(txn, "Payment"))
    }
}
