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
                // RTA income — not spending
                txn("2026-03-10", 10000, categoryId = "rta", payeeId = null),
                txn("2026-03-12", -2000, transfer = "acct-2"), // transfer ignored
                txn("2026-02-01", -9000, categoryId = "food"), // other month
            )
        val report =
            Analytics.buildSpendingReport(
                transactions = txns,
                mode = PeriodMode.MONTH,
                periodKey = "2026-03",
                categoryNames =
                    mapOf(
                        "food" to "Food",
                        "gas" to "Gas",
                        "rta" to Analytics.INFLOW_READY_TO_ASSIGN,
                    ),
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
                txn("2026-02-20", 5000, categoryId = "rta"),
                txn("2025-12-01", -9999), // other year
            )
        val report =
            Analytics.buildSpendingReport(
                transactions = txns,
                mode = PeriodMode.YEAR,
                periodKey = "2026",
                categoryNames = mapOf("rta" to Analytics.INFLOW_READY_TO_ASSIGN),
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
                // Refund in a spending category nets against spending (not income)
                txn("2026-03-01", 500, categoryId = "food"),
            )
        val report =
            Analytics.buildSpendingReport(
                transactions = txns,
                mode = PeriodMode.ALL,
                periodKey = "all",
                categoryNames = mapOf("food" to "Food"),
            )
        assertEquals(2, report.yearlyTrend.size)
        assertEquals(-2500L, report.outflowMilli) // -1000 -2000 +500 refund
        assertEquals(0L, report.inflowMilli)
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

    @Test
    fun unapproved_includedInSpendingByDefault() {
        // YNAB month/Reflect activity includes unapproved (inbox) amounts.
        val txns =
            listOf(
                txn("2026-08-01", -10000, categoryId = "food"),
                AnalyticsTxn(
                    date = "2026-08-02",
                    amountMilli = -50000,
                    categoryId = "food",
                    accountId = "acct-1",
                    approved = false,
                ),
            )
        val report =
            Analytics.buildSpendingReport(
                transactions = txns,
                mode = PeriodMode.MONTH,
                periodKey = "2026-08",
                categoryNames = mapOf("food" to "Food"),
            )
        assertEquals(-60000L, report.outflowMilli)
        assertEquals(2, report.count)
    }

    @Test
    fun unapproved_excludedWhenApprovedOnlyTrue() {
        val txns =
            listOf(
                txn("2026-08-01", -10000, categoryId = "food"),
                AnalyticsTxn(
                    date = "2026-08-02",
                    amountMilli = -50000,
                    categoryId = "food",
                    accountId = "acct-1",
                    approved = false,
                ),
            )
        val report =
            Analytics.buildSpendingReport(
                transactions = txns,
                mode = PeriodMode.MONTH,
                periodKey = "2026-08",
                categoryNames = mapOf("food" to "Food"),
                approvedOnly = true,
            )
        assertEquals(-10000L, report.outflowMilli)
        assertEquals(1, report.count)
    }

    @Test
    fun transferSplitLeg_excludedFromSpending() {
        val txns =
            listOf(
                AnalyticsTxn(
                    date = "2026-08-01",
                    amountMilli = -15000,
                    accountId = "acct-1",
                    approved = true,
                    splitLines =
                        listOf(
                            AnalyticsSplitLine(
                                amountMilli = -10000,
                                categoryId = "food",
                            ),
                            AnalyticsSplitLine(
                                amountMilli = -5000,
                                transferAccountId = "acct-2",
                            ),
                        ),
                ),
            )
        val report =
            Analytics.buildSpendingReport(
                transactions = txns,
                mode = PeriodMode.MONTH,
                periodKey = "2026-08",
                categoryNames = mapOf("food" to "Food"),
            )
        assertEquals(-10000L, report.outflowMilli)
        assertEquals(1, report.byCategory.size)
        assertEquals(-10000L, report.byCategory[0].amountMilli)
    }

    @Test
    fun refundsNetAgainstSpending_notCountedAsIncome() {
        // YNAB July-style: gross outflows 100, refund 30 → Total spending 70.
        val txns =
            listOf(
                txn("2026-07-01", -10000, categoryId = "food"),
                txn("2026-07-05", 3000, categoryId = "food"), // refund
                txn("2026-07-10", 50000, categoryId = "rta"), // income
            )
        val report =
            Analytics.buildSpendingReport(
                transactions = txns,
                mode = PeriodMode.MONTH,
                periodKey = "2026-07",
                categoryNames =
                    mapOf(
                        "food" to "Food",
                        "rta" to Analytics.INFLOW_READY_TO_ASSIGN,
                    ),
            )
        assertEquals(50000L, report.inflowMilli)
        assertEquals(-7000L, report.outflowMilli)
        assertEquals(1, report.byCategory.size)
        assertEquals(-7000L, report.byCategory[0].amountMilli)
    }

    @Test
    fun rtaNeverInSpendingBreakdown() {
        val txns =
            listOf(
                txn("2026-07-01", -1000, categoryId = "food"),
                txn("2026-07-02", -2000, categoryId = "rta"), // mis-signed RTA still income
            )
        val report =
            Analytics.buildSpendingReport(
                transactions = txns,
                mode = PeriodMode.MONTH,
                periodKey = "2026-07",
                categoryNames =
                    mapOf(
                        "food" to "Food",
                        "rta" to Analytics.INFLOW_READY_TO_ASSIGN,
                    ),
            )
        assertEquals(-2000L, report.inflowMilli)
        assertEquals(-1000L, report.outflowMilli)
        assertEquals(1, report.byCategory.size)
        assertEquals("Food", report.byCategory[0].name)
    }
}
