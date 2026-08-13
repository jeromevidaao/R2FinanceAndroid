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
    fun split_all_transfer_legs_do_not_count_parent_as_spend() {
        // Parent has no transferAccountId but every split leg is a transfer and
        // legs sum to the parent. Falling back to parent used to inflate Reflect.
        val txns =
            listOf(
                AnalyticsTxn(
                    date = "2026-08-01",
                    amountMilli = -239_350L,
                    categoryId = null,
                    accountId = "checking",
                    transferAccountId = null,
                    approved = true,
                    splitLines =
                        listOf(
                            AnalyticsSplitLine(
                                amountMilli = -239_350L,
                                transferAccountId = "cc",
                            ),
                        ),
                ),
                AnalyticsTxn(
                    date = "2026-08-01",
                    amountMilli = -5_000L,
                    categoryId = "food",
                    accountId = "checking",
                    approved = true,
                ),
            )
        val report =
            Analytics.buildSpendingReport(
                transactions = txns,
                mode = PeriodMode.MONTH,
                periodKey = "2026-08",
                categoryNames = mapOf("food" to "Food"),
            )
        assertEquals(-5_000L, report.outflowMilli)
        assertEquals(1, report.count) // transfer-split parent skipped from spend
    }

    @Test
    fun orphan_transfer_only_split_legs_do_not_zero_real_spend() {
        // Stale Room legs: transfer-only but amounts do not equal parent.
        // Must use parent so Reflect is not undercounted to near-zero.
        val txns =
            listOf(
                AnalyticsTxn(
                    date = "2026-08-01",
                    amountMilli = -50_000_00L, // $50 real spend
                    categoryId = "food",
                    accountId = "checking",
                    transferAccountId = null,
                    approved = true,
                    splitLines =
                        listOf(
                            AnalyticsSplitLine(
                                amountMilli = -1L, // orphan garbage, does not sum to parent
                                transferAccountId = "cc",
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
        assertEquals(-50_000_00L, report.outflowMilli)
        assertEquals(1, report.count)
        assertEquals("Food", report.byCategory[0].name)
    }

    @Test
    fun partial_nontransfer_legs_that_do_not_reconcile_use_parent() {
        // Stale Room: a tiny non-transfer leg exists so the old path used only
        // that leg and undercounted Last 12 Months to ~$2k vs YNAB ~$294k.
        // Legs must sum to parent before we trust non-transfer subset.
        val txns =
            listOf(
                AnalyticsTxn(
                    date = "2026-08-01",
                    amountMilli = -500_000_00L, // $500 real spend
                    categoryId = "food",
                    accountId = "checking",
                    transferAccountId = null,
                    approved = true,
                    splitLines =
                        listOf(
                            AnalyticsSplitLine(
                                amountMilli = -2_089_86L, // leftover partial leg
                                categoryId = "food",
                            ),
                            AnalyticsSplitLine(
                                amountMilli = -1L,
                                transferAccountId = "cc",
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
        assertEquals(-500_000_00L, report.outflowMilli)
        assertEquals(1, report.count)
    }

    @Test
    fun reconciled_split_excludes_transfer_leg_only() {
        val txns =
            listOf(
                AnalyticsTxn(
                    date = "2026-08-01",
                    amountMilli = -150_00L,
                    categoryId = null,
                    accountId = "checking",
                    approved = true,
                    splitLines =
                        listOf(
                            AnalyticsSplitLine(
                                amountMilli = -100_00L,
                                categoryId = "food",
                            ),
                            AnalyticsSplitLine(
                                amountMilli = -50_00L,
                                transferAccountId = "cc",
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
        assertEquals(-100_00L, report.outflowMilli)
        assertEquals(-100_00L, report.byCategory[0].amountMilli)
    }

    @Test
    fun presetLast12_sumsRollingYear() {
        val now = java.time.LocalDate.of(2026, 8, 12)
        val txns =
            (0 until 12).map { i ->
                val ym = now.withDayOfMonth(1).minusMonths(i.toLong())
                txn(
                    date = "${ym.year}-${ym.monthValue.toString().padStart(2, '0')}-15",
                    amount = -1_000_00L,
                )
            } +
                listOf(
                    // 13 months ago — outside last 12
                    txn(date = "2025-07-15", amount = -9_999_00L),
                )
        val report =
            Analytics.buildSpendingReport(
                transactions = txns,
                mode = PeriodMode.PRESET,
                periodKey = PresetId.LAST_12.key,
                now = now,
            )
        assertEquals(-12_000_00L, report.outflowMilli)
        assertEquals(12, report.monthsCovered)
        assertEquals("Last 12 Months", report.periodLabel)
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
