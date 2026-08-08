package com.cleaningbutton.r2finance.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * User-friendly transaction times for Spending / categorization.
 *
 * - Instant/timestamp: "1h ago", "30m ago", then calendar rules
 * - Date-only (YYYY-MM-DD): Today / Yesterday / N days ago / Last Saturday / then absolute date
 * - After 7 calendar days: "Aug 1" or "Aug 1, 2025" if not this year
 */
object RelativeDate {
    private val dateOnlyFmtSameYear: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val dateOnlyFmtOtherYear: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    /**
     * @param raw ISO date `YYYY-MM-DD`, or full ISO-8601 timestamp
     * @param now clock for tests; default system default zone “now”
     */
    fun formatFriendly(
        raw: String?,
        now: ZonedDateTime = ZonedDateTime.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim()

        // Full timestamps (with time) → minute/hour relative when recent
        parseInstant(trimmed)?.let { then ->
            val thenZ = then.atZone(zone)
            val nowZ = now.withZoneSameInstant(zone)
            val sec = Duration.between(then, nowZ.toInstant()).seconds
            if (sec >= 0 && sec < 24L * 3600) {
                val hours = sec / 3600
                val mins = sec / 60
                return when {
                    sec < 60 -> "just now"
                    mins < 60 -> if (mins == 1L) "1m ago" else "${mins}m ago"
                    hours < 24 -> if (hours == 1L) "1h ago" else "${hours}h ago"
                    else -> formatCalendarDay(thenZ.toLocalDate(), nowZ.toLocalDate(), locale)
                }
            }
            return formatCalendarDay(thenZ.toLocalDate(), nowZ.toLocalDate(), locale)
        }

        // Date-only YYYY-MM-DD
        val date = runCatching { LocalDate.parse(trimmed.take(10)) }.getOrNull()
            ?: return trimmed
        return formatCalendarDay(date, now.withZoneSameInstant(zone).toLocalDate(), locale)
    }

    /**
     * Calendar-day relative labels (used for ledger dates without time-of-day).
     */
    fun formatCalendarDay(
        day: LocalDate,
        today: LocalDate = LocalDate.now(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val days = ChronoUnit.DAYS.between(day, today)
        return when {
            days < 0 -> formatAbsolute(day, today)
            days == 0L -> "Today"
            days == 1L -> "Yesterday"
            days == 2L -> "2 days ago"
            days in 3L..6L -> {
                val weekday = day.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
                "Last $weekday"
            }
            else -> formatAbsolute(day, today)
        }
    }

    private fun formatAbsolute(day: LocalDate, today: LocalDate): String {
        return if (day.year == today.year) {
            dateOnlyFmtSameYear.format(day)
        } else {
            dateOnlyFmtOtherYear.format(day)
        }
    }

    private fun parseInstant(raw: String): Instant? {
        // Reject pure dates so they use calendar-day path (not start-of-day “Nh ago”).
        if (raw.length <= 10 && !raw.contains('T')) return null
        return try {
            Instant.parse(raw)
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(raw).toInstant()
            } catch (_: Exception) {
                try {
                    ZonedDateTime.parse(raw).toInstant()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}
