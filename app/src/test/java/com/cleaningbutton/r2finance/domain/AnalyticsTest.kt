package com.cleaningbutton.r2finance.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsTest {
    private fun txn(
        date: String,
        amount: Long,
        categoryId: String? = "cat-food",
        groupId: String? = "grp-living",
        payeeId: String? = "payee-1",
        accountId: String = "acct-1",
        transfer: String? = null,
    ) = AnalyticsTxn(
        date = date,
        amountMilli = amount,
        categoryId = categoryId,
        categoryGroupId = groupId,
        payeeId = payeeId,
        accountId = accountId,
        transferAccountId = transfer,
    )

    @Test
    fun monthReport_sumsInOutAndCategories() {
        val txns =
            listOf(
                txn("2026-03-01", -5000, categoryId = "food"),
                txn("2026-03-05", -3000, categoryId = "gas"),
                txn("2026-03-10", 10000, categoryId = null, payeeId = null), // income
                txn("2026-03-12", -2000, transfer = "acct-2"), // transfer ignored
                txn("2026-02-01", -9000, categoryId = "food"), // other month
            )
        val report =
            Analytics.buildSpendingReport(
                transactions = txns,
                mode = PeriodMode.MONTH,
                periodKey = "2026-03",
                categoryNames = mapOf("food" to "Food", "gas" to "Gas"),
            )
        assertEquals(10000L, report.inflowMilli)
        assertEquals(-8000L, report.outflowMilli)
        assertEquals(2000L, report.netMilli)
        assertEquals(3, report.count) // transfer excluded
        assertEquals(2, report.byCategory.size)
        assertEquals("Food", report.byCategory[0].name) // more spent? food -5k, gas -3k
        assertEquals(-5000L, report.byCategory[0].amountMilli)
        assertTrue(report.byCategory[0].share > report.byCategory[1].share)
    }

    @Test
    fun yearReport_aggregatesMonthsAndAvg() {
        val txns =
            listOf(
                txn("2026-01-15", -1000),
                txn("2026-02-15", -3000),
                txn("2026-02-20", 5000, categoryId = null),
                txn("2025-12-01", -9999), // other year
            )
        val report =
            Analytics.buildSpendingReport(
                transactions = txns,
                mode = PeriodMode.YEAR,
                periodKey = "2026",
            )
        assertEquals(-4000L, report.outflowMilli)
        assertEquals(5000L, report.inflowMilli)
        assertEquals(2, report.monthsCovered)
        assertEquals(-2000L, report.avgMonthlyOutflowMilli)
        assertEquals(2, report.monthlyTrend.size)
    }

    @Test
    fun allTime_yearlyTrend() {
        val txns =
            listOf(
                txn("2025-06-01", -1000),
                txn("2026-01-01", -2000),
                txn("2026-03-01", 500),
            )
        val report =
            Analytics.buildSpendingReport(
                transactions = txns,
                mode = PeriodMode.ALL,
                periodKey = "all",
            )
        assertEquals(2, report.yearlyTrend.size)
        assertEquals(-3000L, report.outflowMilli)
        assertEquals(500L, report.inflowMilli)
        assertEquals(-2500L, report.netMilli)
    }

    @Test
    fun listMonths_sortedDesc() {
        val months =
            Analytics.listMonths(
                listOf(
                    txn("2026-01-01", -1),
                    txn("2026-03-01", -1),
                    txn("2026-02-01", -1),
                ),
            )
        assertEquals(listOf("2026-03", "2026-02", "2026-01"), months)
    }

    @Test
    fun presetLast3_includesRollingMonths() {
        val now = java.time.LocalDate.of(2026, 8, 15)
        val txns =
            listOf(
                txn("2026-06-01", -1000),
                txn("2026-07-01", -2000),
                txn("2026-08-01", -3000),
                txn("2026-05-01", -9999), // outside last 3
            )
        val report =
            Analytics.buildSpendingReport(
                transactions = txns,
                mode = PeriodMode.PRESET,
                periodKey = PresetId.LAST_3.key,
                now = now,
            )
        assertEquals(-6000L, report.outflowMilli)
        assertEquals(3, report.monthsCovered)
        assertEquals("Last 3 Months", report.periodLabel)
    }

    @Test
    fun incomeInsight_spendingMore() {
        val points =
            listOf(
                TrendPoint("2026-07", "July", 1000, -5000, -4000, 2),
                TrendPoint("2026-08", "August", 1000, -5000, -4000, 2),
            )
        assertTrue(
            Analytics.incomeVsSpendingInsight(points).contains("spending more"),
        )
    }
}
