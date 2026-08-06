package com.cleaningbutton.r2finance.domain

/**
 * Category color helpers for Reflect / Spending Breakdown.
 * Prefer DDB-stored hex on category.color; fallback mirrors R2FinanceAPI.
 * Returns hex strings only (UI converts to Compose Color).
 */
object CategoryColors {
    val PALETTE =
        listOf(
            "#6366F1",
            "#22C55E",
            "#EAB308",
            "#EF4444",
            "#8B5CF6",
            "#A5B4FC",
            "#06B6D4",
            "#F97316",
            "#EC4899",
            "#14B8A6",
            "#3B82F6",
            "#84CC16",
            "#F43F5E",
            "#0EA5E9",
            "#A855F7",
            "#65A30D",
        )

    const val UNCATEGORIZED = "#6366F1"
    const val ALL_OTHERS = "#A5B4FC"
    const val INCOME = "#22C55E"
    const val SPENDING = "#3B82F6"

    fun isHex(v: String?): Boolean = v != null && Regex("^#[0-9A-Fa-f]{6}$").matches(v)

    private fun hashId(id: String): Int {
        var h = 2166136261.toInt()
        for (ch in id) {
            h = h xor ch.code
            h *= 16777619
        }
        return h
    }

    fun colorHex(
        id: String?,
        colorById: Map<String, String>,
        name: String? = null,
    ): String {
        if (id.isNullOrBlank() || id == Analytics.UNCAT) return UNCATEGORIZED
        val existing = colorById[id]
        if (isHex(existing)) return existing!!
        if (name != null && name.contains("uncategor", ignoreCase = true)) return UNCATEGORIZED
        val idx = (hashId(id).toLong() and 0xFFFFFFFFL) % PALETTE.size
        return PALETTE[idx.toInt()]
    }

    data class StackSegment(
        val id: String,
        val name: String,
        val amountMilli: Long,
        val share: Double,
        val colorHex: String,
    )

    fun buildStack(
        rows: List<RankRow>,
        colorById: Map<String, String>,
        topN: Int = 5,
    ): List<StackSegment> {
        val spending = rows.filter { it.amountMilli < 0 }
        val totalAbs = spending.sumOf { kotlin.math.abs(it.amountMilli) }
        if (totalAbs <= 0) return emptyList()
        val top = spending.take(topN)
        val rest = spending.drop(topN)
        val segs =
            top.map { r ->
                StackSegment(
                    id = r.id,
                    name = r.name,
                    amountMilli = r.amountMilli,
                    share = kotlin.math.abs(r.amountMilli).toDouble() / totalAbs.toDouble(),
                    colorHex = colorHex(r.id, colorById, r.name),
                )
            }.toMutableList()
        if (rest.isNotEmpty()) {
            val restAbs = rest.sumOf { kotlin.math.abs(it.amountMilli) }
            segs.add(
                StackSegment(
                    id = "__others",
                    name = "All Others",
                    amountMilli = -restAbs,
                    share = restAbs.toDouble() / totalAbs.toDouble(),
                    colorHex = ALL_OTHERS,
                ),
            )
        }
        return segs
    }
}
