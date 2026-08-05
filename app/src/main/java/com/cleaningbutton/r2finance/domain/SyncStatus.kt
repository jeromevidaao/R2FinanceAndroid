package com.cleaningbutton.r2finance.domain

enum class SyncStatus {
    /** Matches remote (DDB and/or YNAB). */
    SYNCED,

    /** Local change waiting to push to R2FinanceAPI / YNAB. */
    PENDING_PUSH,

    /** Divergent edits; needs user choice. */
    CONFLICT,

    /** Never intended for cloud (or cloud not configured yet). */
    LOCAL_ONLY,
}
