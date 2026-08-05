package com.cleaningbutton.r2finance.data.ynab

import com.cleaningbutton.r2finance.domain.AccountType
import com.cleaningbutton.r2finance.domain.ClearedStatus
import com.cleaningbutton.r2finance.domain.FlagColor
import com.cleaningbutton.r2finance.domain.ScheduledFrequency

/** Pure mapping helpers (unit-testable, no Android deps). */
object YnabMapper {
    fun accountType(raw: String): AccountType =
        runCatching { AccountType.valueOf(raw) }.getOrElse {
            when (raw.lowercase()) {
                "creditcard", "credit_card" -> AccountType.creditCard
                "lineofcredit", "line_of_credit" -> AccountType.lineOfCredit
                "otherasset", "other_asset" -> AccountType.otherAsset
                "otherliability", "other_liability" -> AccountType.otherLiability
                "autoloan", "auto_loan" -> AccountType.autoLoan
                "studentloan", "student_loan" -> AccountType.studentLoan
                "personalloan", "personal_loan" -> AccountType.personalLoan
                "medicaldebt", "medical_debt" -> AccountType.medicalDebt
                "otherdebt", "other_debt" -> AccountType.otherDebt
                else -> AccountType.checking
            }
        }

    fun cleared(raw: String?): ClearedStatus =
        when (raw?.lowercase()) {
            "cleared" -> ClearedStatus.cleared
            "reconciled" -> ClearedStatus.reconciled
            else -> ClearedStatus.uncleared
        }

    fun flagColor(raw: String?): FlagColor =
        when (raw?.lowercase()) {
            "red" -> FlagColor.red
            "orange" -> FlagColor.orange
            "yellow" -> FlagColor.yellow
            "green" -> FlagColor.green
            "blue" -> FlagColor.blue
            "purple" -> FlagColor.purple
            else -> FlagColor.none
        }

    fun frequency(raw: String?): ScheduledFrequency =
        runCatching { ScheduledFrequency.valueOf(raw ?: "never") }.getOrDefault(ScheduledFrequency.never)
}
