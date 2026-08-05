package com.cleaningbutton.r2finance.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class MoneyTest {
    @Test
    fun milliunits_fromMajorUnits() {
        assertEquals(123930L, Money.fromMajorUnits(123.93))
        assertEquals(-220L, Money.fromMajorUnits(-0.22))
        assertEquals(1000L, Money.fromMajorUnits(BigDecimal.ONE))
    }

    @Test
    fun milliunits_roundTrip() {
        val m = Money.fromMajorUnits(12.50)
        assertEquals(BigDecimal("12.500"), Money.toMajorDecimal(m))
    }

    @Test
    fun format_usd() {
        val s = Money.format(123930L, "USD")
        assertTrue(s.contains("123.93") || s.contains("123,93"))
    }
}

class DomainRulesTest {
    @Test
    fun split_must_sum_to_parent() {
        assertTrue(DomainRules.splitAmountsValid(-10000, listOf(-6000, -4000)))
        assertFalse(DomainRules.splitAmountsValid(-10000, listOf(-6000, -3000)))
        assertFalse(DomainRules.splitAmountsValid(-10000, emptyList()))
    }

    @Test
    fun inbox_unapproved_or_uncategorized() {
        assertTrue(
            DomainRules.isInboxItem(
                approved = false,
                onBudget = true,
                categoryId = "cat",
                hasSubtransactions = false,
            ),
        )
        assertTrue(
            DomainRules.isInboxItem(
                approved = true,
                onBudget = true,
                categoryId = null,
                hasSubtransactions = false,
            ),
        )
        assertFalse(
            DomainRules.isInboxItem(
                approved = true,
                onBudget = true,
                categoryId = "cat",
                hasSubtransactions = false,
            ),
        )
        assertFalse(
            DomainRules.isInboxItem(
                approved = true,
                onBudget = false,
                categoryId = null,
                hasSubtransactions = false,
            ),
        )
    }
}
