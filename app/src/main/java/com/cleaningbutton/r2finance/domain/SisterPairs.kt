package com.cleaningbutton.r2finance.domain

import com.cleaningbutton.r2finance.data.repository.TransactionRow
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Sister (offsetting) transaction pairs for Categorization.
 *
 * Examples that cancel each other (net $0):
 * - CC payment −239.35 + Transfer: Family Checking +239.35
 * - Both legs of a YNAB transfer (linked via transferTransactionId)
 *
 * Matching priority:
 * 1. Explicit transfer link (transferTransactionId → other row id / ynabId)
 * 2. Equal opposite amount within a small date window (greedy)
 */
data class SisterPair<T>(
    /** Prefer outflow first, then inflow. */
    val a: T,
    val b: T,
)

data class SisterPairResult<T>(
    val pairs: List<SisterPair<T>>,
    val unpaired: List<T>,
)

/** Max calendar-day gap for amount-based sister matching. */
const val SISTER_DATE_WINDOW_DAYS = 3L

fun dateDiffDays(a: String, b: String): Long {
    return try {
        val da = LocalDate.parse(a.take(10))
        val db = LocalDate.parse(b.take(10))
        abs(ChronoUnit.DAYS.between(da, db))
    } catch (_: Exception) {
        Long.MAX_VALUE / 4
    }
}

interface SisterTxnAccessors<T> {
    /** Primary stable id used for pairing / used-set. */
    fun id(t: T): String
    /**
     * Extra ids that may appear in transferTransactionId
     * (e.g. local id vs ynabId). Primary id is always included.
     */
    fun altIds(t: T): List<String> = emptyList()
    fun amount(t: T): Long
    fun date(t: T): String
    fun transferTransactionId(t: T): String?
}

private fun <T> orderPair(x: T, y: T, amount: (T) -> Long): SisterPair<T> =
    if (amount(x) <= amount(y)) SisterPair(x, y) else SisterPair(y, x)

/**
 * Generic sister-pair finder (shared shape for TransactionRow and tests).
 */
fun <T> findSisterPairsWith(
    items: List<T>,
    acc: SisterTxnAccessors<T>,
    dateWindowDays: Long = SISTER_DATE_WINDOW_DAYS,
): SisterPairResult<T> {
    val byId = LinkedHashMap<String, T>()
    for (t in items) {
        val id = acc.id(t)
        if (id.isNotBlank()) byId[id] = t
        for (alt in acc.altIds(t)) {
            if (alt.isNotBlank() && alt !in byId) byId[alt] = t
        }
    }

    val used = mutableSetOf<String>()
    val pairs = mutableListOf<SisterPair<T>>()

    // 1) Explicit YNAB transfer links (either direction).
    for (t in items) {
        val id = acc.id(t)
        if (id.isBlank() || id in used) continue
        val link = acc.transferTransactionId(t) ?: continue
        val other = byId[link] ?: continue
        val oid = acc.id(other)
        if (oid.isBlank() || oid in used || oid == id) continue
        used += id
        used += oid
        pairs += orderPair(t, other) { acc.amount(it) }
    }

    // 2) Equal opposite amounts within date window (greedy, prefer same day).
    val remaining = items.filter {
        val id = acc.id(it)
        id.isNotBlank() && id !in used
    }.sortedWith(
        compareByDescending<T> { acc.date(it) }
            .thenByDescending { abs(acc.amount(it)) },
    )

    for (i in remaining.indices) {
        val a = remaining[i]
        val aid = acc.id(a)
        if (aid in used) continue
        val amtA = acc.amount(a)
        if (amtA == 0L) continue

        var best: T? = null
        var bestScore = Long.MAX_VALUE

        for (j in (i + 1) until remaining.size) {
            val b = remaining[j]
            val bid = acc.id(b)
            if (bid in used) continue
            if (acc.amount(a) + acc.amount(b) != 0L) continue
            val days = dateDiffDays(acc.date(a), acc.date(b))
            if (days > dateWindowDays) continue
            val score = days * 1000 + j
            if (score < bestScore) {
                bestScore = score
                best = b
            }
        }

        val match = best
        if (match != null) {
            val bid = acc.id(match)
            used += aid
            used += bid
            pairs += orderPair(a, match) { acc.amount(it) }
        }
    }

    // Newest pair first (by later of the two dates).
    pairs.sortWith(
        compareByDescending { p ->
            maxOf(acc.date(p.a), acc.date(p.b))
        },
    )

    val unpaired = items.filter {
        val id = acc.id(it)
        id.isBlank() || id !in used
    }

    return SisterPairResult(pairs = pairs, unpaired = unpaired)
}

private val ROW_ACCESSORS = object : SisterTxnAccessors<TransactionRow> {
    override fun id(t: TransactionRow): String =
        t.txn.ynabId?.takeIf { it.isNotBlank() } ?: t.txn.id

    override fun altIds(t: TransactionRow): List<String> =
        listOfNotNull(t.txn.id, t.txn.ynabId).filter { it.isNotBlank() }

    override fun amount(t: TransactionRow): Long = t.txn.amountMilli

    override fun date(t: TransactionRow): String = t.txn.date

    override fun transferTransactionId(t: TransactionRow): String? =
        t.txn.transferTransactionId
}

fun findSisterPairs(
    items: List<TransactionRow>,
    dateWindowDays: Long = SISTER_DATE_WINDOW_DAYS,
): SisterPairResult<TransactionRow> =
    findSisterPairsWith(items, ROW_ACCESSORS, dateWindowDays)

/**
 * Transfers may be approved from Categorization only when they cancel
 * (net $0) inside the selection — a sister pair (CC payment + transfer,
 * or both transfer legs) or unpaired transfers that themselves sum to 0.
 * Regular non-transfer spend is always approvable.
 */
fun <T> canApproveInboxSelectionWith(
    items: List<T>,
    acc: SisterTxnAccessors<T>,
    isTransfer: (T) -> Boolean,
    dateWindowDays: Long = SISTER_DATE_WINDOW_DAYS,
): Boolean {
    if (items.none(isTransfer)) return true
    val unpairedTransfers = findSisterPairsWith(items, acc, dateWindowDays)
        .unpaired
        .filter(isTransfer)
    return unpairedTransfers.sumOf { acc.amount(it) } == 0L
}

fun canApproveInboxSelection(items: List<TransactionRow>): Boolean =
    canApproveInboxSelectionWith(
        items,
        ROW_ACCESSORS,
        isTransfer = { it.txn.transferAccountId != null },
    )

/** Flatten pairs as [a1,b1,a2,b2,…] for list display. */
fun <T> flattenSisterPairs(pairs: List<SisterPair<T>>): List<T> {
    val out = ArrayList<T>(pairs.size * 2)
    for (p in pairs) {
        out += p.a
        out += p.b
    }
    return out
}

fun isSisterPairStart(index: Int): Boolean = index % 2 == 0

fun isSisterPairEnd(index: Int): Boolean = index % 2 == 1
