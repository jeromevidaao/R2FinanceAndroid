package com.cleaningbutton.r2finance.data.aggregates

import com.cleaningbutton.r2finance.domain.AnalyticsTxn
import com.cleaningbutton.r2finance.domain.PeriodMode
import com.cleaningbutton.r2finance.domain.PresetId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AggregatesBuilderTest {
    private fun txn(
        date: String,
        amount: Long,
        categoryId: String? = "food",
        approved: Boolean = true,
        transfer: String? = null,
        accountId: String = "acct-1",
    ) = AnalyticsTxn(
        date = date,
        amountMilli = amount,
        categoryId = categoryId,
        categoryGroupId = "grp",
        payeeId = "p1",
        accountId = accountId,
        transferAccountId = transfer,
        approved = approved,
    )

    @Test
    fun precomputesReflectMonthAndPresets() {
        val now = LocalDate.now()
        val ym = now.toString().take(7)
        val prev = now.withDayOfMonth(1).minusMonths(1).toString().take(7)
        val txns =
            listOf(
                txn("$ym-05", -10_000_00),
                txn("$ym-10", 5_000_00, categoryId = null),
                txn("$prev-15", -2_000_00),
                txn("$ym-12", -500_00, approved = false), // excluded
                txn("$ym-13", -100_00, transfer = "acct-2"), // excluded
            )

        val agg =
            AggregatesBuilder.buildFromAnalytics(
                planId = "default",
                analyticsTxns = txns,
                categoryNames = mapOf("food" to "Food"),
            )

        assertTrue(agg.ready)
        assertEquals(5, agg.txnCount)
        assertNotNull(agg.reflectReport)
        // Transfer excluded; unapproved included so Reflect is not $0 with inbox spend
        assertEquals(-10_500_00L, agg.reflectReport!!.outflowMilli)
        assertTrue(agg.presetReports.containsKey(PresetId.LAST_3.key))
        assertTrue(agg.monthReports.containsKey(ym))
        assertEquals(1, agg.home.inboxCount)
        assertTrue(agg.incomeTrend.isNotEmpty())

        val last3 = agg.presetReports[PresetId.LAST_3.key]!!
        // Approved + unapproved outflows in range (transfer still excluded)
        assertEquals(-12_500_00L, last3.outflowMilli)
    }

    @Test
    fun cachedReportLooksUpMonthAndPreset() {
        val ym = LocalDate.now().toString().take(7)
        val agg =
            AggregatesBuilder.buildFromAnalytics(
                planId = "default",
                analyticsTxns = listOf(txn("$ym-01", -1_000_00)),
            )
        val month =
            LedgerAggregatesStore.cachedReport(agg, PeriodMode.MONTH, ym)
        val preset =
            LedgerAggregatesStore.cachedReport(agg, PeriodMode.PRESET, PresetId.LAST_3.key)
        assertNotNull(month)
        assertNotNull(preset)
        assertEquals(-1_000_00L, month!!.outflowMilli)
    }
}
