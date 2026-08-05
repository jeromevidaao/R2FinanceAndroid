package com.cleaningbutton.r2finance.domain

/** Mirrors YNAB account types (OpenAPI AccountType). */
enum class AccountType {
    checking,
    savings,
    cash,
    creditCard,
    lineOfCredit,
    otherAsset,
    otherLiability,
    mortgage,
    autoLoan,
    studentLoan,
    personalLoan,
    medicalDebt,
    otherDebt,
}

enum class ClearedStatus {
    uncleared,
    cleared,
    reconciled,
}

enum class FlagColor {
    red,
    orange,
    yellow,
    green,
    blue,
    purple,
    none,
}

enum class ScheduledFrequency {
    never,
    daily,
    weekly,
    everyOtherWeek,
    twiceAMonth,
    every4Weeks,
    monthly,
    everyOtherMonth,
    every3Months,
    every4Months,
    twiceAYear,
    yearly,
    everyOtherYear,
}
