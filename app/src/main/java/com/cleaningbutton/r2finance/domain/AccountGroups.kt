package com.cleaningbutton.r2finance.domain

/**
 * YNAB-style account grouping + institution branding for the Accounts screen.
 * Sections: Cash · Credit · Tracking (matches YNAB app layout).
 */

enum class AccountGroup(
    val title: String,
    val order: Int,
) {
    Cash("Cash", 0),
    Credit("Credit", 1),
    Tracking("Tracking", 2),
}

private val cashTypes = setOf(
    AccountType.checking,
    AccountType.savings,
    AccountType.cash,
)

private val creditTypes = setOf(
    AccountType.creditCard,
    AccountType.lineOfCredit,
)

fun accountGroup(type: AccountType, onBudget: Boolean): AccountGroup {
    if (onBudget && type in cashTypes) return AccountGroup.Cash
    if (onBudget && type in creditTypes) return AccountGroup.Credit
    return AccountGroup.Tracking
}

enum class InstitutionId {
    Boa,
    Chase,
    Vanguard,
    Amazon,
    GenericCash,
    GenericCredit,
    GenericTracking,
}

data class InstitutionBrand(
    val id: InstitutionId,
    /** Short mark in the icon circle */
    val mark: String,
    val label: String,
    /** ARGB */
    val bg: Long,
    val fg: Long,
)

private val brands = mapOf(
    InstitutionId.Boa to InstitutionBrand(
        id = InstitutionId.Boa,
        mark = "BoA",
        label = "Bank of America",
        bg = 0xFF012169,
        fg = 0xFFE31837,
    ),
    InstitutionId.Chase to InstitutionBrand(
        id = InstitutionId.Chase,
        mark = "C",
        label = "Chase",
        bg = 0xFF117ACA,
        fg = 0xFFFFFFFF,
    ),
    InstitutionId.Vanguard to InstitutionBrand(
        id = InstitutionId.Vanguard,
        mark = "V",
        label = "Vanguard",
        bg = 0xFF96000E,
        fg = 0xFFFFFFFF,
    ),
    InstitutionId.Amazon to InstitutionBrand(
        id = InstitutionId.Amazon,
        mark = "a",
        label = "Amazon",
        bg = 0xFF232F3E,
        fg = 0xFFFF9900,
    ),
    InstitutionId.GenericCash to InstitutionBrand(
        id = InstitutionId.GenericCash,
        mark = "$",
        label = "Cash account",
        bg = 0xFF1F6F4A,
        fg = 0xFFD8F3E4,
    ),
    InstitutionId.GenericCredit to InstitutionBrand(
        id = InstitutionId.GenericCredit,
        mark = "CC",
        label = "Credit card",
        bg = 0xFF3D4A63,
        fg = 0xFFE8EEF6,
    ),
    InstitutionId.GenericTracking to InstitutionBrand(
        id = InstitutionId.GenericTracking,
        mark = "◆",
        label = "Tracking account",
        bg = 0xFF4A3D63,
        fg = 0xFFE8EEF6,
    ),
)

/**
 * Infer institution from account nickname (YNAB does not send brand codes).
 */
fun inferInstitution(
    name: String,
    type: AccountType,
    onBudget: Boolean,
): InstitutionBrand {
    val n = name.lowercase()

    if (
        Regex("""\bboa\b""").containsMatchIn(n) ||
        n.contains("bank of america") ||
        n.contains("checkin")
    ) {
        return brands.getValue(InstitutionId.Boa)
    }

    if (
        n.contains("chase") ||
        n.contains("ink ") ||
        n.startsWith("ink ") ||
        n.contains("freedom") ||
        n.contains("reserve") ||
        n.contains("mai/tri") ||
        n.contains("sapphire")
    ) {
        return brands.getValue(InstitutionId.Chase)
    }

    if (n.contains("vanguard") || Regex("""\b529\b""").containsMatchIn(n)) {
        return brands.getValue(InstitutionId.Vanguard)
    }

    if (
        n.contains("amazon") ||
        n.contains("401") ||
        n.contains("rsu")
    ) {
        return brands.getValue(InstitutionId.Amazon)
    }

    return when (accountGroup(type, onBudget)) {
        AccountGroup.Cash -> brands.getValue(InstitutionId.GenericCash)
        AccountGroup.Credit -> brands.getValue(InstitutionId.GenericCredit)
        AccountGroup.Tracking -> brands.getValue(InstitutionId.GenericTracking)
    }
}

fun accountTypeLabel(type: AccountType): String = when (type) {
    AccountType.checking -> "Checking"
    AccountType.savings -> "Savings"
    AccountType.cash -> "Cash"
    AccountType.creditCard -> "Credit Card"
    AccountType.lineOfCredit -> "Line of Credit"
    AccountType.otherAsset -> "Asset"
    AccountType.otherLiability -> "Liability"
    AccountType.mortgage -> "Mortgage"
    AccountType.autoLoan -> "Auto Loan"
    AccountType.studentLoan -> "Student Loan"
    AccountType.personalLoan -> "Personal Loan"
    AccountType.medicalDebt -> "Medical Debt"
    AccountType.otherDebt -> "Other Debt"
}
