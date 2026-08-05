package com.cleaningbutton.r2finance.domain

/**
 * YNAB-style spending analytics over ledger transactions.
 * Pure functions — no Android / Room dependencies (unit-testable).
 */

enum class PeriodMode {
    MONTH,
    YEAR,
    ALL,
}

data class AnalyticsTxn(
    val date: String,
    val amountMilli: Long,
    val categoryId: String? = null,
    val categoryGroupId: String? = null,
    val payeeId: String? = null,
    val accountId: String,
    val transferAccountId: String? = null,
    /** Optional split outflow lines; when non-empty, used for category/payee attribution. */
    val splitLines: List<AnalyticsSplitLine> = emptyList(),
)

data class AnalyticsSplitLine(
    val amountMilli: Long,
    val categoryId: String? = null,
    val categoryGroupId: String? = null,
    val payeeId: String? = null,
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

    fun listYears(txns: List<AnalyticsTxn>): List<String> =
        txns
            .asSequence()
            .filter { it.transferAccountId == null }
            .map { yearKey(it.date) }
            .toSortedSet()
            .toList()
            .asReversed()

    fun formatPeriodLabel(mode: PeriodMode, key: String): String =
        when (mode) {
            PeriodMode.ALL -> "All time"
            PeriodMode.YEAR -> key
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

    fun defaultPeriodKey(mode: PeriodMode, txns: List<AnalyticsTxn>): String =
        when (mode) {
            PeriodMode.ALL -> "all"
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

    private fun inPeriod(date: String, mode: PeriodMode, key: String): Boolean =
        when (mode) {
            PeriodMode.ALL -> true
            PeriodMode.YEAR -> yearKey(date) == key
            PeriodMode.MONTH -> monthKey(date) == key
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
    ): SpendingReport {
        val periodTxns =
            transactions.filter {
                it.transferAccountId == null && inPeriod(it.date, mode, periodKey)
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
            if (t.amountMilli > 0) inflow += t.amountMilli
            if (t.amountMilli < 0) outflow += t.amountMilli

            if (t.amountMilli < 0) {
                val lines =
                    if (t.splitLines.isNotEmpty()) {
                        t.splitLines.filter { it.amountMilli < 0 }
                    } else {
                        listOf(
                            AnalyticsSplitLine(
                                amountMilli = t.amountMilli,
                                categoryId = t.categoryId,
                                categoryGroupId = t.categoryGroupId,
                                payeeId = t.payeeId,
                            ),
                        )
                    }
                for (line in lines) {
                    val catId = line.categoryId ?: UNCAT
                    byCat[catId] = (byCat[catId] ?: 0L) + line.amountMilli
                    val gKey = line.categoryGroupId ?: NO_GROUP
                    byGroup[gKey] = (byGroup[gKey] ?: 0L) + line.amountMilli
                    val payeeId = line.payeeId
                    if (payeeId != null) {
                        byPayee[payeeId] = (byPayee[payeeId] ?: 0L) + line.amountMilli
                    }
                }
            }

            byAccount[t.accountId] = (byAccount[t.accountId] ?: 0L) + t.amountMilli

            val mk = monthKey(t.date)
            val yk = yearKey(t.date)
            val mb = bucket(monthBuckets, mk, formatPeriodLabel(PeriodMode.MONTH, mk))
            val yb = bucket(yearBuckets, yk, yk)
            mb.count += 1
            yb.count += 1
            if (t.amountMilli > 0) {
                mb.inflow += t.amountMilli
                yb.inflow += t.amountMilli
            } else if (t.amountMilli < 0) {
                mb.outflow += t.amountMilli
                yb.outflow += t.amountMilli
            }
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
            periodLabel = formatPeriodLabel(mode, periodKey),
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
