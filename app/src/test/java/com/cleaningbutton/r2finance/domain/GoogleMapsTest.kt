package com.cleaningbutton.r2finance.domain

import com.cleaningbutton.r2finance.data.local.entity.TransactionEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class GoogleMapsTest {

    private fun queryOf(url: String): String {
        val raw = url.substringAfter("query=").substringBefore('&')
        return URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
    }

    @Test
    fun coordinate_query_detection() {
        assertTrue(GoogleMaps.isCoordinateQuery("47.6,-122.3"))
        assertTrue(GoogleMaps.isCoordinateQuery("47.6062, -122.3321"))
        assertFalse(GoogleMaps.isCoordinateQuery("Seattle, WA"))
        assertFalse(GoogleMaps.isCoordinateQuery("123 Main St"))
        assertFalse(GoogleMaps.isPlaceLabel(null))
        assertFalse(GoogleMaps.isPlaceLabel("  "))
        assertFalse(GoogleMaps.isPlaceLabel("47.0, -122.0"))
        assertTrue(GoogleMaps.isPlaceLabel("Portland, OR"))
    }

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
    fun search_url_null_for_coords_only_label() {
        assertNull(
            GoogleMaps.searchUrl(
                payee = "Voyager Cafe",
                locationDisplay = "47.6,-122.3",
            ),
        )
        assertNull(
            GoogleMaps.searchUrl(
                payee = "Voyager Cafe",
                locationDisplay = "47.6062, -122.3321",
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
        val q = queryOf(url)
        assertTrue(q.contains("Voyager", ignoreCase = true))
        assertTrue(q.contains("Seattle", ignoreCase = true))
        assertTrue(!GoogleMaps.isCoordinateQuery(q))
    }

    @Test
    fun drops_mismatched_poi_head_in_location_display() {
        val url = GoogleMaps.searchUrl(
            payee = "Don's Cafe",
            locationDisplay = "Sister's cafe, Bellevue, WA, 98005, US",
        )
        assertNotNull(url)
        val q = queryOf(url!!)
        assertTrue(q.contains("Don", ignoreCase = true))
        assertTrue(q.contains("Bellevue", ignoreCase = true))
        assertFalse(q.contains("Sister", ignoreCase = true))
    }

    @Test
    fun place_name_related_rejects_wrong_cafe() {
        assertFalse(GoogleMaps.placeNameRelated("Don's Cafe", "Sister's cafe"))
        assertTrue(GoogleMaps.placeNameRelated("Don's Cafe", "Don's Cafe"))
        assertTrue(GoogleMaps.isStreetAddress("1023 4th Ave"))
        assertFalse(GoogleMaps.isStreetAddress("Sister's cafe"))
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
    fun no_url_for_coords_only_location_display() {
        val txn = TransactionEntity(
            id = "t4",
            planId = "p",
            accountId = "a",
            date = "2026-08-01",
            amountMilli = -5000,
            locationDisplay = "37.7749, -122.4194",
            plaidMerchantName = "Some Cafe",
        )
        assertNull(GoogleMaps.urlForTxn(txn, "Some Cafe"))
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
