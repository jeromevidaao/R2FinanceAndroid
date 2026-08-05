package com.cleaningbutton.r2finance.domain

enum class SyncStatus {
    /** Matches remote (R2FinanceAPI / DynamoDB). */
    SYNCED,

    /** Local change waiting to push to R2FinanceAPI. */
    PENDING_PUSH,

    /** Divergent edits; needs user choice. */
    CONFLICT,

    /** Never intended for cloud (or cloud not configured yet). */
    LOCAL_ONLY,
}
