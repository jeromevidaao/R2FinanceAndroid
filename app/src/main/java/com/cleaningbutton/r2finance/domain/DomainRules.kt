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

    fun isInboxItem(
        approved: Boolean,
        onBudget: Boolean,
        categoryId: String?,
        hasSubtransactions: Boolean,
        isTransfer: Boolean = false,
        categoryName: String? = null,
    ): Boolean {
        if (!approved) return true
        return isUncategorizedOnBudget(
            onBudget = onBudget,
            categoryId = categoryId,
            hasSubtransactions = hasSubtransactions,
            isTransfer = isTransfer,
            categoryName = categoryName,
        )
    }
}
