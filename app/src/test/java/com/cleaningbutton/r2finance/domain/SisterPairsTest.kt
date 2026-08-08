package com.cleaningbutton.r2finance.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private data class FakeTxn(
    val id: String,
    val amount: Long,
    val date: String,
    val transferTxnId: String? = null,
    val altId: String? = null,
)

private val FAKE_ACC = object : SisterTxnAccessors<FakeTxn> {
    override fun id(t: FakeTxn): String = t.id
    override fun altIds(t: FakeTxn): List<String> =
        listOfNotNull(t.altId).filter { it.isNotBlank() }
    override fun amount(t: FakeTxn): Long = t.amount
    override fun date(t: FakeTxn): String = t.date
    override fun transferTransactionId(t: FakeTxn): String? = t.transferTxnId
}

class SisterPairsTest {
    @Test
    fun amount_sisters_cc_payment_and_transfer() {
        // User example: payment −239.35 + Transfer Family Checking +239.35
        val payment = FakeTxn("pay", -239_350L, "2026-08-01")
        val transfer = FakeTxn("xfer", 239_350L, "2026-08-01")
        val noise = FakeTxn("coffee", -4_500L, "2026-08-01")

        val result = findSisterPairsWith(
            listOf(payment, transfer, noise),
            FAKE_ACC,
        )

        assertEquals(1, result.pairs.size)
        assertEquals("pay", result.pairs[0].a.id) // outflow first
        assertEquals("xfer", result.pairs[0].b.id)
        assertEquals(listOf("coffee"), result.unpaired.map { it.id })
        assertEquals(0L, result.pairs[0].a.amount + result.pairs[0].b.amount)
    }

    @Test
    fun linked_transfer_pair_via_transferTransactionId() {
        val a = FakeTxn("a", -50_000L, "2026-07-15", transferTxnId = "b-ynab")
        val b = FakeTxn("b", 50_000L, "2026-07-15", altId = "b-ynab")

        val result = findSisterPairsWith(listOf(a, b), FAKE_ACC)
        assertEquals(1, result.pairs.size)
        assertTrue(result.unpaired.isEmpty())
        assertEquals(setOf("a", "b"), setOf(result.pairs[0].a.id, result.pairs[0].b.id))
    }

    @Test
    fun same_sign_does_not_pair() {
        val a = FakeTxn("a", -10_000L, "2026-08-01")
        val b = FakeTxn("b", -10_000L, "2026-08-01")
        val result = findSisterPairsWith(listOf(a, b), FAKE_ACC)
        assertTrue(result.pairs.isEmpty())
        assertEquals(2, result.unpaired.size)
    }

    @Test
    fun outside_date_window_does_not_pair() {
        val a = FakeTxn("a", -10_000L, "2026-08-01")
        val b = FakeTxn("b", 10_000L, "2026-08-10")
        val result = findSisterPairsWith(listOf(a, b), FAKE_ACC)
        assertTrue(result.pairs.isEmpty())
    }

    @Test
    fun greedy_one_to_one_when_three_match_amount() {
        val a = FakeTxn("out1", -10_000L, "2026-08-01")
        val b = FakeTxn("in1", 10_000L, "2026-08-01")
        val c = FakeTxn("in2", 10_000L, "2026-08-01")
        val result = findSisterPairsWith(listOf(a, b, c), FAKE_ACC)
        assertEquals(1, result.pairs.size)
        assertEquals(1, result.unpaired.size)
    }

    @Test
    fun flatten_orders_pairs_consecutively() {
        val p1a = FakeTxn("p1a", -1_000L, "2026-08-02")
        val p1b = FakeTxn("p1b", 1_000L, "2026-08-02")
        val p2a = FakeTxn("p2a", -2_000L, "2026-08-01")
        val p2b = FakeTxn("p2b", 2_000L, "2026-08-01")
        val result = findSisterPairsWith(listOf(p1a, p1b, p2a, p2b), FAKE_ACC)
        val flat = flattenSisterPairs(result.pairs)
        assertEquals(4, flat.size)
        // Each consecutive duo cancels
        assertEquals(0L, flat[0].amount + flat[1].amount)
        assertEquals(0L, flat[2].amount + flat[3].amount)
        assertTrue(isSisterPairStart(0))
        assertTrue(isSisterPairEnd(1))
    }
}
