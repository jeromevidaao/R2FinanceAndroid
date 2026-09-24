package com.cleaningbutton.r2finance.ui.inbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxRefreshTest {
    @Test
    fun pull_indicator_hidden_when_silent_heal_and_list_painted() {
        assertFalse(
            inboxPullIndicatorVisible(
                intentionalRefresh = false,
                silentHealRunning = true,
                listHasItems = true,
            ),
        )
    }

    @Test
    fun pull_indicator_shown_for_intentional_refresh_even_with_rows() {
        assertTrue(
            inboxPullIndicatorVisible(
                intentionalRefresh = true,
                silentHealRunning = false,
                listHasItems = true,
            ),
        )
    }

    @Test
    fun pull_indicator_shown_for_empty_silent_heal() {
        assertTrue(
            inboxPullIndicatorVisible(
                intentionalRefresh = false,
                silentHealRunning = true,
                listHasItems = false,
            ),
        )
    }

    @Test
    fun pull_indicator_hidden_when_idle() {
        assertFalse(
            inboxPullIndicatorVisible(
                intentionalRefresh = false,
                silentHealRunning = false,
                listHasItems = true,
            ),
        )
    }

    @Test
    fun refresh_clears_flag_on_success() = runTest {
        val flag = mutableFlag(true)
        var ran = false
        runInboxRefresh(flag, timeoutMs = 5_000L) { ran = true }
        assertTrue(ran)
        assertFalse(flag.value)
    }

    @Test
    fun refresh_clears_flag_on_failure() = runTest {
        val flag = mutableFlag(false)
        val failed = runCatching {
            runInboxRefresh(flag, timeoutMs = 5_000L) {
                error("sync down")
            }
        }
        assertTrue(failed.isFailure)
        assertEquals("sync down", failed.exceptionOrNull()?.message)
        assertFalse(flag.value)
    }

    @Test
    fun refresh_runs_on_finished_when_parent_cancels() = runTest {
        val flag = mutableFlag(false)
        var finished = false
        val started = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            runInboxRefresh(
                refreshing = flag,
                timeoutMs = 60_000L,
                onFinished = { finished = true },
            ) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        job.cancel()
        job.join()
        assertTrue(finished)
        assertFalse(flag.value)
    }

    @Test
    fun refresh_clears_flag_when_parent_cancels() = runTest {
        val flag = mutableFlag(false)
        val started = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            runInboxRefresh(flag, timeoutMs = 60_000L) {
                started.complete(Unit)
                // Park until the parent job is cancelled (mirrors LaunchedEffect cancel).
                awaitCancellation()
            }
        }
        started.await()
        assertTrue(flag.value)
        job.cancel()
        job.join()
        assertFalse(
            "cancel before trailing clear must not leave refreshing stuck",
            flag.value,
        )
    }

    @Test
    fun refresh_timeout_sets_message_and_clears_flag() = runTest {
        val flag = mutableFlag(false)
        var message: String? = null
        val result = runCatching {
            runInboxRefresh(
                refreshing = flag,
                timeoutMs = 50L,
                onTimeoutMessage = { message = INBOX_REFRESH_TIMEOUT_MESSAGE },
            ) {
                // delay advances runTest virtual time so withTimeout can fire.
                delay(10_000L)
            }
        }
        // Timeout is consumed so a hung heal cannot escape as CancellationException
        // and skip a caller's own finally (silentHealRunning).
        assertTrue(result.isSuccess)
        assertEquals(INBOX_REFRESH_TIMEOUT_MESSAGE, message)
        assertFalse(flag.value)
    }

    private fun mutableFlag(initial: Boolean): InboxRefreshingFlag =
        InboxRefreshingFlag(initial = initial)
}
