package com.cleaningbutton.r2finance.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class RelativeDateTest {
    private val zone = ZoneId.of("America/New_York")
    private val locale = Locale.US
    /** Saturday 2026-08-08 15:00 Eastern */
    private val now = ZonedDateTime.of(2026, 8, 8, 15, 0, 0, 0, zone)

    @Test
    fun today_yesterday_twoDays() {
        assertEquals(
            "Today",
            RelativeDate.formatCalendarDay(LocalDate.of(2026, 8, 8), LocalDate.of(2026, 8, 8), locale),
        )
        assertEquals(
            "Yesterday",
            RelativeDate.formatCalendarDay(LocalDate.of(2026, 8, 7), LocalDate.of(2026, 8, 8), locale),
        )
        assertEquals(
            "2 days ago",
            RelativeDate.formatCalendarDay(LocalDate.of(2026, 8, 6), LocalDate.of(2026, 8, 8), locale),
        )
    }

    @Test
    fun lastWeekday_withinWeek() {
        // 2026-08-08 is Saturday; 3 days ago = Wednesday
        assertEquals(
            "Last Wednesday",
            RelativeDate.formatCalendarDay(LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 8), locale),
        )
        // 6 days ago = Sunday
        assertEquals(
            "Last Sunday",
            RelativeDate.formatCalendarDay(LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 8), locale),
        )
    }

    @Test
    fun absolute_after_week() {
        assertEquals(
            "Aug 1",
            RelativeDate.formatCalendarDay(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 8), locale),
        )
        assertEquals(
            "Dec 25, 2025",
            RelativeDate.formatCalendarDay(LocalDate.of(2025, 12, 25), LocalDate.of(2026, 8, 8), locale),
        )
    }

    @Test
    fun formatFriendly_dateOnly() {
        assertEquals(
            "Today",
            RelativeDate.formatFriendly("2026-08-08", now = now, zone = zone, locale = locale),
        )
        // Aug 1 is 7 days before Aug 8 Saturday → absolute (after a week)
        assertEquals(
            "Aug 1",
            RelativeDate.formatFriendly("2026-08-01", now = now, zone = zone, locale = locale),
        )
        // Wednesday now → previous Saturday is 4 days ago → "Last Saturday"
        val wed = ZonedDateTime.of(2026, 8, 5, 12, 0, 0, 0, zone)
        assertEquals(
            "Last Saturday",
            RelativeDate.formatFriendly("2026-08-01", now = wed, zone = zone, locale = locale),
        )
    }

    @Test
    fun formatFriendly_hoursAgo() {
        val then = now.minusHours(2).toInstant().toString()
        assertEquals(
            "2h ago",
            RelativeDate.formatFriendly(then, now = now, zone = zone, locale = locale),
        )
        val oneH = now.minusHours(1).toInstant().toString()
        assertEquals(
            "1h ago",
            RelativeDate.formatFriendly(oneH, now = now, zone = zone, locale = locale),
        )
        val mins = now.minusMinutes(15).toInstant().toString()
        assertEquals(
            "15m ago",
            RelativeDate.formatFriendly(mins, now = now, zone = zone, locale = locale),
        )
    }

    @Test
    fun blank_returns_empty() {
        assertEquals("", RelativeDate.formatFriendly(null, now = now, zone = zone, locale = locale))
        assertEquals("", RelativeDate.formatFriendly("  ", now = now, zone = zone, locale = locale))
    }
}
