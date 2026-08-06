package com.cleaningbutton.r2finance.domain

/**
 * YNAB-style spending analytics over ledger transactions.
 * Pure functions — no Android / Room dependencies (unit-testable).
 */

enum class PeriodMode {
    MONTH,
    YEAR,
    ALL,
    /** Named rolling / calendar ranges (Last 3 Months, YTD, …). */
    PRESET,
}

enum class PresetId(val key: String, val label: String) {
    LAST_3("last3", "Last 3 Months"),
    LAST_6("last6", "Last 6 Months"),
    LAST_12("last12", "Last 12 Months"),
    YTD("ytd", "Year to Date"),
    LAST_YEAR("lastYear", "Last Year"),
    ALL("all", "All Dates"),
    ;

    companion object {
        fun fromKey(key: String): PresetId =
            entries.find { it.key == key } ?: LAST_3
    }
}

data class AnalyticsTxn(
    val date: String,
    val amountMilli: Long,
    val categoryId: String? = null,
    val categoryGroupId: String? = null,
    val payeeId: String? = null,
    val accountId: String,
    val transferAccountId: String? = null,
    /** YNAB Reflect excludes unapproved (inbox) from spending totals. */
    val approved: Boolean = true,
    /** Optional split lines; when non-empty, used for attribution (skip transfer legs). */
    val splitLines: List<AnalyticsSplitLine> = emptyList(),
)

data class AnalyticsSplitLine(
    val amountMilli: Long,
    val categoryId: String? = null,
    val categoryGroupId: String? = null,
    val payeeId: String? = null,
    val transferAccountId: String? = null,
)

data class RankRow(
    val id: String,
    val name: String,
    val amountMilli: Long,
    /** Share of total |outflow| in 0..1 for spending rows. */
    val share: Double,
)

data class TrendPoint(
    val key: String,
    val label: String,
    val inflowMilli: Long,
    val outflowMilli: Long,
    val netMilli: Long,
    val count: Int,
)

data class SpendingReport(
    val mode: PeriodMode,
    val periodKey: String,
    val periodLabel: String,
    val count: Int,
    val inflowMilli: Long,
    val outflowMilli: Long,
    val netMilli: Long,
    val avgMonthlyOutflowMilli: Long,
    val monthsCovered: Int,
    val byCategory: List<RankRow>,
    val byGroup: List<RankRow>,
    val byPayee: List<RankRow>,
    val byAccount: List<RankRow>,
    val monthlyTrend: List<TrendPoint>,
    val yearlyTrend: List<TrendPoint>,
)

data class DateBounds(
    val from: String?,
    val to: String?,
    val label: String,
)

object Analytics {
    const val UNCAT = "__uncat"
    const val NO_GROUP = "__nogroup"

    fun monthKey(date: String): String = date.take(7)

    fun yearKey(date: String): String = date.take(4)

    fun listMonths(txns: List<AnalyticsTxn>): List<String> =
        txns
            .asSequence()
            .filter { it.transferAccountId == null }
            .map { monthKey(it.date) }
            .toSortedSet()
            .toList()
            .asReversed()

    /**
     * Months that have at least one non-transfer outflow (any approval state).
     * Used so Reflect does not land on an empty "current month" with $0 while
     * earlier months still have spending.
     */
    fun listMonthsWithOutflow(txns: List<AnalyticsTxn>): List<String> =
        txns
            .asSequence()
            .filter { it.transferAccountId == null && it.amountMilli < 0 }
            .map { monthKey(it.date) }
            .toSortedSet()
            .toList()
            .asReversed()

    fun listYears(txns: List<AnalyticsTxn>): List<String> =
        txns
            .asSequence()
            .filter { it.transferAccountId == null }
            .map { yearKey(it.date) }
            .toSortedSet()
            .toList()
            .asReversed()

    private fun pad2(n: Int): String = n.toString().padStart(2, '0')

    private fun ymd(y: Int, m0: Int, d: Int): String =
        "${y}-${pad2(m0 + 1)}-${pad2(d)}"

    private fun lastDayOfMonth(y: Int, m0: Int): Int =
        java.time.YearMonth.of(y, m0 + 1).lengthOfMonth()

    fun resolveDateBounds(
        mode: PeriodMode,
        periodKey: String,
        now: java.time.LocalDate = java.time.LocalDate.now(),
    ): DateBounds {
        val y = now.year
        val m0 = now.monthValue - 1
        return when (mode) {
            PeriodMode.ALL -> DateBounds(null, null, "All Dates")
            PeriodMode.MONTH -> {
                val parts = periodKey.split("-")
                val yy = parts.getOrNull(0)?.toIntOrNull() ?: y
                val mm = parts.getOrNull(1)?.toIntOrNull() ?: (m0 + 1)
                DateBounds(
                    from = ymd(yy, mm - 1, 1),
                    to = ymd(yy, mm - 1, lastDayOfMonth(yy, mm - 1)),
                    label = formatPeriodLabel(PeriodMode.MONTH, periodKey),
                )
            }
            PeriodMode.YEAR -> {
                val yy = periodKey.toIntOrNull() ?: y
                DateBounds(ymd(yy, 0, 1), ymd(yy, 11, 31), yy.toString())
            }
            PeriodMode.PRESET -> {
                when (PresetId.fromKey(periodKey)) {
                    PresetId.ALL -> DateBounds(null, null, PresetId.ALL.label)
                    PresetId.YTD ->
                        DateBounds(
                            ymd(y, 0, 1),
                            ymd(y, m0, lastDayOfMonth(y, m0)),
                            PresetId.YTD.label,
                        )
                    PresetId.LAST_YEAR ->
                        DateBounds(
                            ymd(y - 1, 0, 1),
                            ymd(y - 1, 11, 31),
                            PresetId.LAST_YEAR.label,
                        )
                    PresetId.LAST_3, PresetId.LAST_6, PresetId.LAST_12 -> {
                        val n =
                            when (PresetId.fromKey(periodKey)) {
                                PresetId.LAST_3 -> 3
                                PresetId.LAST_6 -> 6
                                else -> 12
                            }
                        val start = now.withDayOfMonth(1).minusMonths((n - 1).toLong())
                        DateBounds(
                            from = ymd(start.year, start.monthValue - 1, 1),
                            to = ymd(y, m0, lastDayOfMonth(y, m0)),
                            label = PresetId.fromKey(periodKey).label,
                        )
                    }
                }
            }
        }
    }

    fun formatPeriodLabel(mode: PeriodMode, key: String): String {
        return when (mode) {
            PeriodMode.ALL -> "All Dates"
            PeriodMode.YEAR -> key
            PeriodMode.PRESET -> PresetId.fromKey(key).label
            PeriodMode.MONTH -> {
                if (key.length < 7) return key
                val y = key.take(4)
                val m = key.substring(5, 7).toIntOrNull() ?: return key
                val monthName =
                    java.time.Month
                        .of(m)
                        .name
                        .lowercase()
                        .replaceFirstChar { it.uppercase() }
                "$monthName $y"
            }
        }
    }

    fun defaultPeriodKey(mode: PeriodMode, txns: List<AnalyticsTxn>): String =
        when (mode) {
            PeriodMode.ALL -> "all"
            PeriodMode.PRESET -> PresetId.LAST_3.key
            PeriodMode.YEAR ->
                listYears(txns).firstOrNull()
                    ?: java.time.Year
                        .now()
                        .value
                        .toString()
            PeriodMode.MONTH ->
                listMonths(txns).firstOrNull()
                    ?: java.time.LocalDate
                        .now()
                        .toString()
                        .take(7)
        }

    fun currentMonthKey(now: java.time.LocalDate = java.time.LocalDate.now()): String =
        now.toString().take(7)

    /** Last N calendar months ending at [endYm] (YYYY-MM), chronological. */
    fun lastNMonthKeys(endYm: String, n: Int): List<String> {
        val parts = endYm.split("-")
        val y = parts.getOrNull(0)?.toIntOrNull() ?: return emptyList()
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return emptyList()
        val end = java.time.YearMonth.of(y, m)
        return (n - 1 downTo 0).map { i ->
            val ym = end.minusMonths(i.toLong())
            "${ym.year}-${pad2(ym.monthValue)}"
        }
    }

    fun incomeVsSpendingInsight(points: List<TrendPoint>): String {
        if (points.isEmpty()) {
            return "Not enough activity yet to compare income and spending."
        }
        val avgIn = points.map { it.inflowMilli }.average()
        val avgOut = points.map { kotlin.math.abs(it.outflowMilli) }.average()
        return when {
            avgOut > avgIn * 1.02 -> "On average, you're spending more than you make."
            avgIn > avgOut * 1.02 -> "On average, you're making more than you spend."
            else -> "On average, income and spending are roughly balanced."
        }
    }

    private fun inBounds(date: String, from: String?, to: String?): Boolean {
        if (from != null && date < from) return false
        if (to != null && date > to) return false
        return true
    }

    private fun rankOutflows(
        map: Map<String, Long>,
        nameOf: (String) -> String,
        totalOutAbs: Long,
    ): List<RankRow> =
        map.entries
            .filter { it.value < 0 }
            .sortedBy { it.value }
            .map { (id, amount) ->
                RankRow(
                    id = id,
                    name = nameOf(id),
                    amountMilli = amount,
                    share =
                        if (totalOutAbs > 0) {
                            kotlin.math.abs(amount).toDouble() / totalOutAbs.toDouble()
                        } else {
                            0.0
                        },
                )
            }

    fun buildSpendingReport(
        transactions: List<AnalyticsTxn>,
        mode: PeriodMode,
        periodKey: String,
        categoryNames: Map<String, String> = emptyMap(),
        groupNames: Map<String, String> = emptyMap(),
        payeeNames: Map<String, String> = emptyMap(),
        accountNames: Map<String, String> = emptyMap(),
        now: java.time.LocalDate = java.time.LocalDate.now(),
        /**
         * When true, only approved rows count (strict YNAB Reflect).
         * Default **false**: R2Finance includes unapproved (inbox) outflows so
         * Reflect is not $0 while spend still sits in Spending/to-approve.
         * Transfers are always excluded.
         */
        approvedOnly: Boolean = false,
    ): SpendingReport {
        val bounds = resolveDateBounds(mode, periodKey, now)
        val periodTxns =
            transactions.filter {
                it.transferAccountId == null &&
                    (!approvedOnly || it.approved) &&
                    inBounds(it.date, bounds.from, bounds.to)
            }

        var inflow = 0L
        var outflow = 0L
        val byCat = mutableMapOf<String, Long>()
        val byGroup = mutableMapOf<String, Long>()
        val byPayee = mutableMapOf<String, Long>()
        val byAccount = mutableMapOf<String, Long>()
        val monthBuckets = linkedMapOf<String, MutableTrend>()
        val yearBuckets = linkedMapOf<String, MutableTrend>()

        fun bucket(map: MutableMap<String, MutableTrend>, key: String, label: String): MutableTrend =
            map.getOrPut(key) { MutableTrend(key, label) }

        for (t in periodTxns) {
            val hasSplits = t.splitLines.isNotEmpty()
            val nonTransferSubs =
                if (hasSplits) t.splitLines.filter { it.transferAccountId == null } else emptyList()

            var txnIn = 0L
            var txnOut = 0L
            if (hasSplits) {
                for (line in nonTransferSubs) {
                    if (line.amountMilli > 0) txnIn += line.amountMilli
                    if (line.amountMilli < 0) txnOut += line.amountMilli
                }
                // Defensive: incomplete split rows in Room must not zero a real parent outflow.
                if (txnIn == 0L && txnOut == 0L) {
                    if (t.amountMilli > 0) txnIn = t.amountMilli
                    if (t.amountMilli < 0) txnOut = t.amountMilli
                }
            } else {
                if (t.amountMilli > 0) txnIn = t.amountMilli
                if (t.amountMilli < 0) txnOut = t.amountMilli
            }
            inflow += txnIn
            outflow += txnOut

            val spendLines =
                if (hasSplits && nonTransferSubs.any { it.amountMilli != 0L }) {
                    nonTransferSubs.filter { it.amountMilli < 0 }
                } else if (t.amountMilli < 0) {
                    listOf(
                        AnalyticsSplitLine(
                            amountMilli = t.amountMilli,
                            categoryId = t.categoryId,
                            categoryGroupId = t.categoryGroupId,
                            payeeId = t.payeeId,
                        ),
                    )
                } else {
                    emptyList()
                }
            for (line in spendLines) {
                val catId = line.categoryId ?: UNCAT
                byCat[catId] = (byCat[catId] ?: 0L) + line.amountMilli
                val gKey = line.categoryGroupId ?: NO_GROUP
                byGroup[gKey] = (byGroup[gKey] ?: 0L) + line.amountMilli
                val payeeId = line.payeeId
                if (payeeId != null) {
                    byPayee[payeeId] = (byPayee[payeeId] ?: 0L) + line.amountMilli
                }
            }

            byAccount[t.accountId] = (byAccount[t.accountId] ?: 0L) + txnIn + txnOut

            val mk = monthKey(t.date)
            val yk = yearKey(t.date)
            val mb = bucket(monthBuckets, mk, formatPeriodLabel(PeriodMode.MONTH, mk))
            val yb = bucket(yearBuckets, yk, yk)
            mb.count += 1
            yb.count += 1
            mb.inflow += txnIn
            yb.inflow += txnIn
            mb.outflow += txnOut
            yb.outflow += txnOut
        }

        val totalOutAbs = kotlin.math.abs(outflow)
        val byCategory =
            rankOutflows(byCat, { id ->
                if (id == UNCAT) "Uncategorized" else categoryNames[id] ?: "Unknown"
            }, totalOutAbs)
        val byGroupRows =
            rankOutflows(byGroup, { id ->
                if (id == NO_GROUP) "No group" else groupNames[id] ?: "Unknown group"
            }, totalOutAbs)
        val byPayeeRows =
            rankOutflows(byPayee, { id -> payeeNames[id] ?: "Unknown" }, totalOutAbs)
                .take(40)
        val byAccountRows =
            byAccount.entries
                .sortedBy { it.value }
                .map { (id, amount) ->
                    RankRow(
                        id = id,
                        name = accountNames[id] ?: "Unknown",
                        amountMilli = amount,
                        share =
                            if (totalOutAbs > 0 && amount < 0) {
                                kotlin.math.abs(amount).toDouble() / totalOutAbs.toDouble()
                            } else {
                                0.0
                            },
                    )
                }

        val monthlyTrend =
            monthBuckets.values
                .sortedBy { it.key }
                .map { it.toPoint() }
        val yearlyTrend =
            yearBuckets.values
                .sortedBy { it.key }
                .map { it.toPoint() }

        val monthsCovered = monthlyTrend.size.coerceAtLeast(if (mode == PeriodMode.MONTH) 1 else 0)
        val avg =
            if (monthsCovered > 0) outflow / monthsCovered else outflow

        return SpendingReport(
            mode = mode,
            periodKey = periodKey,
            periodLabel = bounds.label,
            count = periodTxns.size,
            inflowMilli = inflow,
            outflowMilli = outflow,
            netMilli = inflow + outflow,
            avgMonthlyOutflowMilli = avg,
            monthsCovered = monthsCovered.coerceAtLeast(1),
            byCategory = byCategory,
            byGroup = byGroupRows,
            byPayee = byPayeeRows,
            byAccount = byAccountRows,
            monthlyTrend = monthlyTrend,
            yearlyTrend = yearlyTrend,
        )
    }

    private data class MutableTrend(
        val key: String,
        val label: String,
        var inflow: Long = 0,
        var outflow: Long = 0,
        var count: Int = 0,
    ) {
        fun toPoint() =
            TrendPoint(
                key = key,
                label = label,
                inflowMilli = inflow,
                outflowMilli = outflow,
                netMilli = inflow + outflow,
                count = count,
            )
    }
}
