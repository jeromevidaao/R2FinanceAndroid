package com.cleaningbutton.r2finance.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonOrderLinkTest {
    @Test
    fun httpsUrl_fromOrderNumber() {
        val u = AmazonOrderLink.httpsUrl("113-4449307-3045054")
        assertEquals(
            "https://www.amazon.com/your-orders/order-details?orderID=113-4449307-3045054",
            u,
        )
    }

    @Test
    fun appDeepLink_shoppingWebScheme() {
        val u = AmazonOrderLink.appDeepLink("113-4449307-3045054")
        assertTrue(u!!.startsWith("com.amazon.mobile.shopping.web://www.amazon.com/"))
        assertTrue(u.contains("orderID=113-4449307-3045054"))
    }

    @Test
    fun emptyOrder_null() {
        assertNull(AmazonOrderLink.httpsUrl(null))
        assertNull(AmazonOrderLink.appDeepLink("  "))
    }
}
