package com.cleaningbutton.r2finance.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * YNAB-compatible milliunits: 1 currency unit = 1000 milliunits.
 * Never use floating point for money arithmetic.
 */
object Money {
    const val MILLIUNITS_PER_UNIT = 1000L

    fun fromMajorUnits(amount: BigDecimal): Long =
        amount
            .multiply(BigDecimal(MILLIUNITS_PER_UNIT))
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()

    fun fromMajorUnits(amount: Double): Long =
        fromMajorUnits(BigDecimal.valueOf(amount))

    fun toMajorDecimal(milliunits: Long): BigDecimal =
        BigDecimal(milliunits).divide(BigDecimal(MILLIUNITS_PER_UNIT), 3, RoundingMode.HALF_UP)

    fun format(
        milliunits: Long,
        currencyCode: String = "USD",
        locale: Locale = Locale.US,
    ): String {
        val nf = NumberFormat.getCurrencyInstance(locale)
        runCatching { nf.currency = Currency.getInstance(currencyCode) }
        return nf.format(toMajorDecimal(milliunits))
    }
}
