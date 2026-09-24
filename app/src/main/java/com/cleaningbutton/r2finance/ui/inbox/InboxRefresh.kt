package com.cleaningbutton.r2finance.ui.inbox

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Wall-clock cap so a hung sync.refresh / pullInbox cannot pin the spinner. */
const val INBOX_REFRESH_TIMEOUT_MS = 75_000L

const val INBOX_REFRESH_TIMEOUT_MESSAGE = "Refresh timed out — pull to try again"

/**
 * Whether [PullToRefreshBox] should show its indicator.
 *
 * Silent empty-inbox heal must not drive the spinner once the list already has
 * painted rows (cache or Room). Intentional pull / toolbar refresh always does,
 * including while the list stays painted.
 */
fun inboxPullIndicatorVisible(
    intentionalRefresh: Boolean,
    silentHealRunning: Boolean,
    listHasItems: Boolean,
): Boolean = intentionalRefresh || (silentHealRunning && !listHasItems)

/**
 * Mutable refreshing flag so unit tests do not need Compose state.
 * Production wires [onChange] to Compose `mutableStateOf` for recomposition.
 */
class InboxRefreshingFlag(
    initial: Boolean = false,
    private val onChange: ((Boolean) -> Unit)? = null,
) {
    var value: Boolean = initial
        set(v) {
            field = v
            onChange?.invoke(v)
        }
}

/**
 * Runs inbox network work and always clears [refreshing] — success, failure,
 * timeout, or coroutine cancellation (Compose cancels LaunchedEffect when its
 * keys change before the body reaches a trailing clear).
 *
 * [TimeoutCancellationException] is consumed here (banner via [onTimeoutMessage]).
 * Parent cancellation still clears the flag and rethrows so the caller stops.
 * Other failures propagate after the flag is cleared.
 */
suspend fun runInboxRefresh(
    refreshing: InboxRefreshingFlag,
    timeoutMs: Long = INBOX_REFRESH_TIMEOUT_MS,
    onTimeoutMessage: suspend () -> Unit = {},
    onFinished: suspend () -> Unit = {},
    block: suspend () -> Unit,
) {
    refreshing.value = true
    var timedOut = false
    try {
        try {
            withTimeout(timeoutMs) { block() }
        } catch (_: TimeoutCancellationException) {
            timedOut = true
        }
    } finally {
        // Compose cancellation skips a normal trailing assignment. NonCancellable
        // lets the flag clear even when this coroutine is already cancelled.
        withContext(NonCancellable) {
            if (timedOut) {
                onTimeoutMessage()
            }
            onFinished()
            refreshing.value = false
        }
    }
}
