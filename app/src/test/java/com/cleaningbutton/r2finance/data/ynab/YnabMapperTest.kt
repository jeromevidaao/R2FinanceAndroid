package com.cleaningbutton.r2finance.data.ynab

import com.cleaningbutton.r2finance.domain.AccountType
import com.cleaningbutton.r2finance.domain.ClearedStatus
import com.cleaningbutton.r2finance.domain.FlagColor
import com.cleaningbutton.r2finance.domain.ScheduledFrequency
import org.junit.Assert.assertEquals
import org.junit.Test

class YnabMapperTest {
    @Test
    fun accountTypes() {
        assertEquals(AccountType.checking, YnabMapper.accountType("checking"))
        assertEquals(AccountType.creditCard, YnabMapper.accountType("creditCard"))
        assertEquals(AccountType.creditCard, YnabMapper.accountType("credit_card"))
        assertEquals(AccountType.mortgage, YnabMapper.accountType("mortgage"))
    }

    @Test
    fun clearedAndFlags() {
        assertEquals(ClearedStatus.cleared, YnabMapper.cleared("cleared"))
        assertEquals(ClearedStatus.reconciled, YnabMapper.cleared("reconciled"))
        assertEquals(ClearedStatus.uncleared, YnabMapper.cleared(null))
        assertEquals(FlagColor.red, YnabMapper.flagColor("red"))
        assertEquals(FlagColor.none, YnabMapper.flagColor(null))
    }

    @Test
    fun frequency() {
        assertEquals(ScheduledFrequency.monthly, YnabMapper.frequency("monthly"))
        assertEquals(ScheduledFrequency.never, YnabMapper.frequency("nope"))
    }
}
