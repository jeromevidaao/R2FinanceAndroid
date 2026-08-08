package com.cleaningbutton.r2finance.data

import com.cleaningbutton.r2finance.data.local.entity.TransactionEntity
import com.cleaningbutton.r2finance.domain.ClearedStatus
import com.cleaningbutton.r2finance.domain.FlagColor
import com.cleaningbutton.r2finance.domain.SyncStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushHoldTest {
    @After
    fun tearDown() {
        PushHold.clearAll()
    }

    @Test
    fun hold_and_filter_excludes_held_ids() {
        val a = txn("a")
        val b = txn("b")
        val c = txn("c")
        PushHold.hold(listOf("a", "c"))
        assertTrue(PushHold.isHeld("a"))
        assertFalse(PushHold.isHeld("b"))
        val pushable = PushHold.filterPushable(listOf(a, b, c))
        assertEquals(listOf("b"), pushable.map { it.id })
        PushHold.release(listOf("a"))
        assertFalse(PushHold.isHeld("a"))
        assertEquals(listOf("a", "b"), PushHold.filterPushable(listOf(a, b, c)).map { it.id })
    }

    private fun txn(id: String) = TransactionEntity(
        id = id,
        planId = "default",
        accountId = "acct",
        date = "2026-08-01",
        amountMilli = -1000,
        cleared = ClearedStatus.uncleared,
        approved = false,
        flagColor = FlagColor.none,
        syncStatus = SyncStatus.PENDING_PUSH,
    )
}
