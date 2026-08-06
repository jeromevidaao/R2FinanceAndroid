package com.cleaningbutton.r2finance.domain

/**
 * Core ledger integrity rules (YNAB-compatible behavior without envelope budgeting).
 */
object DomainRules {
    fun splitAmountsValid(parentMilli: Long, subAmounts: List<Long>): Boolean =
        subAmounts.isNotEmpty() && subAmounts.sum() == parentMilli

    fun isUncategorizedOnBudget(
        onBudget: Boolean,
        categoryId: String?,
        hasSubtransactions: Boolean,
        isTransfer: Boolean = false,
        categoryName: String? = null,
    ): Boolean {
        if (!onBudget) return false
        if (hasSubtransactions) return false
        if (isTransfer) return false
        if (categoryId.isNullOrBlank()) return true
        return categoryName?.equals("Uncategorized", ignoreCase = true) == true
    }

    /**
     * Spending list = unapproved only.
     * Approve works without a category; approved rows leave even if uncategorized.
     * Extra params kept for call-site compatibility.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isInboxItem(
        approved: Boolean,
        onBudget: Boolean = true,
        categoryId: String? = null,
        hasSubtransactions: Boolean = false,
        isTransfer: Boolean = false,
        categoryName: String? = null,
    ): Boolean = !approved
}
