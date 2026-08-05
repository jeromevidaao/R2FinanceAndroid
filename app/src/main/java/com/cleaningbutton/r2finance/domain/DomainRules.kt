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
    ): Boolean {
        if (!onBudget) return false
        if (hasSubtransactions) return false
        return categoryId.isNullOrBlank()
    }

    fun isInboxItem(
        approved: Boolean,
        onBudget: Boolean,
        categoryId: String?,
        hasSubtransactions: Boolean,
    ): Boolean {
        if (!approved) return true
        return isUncategorizedOnBudget(onBudget, categoryId, hasSubtransactions)
    }
}
