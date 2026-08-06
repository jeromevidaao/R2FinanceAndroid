package com.cleaningbutton.r2finance.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleaningbutton.r2finance.R
import com.cleaningbutton.r2finance.data.repository.TransactionRow
import com.cleaningbutton.r2finance.domain.CategoryColors

enum class CategoryChipKind {
    Needed,
    Category,
    Inflow,
    Transfer,
    Airbnb,
}

enum class BrandIcon {
    None,
    Airbnb,
}

data class CategoryChipModel(
    val label: String,
    val kind: CategoryChipKind,
    val icon: String,
    val brandIcon: BrandIcon = BrandIcon.None,
    val railColorHex: String,
)

private data class IconRule(val pattern: Regex, val icon: String)

private val ICON_RULES = listOf(
    IconRule(Regex("grocer|food|supermarket|market", RegexOption.IGNORE_CASE), "🛒"),
    IconRule(Regex("restaurant|dining|coffee|cafe|takeout|fast.?food", RegexOption.IGNORE_CASE), "🍽️"),
    IconRule(Regex("gas|fuel|parking|auto|car |vehicle|uber|lyft|transit|transport", RegexOption.IGNORE_CASE), "⛽"),
    IconRule(Regex("rent|mortgage|housing|hoa|home|apartment", RegexOption.IGNORE_CASE), "🏠"),
    IconRule(Regex("utilit|electric|water|gas bill|internet|phone|cable", RegexOption.IGNORE_CASE), "💡"),
    IconRule(Regex("income|salary|paycheck|payroll|wages|deposit|interest|dividend|refund", RegexOption.IGNORE_CASE), "💰"),
    IconRule(Regex("inflow|ready to assign|to be budgeted", RegexOption.IGNORE_CASE), "💵"),
    IconRule(Regex("medical|health|doctor|dental|pharmacy|hospital", RegexOption.IGNORE_CASE), "🏥"),
    IconRule(Regex("entertain|movie|music|game|hobby|netflix|spotify", RegexOption.IGNORE_CASE), "🎬"),
    IconRule(Regex("shop|amazon|clothing|clothes|retail", RegexOption.IGNORE_CASE), "🛍️"),
    IconRule(Regex("travel|hotel|flight|airline|vacation", RegexOption.IGNORE_CASE), "✈️"),
    IconRule(Regex("educat|tuition|school|student|books", RegexOption.IGNORE_CASE), "📚"),
    IconRule(Regex("insur", RegexOption.IGNORE_CASE), "🛡️"),
    IconRule(Regex("credit card payment|cc payment", RegexOption.IGNORE_CASE), "💳"),
    IconRule(Regex("transfer", RegexOption.IGNORE_CASE), "↔️"),
    IconRule(Regex("subscri|software|saas|app store", RegexOption.IGNORE_CASE), "📱"),
    IconRule(Regex("pet|vet|dog|cat", RegexOption.IGNORE_CASE), "🐾"),
    IconRule(Regex("gift|donation|charit", RegexOption.IGNORE_CASE), "🎁"),
    IconRule(Regex("personal|care|hair|spa|gym|fitness", RegexOption.IGNORE_CASE), "💅"),
    IconRule(Regex("tax", RegexOption.IGNORE_CASE), "🧾"),
    IconRule(Regex("child|kids|baby", RegexOption.IGNORE_CASE), "🧸"),
    IconRule(Regex("savings|invest", RegexOption.IGNORE_CASE), "📈"),
)

const val AIRBNB_COLOR = "#FF5A5F"
const val NEEDED_COLOR = "#F59E0B"
const val TRANSFER_COLOR = "#A78BFA"

fun isAirbnbName(name: String?, groupName: String? = null): Boolean {
    val hay = listOfNotNull(name, groupName).joinToString(" ").lowercase()
    return hay.contains("airbnb")
}

fun iconForCategoryName(name: String?, groupName: String? = null): String {
    if (isAirbnbName(name, groupName)) return ""
    val hay = listOfNotNull(name, groupName).joinToString(" ").trim()
    if (hay.isEmpty()) return "⚠️"
    for (rule in ICON_RULES) {
        if (rule.pattern.containsMatchIn(hay)) return rule.icon
    }
    return "🏷️"
}

fun isInflowCategoryName(categoryName: String?, groupName: String? = null): Boolean {
    val g = groupName.orEmpty().lowercase()
    val c = categoryName.orEmpty().lowercase()
    if (c.isEmpty() && g.isEmpty()) return false
    if (g.contains("inflow") || g.contains("income")) return true
    if (c.contains("income") || c.contains("inflow")) return true
    if (c.contains("salary") || c.contains("paycheck") || c.contains("payroll")) return true
    if (c.contains("ready to assign") || c.contains("to be budgeted")) return true
    if (c.contains("interest") || c.contains("dividend")) return true
    return false
}

fun railColorHex(
    categoryId: String?,
    categoryName: String?,
    groupName: String?,
    storedColor: String?,
    isTransfer: Boolean = false,
    amountMilli: Long = 0L,
): String {
    if (isTransfer) return TRANSFER_COLOR
    if (categoryId.isNullOrBlank() || categoryName.isNullOrBlank() ||
        categoryName.equals("Uncategorized", ignoreCase = true)
    ) {
        return NEEDED_COLOR
    }
    if (isAirbnbName(categoryName, groupName)) return AIRBNB_COLOR
    if (CategoryColors.isHex(storedColor)) return storedColor!!
    if (isInflowCategoryName(categoryName, groupName)) return CategoryColors.INCOME
    if (amountMilli > 0 &&
        !Regex("expense|spend|bills|monthly|yearly|debt", RegexOption.IGNORE_CASE)
            .containsMatchIn(groupName.orEmpty())
    ) {
        return CategoryColors.INCOME
    }
    return CategoryColors.colorHex(categoryId, emptyMap(), categoryName)
}

fun categoryChipForRow(
    row: TransactionRow,
    groupName: String? = null,
): CategoryChipModel {
    val txn = row.txn
    if (txn.transferAccountId != null) {
        return CategoryChipModel(
            label = "Transfer",
            kind = CategoryChipKind.Transfer,
            icon = "↔️",
            railColorHex = TRANSFER_COLOR,
        )
    }
    val name = row.categoryName
    if (name.isNullOrBlank() || name.equals("Uncategorized", ignoreCase = true)) {
        return CategoryChipModel(
            label = "Category Needed",
            kind = CategoryChipKind.Needed,
            icon = "⚠️",
            railColorHex = NEEDED_COLOR,
        )
    }
    val gName = groupName ?: row.categoryGroupName
    if (isAirbnbName(name, gName)) {
        return CategoryChipModel(
            label = name,
            kind = CategoryChipKind.Airbnb,
            icon = "",
            brandIcon = BrandIcon.Airbnb,
            railColorHex = AIRBNB_COLOR,
        )
    }
    if (name.contains("Credit Card Payment", ignoreCase = true)) {
        return CategoryChipModel(
            label = "Credit Card Payment",
            kind = CategoryChipKind.Category,
            icon = "💳",
            railColorHex = railColorHex(
                txn.categoryId,
                name,
                gName,
                row.categoryColor,
                amountMilli = txn.amountMilli,
            ),
        )
    }
    val treatAsInflow =
        isInflowCategoryName(name, gName) ||
            (txn.amountMilli > 0 &&
                !Regex("expense|spend|bills|monthly|yearly|debt", RegexOption.IGNORE_CASE)
                    .containsMatchIn(gName.orEmpty()))
    val rail = railColorHex(
        txn.categoryId,
        name,
        gName,
        row.categoryColor,
        amountMilli = txn.amountMilli,
    )
    return CategoryChipModel(
        label = name,
        kind = if (treatAsInflow) CategoryChipKind.Inflow else CategoryChipKind.Category,
        icon = iconForCategoryName(name, gName),
        railColorHex = if (treatAsInflow) CategoryColors.INCOME else rail,
    )
}

fun categoryChipForCategory(
    name: String,
    groupName: String? = null,
    categoryId: String? = null,
    storedColor: String? = null,
): CategoryChipModel {
    if (isAirbnbName(name, groupName)) {
        return CategoryChipModel(
            label = name,
            kind = CategoryChipKind.Airbnb,
            icon = "",
            brandIcon = BrandIcon.Airbnb,
            railColorHex = AIRBNB_COLOR,
        )
    }
    val inflow = isInflowCategoryName(name, groupName)
    return CategoryChipModel(
        label = name,
        kind = if (inflow) CategoryChipKind.Inflow else CategoryChipKind.Category,
        icon = iconForCategoryName(name, groupName),
        railColorHex = railColorHex(categoryId ?: name, name, groupName, storedColor),
    )
}

/** Stable group key for Spending list (same category together). */
fun inboxGroupKey(row: TransactionRow): String {
    val txn = row.txn
    if (txn.transferAccountId != null) return "__transfer:${txn.transferAccountId}"
    if (txn.categoryId.isNullOrBlank() ||
        row.categoryName.isNullOrBlank() ||
        row.categoryName.equals("Uncategorized", ignoreCase = true)
    ) {
        return "__needed"
    }
    return txn.categoryId!!
}

data class InboxCategoryGroup(
    val key: String,
    val label: String,
    val chip: CategoryChipModel,
    val railColorHex: String,
    val rows: List<TransactionRow>,
)

/**
 * Group inbox rows by category for bulk approve.
 * Order: Category Needed → named categories (A–Z) → transfers.
 * Within each group: newest date first.
 */
fun groupInboxByCategory(rows: List<TransactionRow>): List<InboxCategoryGroup> {
    val map = rows.groupBy { inboxGroupKey(it) }
    val groups = map.map { (key, list) ->
        val sorted = list.sortedByDescending { it.txn.date }
        val sample = sorted.first()
        val chip = categoryChipForRow(sample, sample.categoryGroupName)
        InboxCategoryGroup(
            key = key,
            label = chip.label,
            chip = chip,
            railColorHex = chip.railColorHex,
            rows = sorted,
        )
    }
    return groups.sortedWith(
        compareBy<InboxCategoryGroup> {
            when {
                it.key == "__needed" -> 0
                it.key.startsWith("__transfer") -> 2
                else -> 1
            }
        }.thenBy { it.label.lowercase() },
    )
}

fun parseHexColor(hex: String, alpha: Float = 1f): Color {
    val clean = hex.removePrefix("#")
    val c = when (clean.length) {
        6 -> clean.toLong(16) or 0xFF000000L
        8 -> clean.toLong(16)
        else -> 0xFF6366F1L
    }
    val base = Color(c.toInt())
    return if (alpha >= 0.999f) base else base.copy(alpha = alpha)
}

private val NeededBg = Color(0xFFFDE68A)
private val NeededFg = Color(0xFF1A1A1A)
private val NeededBorder = Color(0xFFF59E0B)

private val CategoryBg = Color(0x3860A5FA)
private val CategoryFg = Color(0xFFBFDBFE)
private val CategoryBorder = Color(0x6660A5FA)

private val InflowBg = Color(0x383DCC91)
private val InflowFg = Color(0xFFA7F3D0)
private val InflowBorder = Color(0x733DCC91)

private val TransferBg = Color(0x2EA78BFA)
private val TransferFg = Color(0xFFDDD6FE)
private val TransferBorder = Color(0x59A78BFA)

private val AirbnbBg = Color(0x2EFF5A5F)
private val AirbnbFg = Color(0xFFFFB4B8)
private val AirbnbBorder = Color(0x80FF5A5F)
private val AirbnbAccent = Color(0xFFFF5A5F)

@Composable
fun CategoryChip(
    model: CategoryChipModel,
    modifier: Modifier = Modifier,
) {
    val (bg, fg, border) = when (model.kind) {
        CategoryChipKind.Needed -> Triple(NeededBg, NeededFg, NeededBorder)
        CategoryChipKind.Category -> Triple(CategoryBg, CategoryFg, CategoryBorder)
        CategoryChipKind.Inflow -> Triple(InflowBg, InflowFg, InflowBorder)
        CategoryChipKind.Transfer -> Triple(TransferBg, TransferFg, TransferBorder)
        CategoryChipKind.Airbnb -> Triple(AirbnbBg, AirbnbFg, AirbnbBorder)
    }
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = modifier
            .background(bg, shape)
            .border(1.dp, border, shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (model.brandIcon) {
            BrandIcon.Airbnb -> {
                Icon(
                    painter = painterResource(R.drawable.ic_airbnb),
                    contentDescription = null,
                    tint = AirbnbAccent,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(14.dp),
                )
            }
            BrandIcon.None -> {
                if (model.icon.isNotEmpty()) {
                    Text(
                        text = model.icon,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
        }
        Text(
            text = model.label,
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
