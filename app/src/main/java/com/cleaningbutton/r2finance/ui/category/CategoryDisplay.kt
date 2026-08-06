package com.cleaningbutton.r2finance.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleaningbutton.r2finance.data.repository.TransactionRow

enum class CategoryChipKind {
    Needed,
    Category,
    Inflow,
    Transfer,
}

data class CategoryChipModel(
    val label: String,
    val kind: CategoryChipKind,
    val icon: String,
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
    IconRule(Regex("travel|hotel|flight|airline|vacation|airbnb", RegexOption.IGNORE_CASE), "✈️"),
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

fun iconForCategoryName(name: String?, groupName: String? = null): String {
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

fun categoryChipForRow(
    row: TransactionRow,
    groupName: String? = null,
): CategoryChipModel {
    val txn = row.txn
    if (txn.transferAccountId != null) {
        return CategoryChipModel("Transfer", CategoryChipKind.Transfer, "↔️")
    }
    val name = row.categoryName
    if (name.isNullOrBlank() || name.equals("Uncategorized", ignoreCase = true)) {
        return CategoryChipModel("Category Needed", CategoryChipKind.Needed, "⚠️")
    }
    if (name.contains("Credit Card Payment", ignoreCase = true)) {
        return CategoryChipModel("Credit Card Payment", CategoryChipKind.Category, "💳")
    }
    val treatAsInflow =
        isInflowCategoryName(name, groupName) ||
            (txn.amountMilli > 0 &&
                !Regex("expense|spend|bills|monthly|yearly|debt", RegexOption.IGNORE_CASE)
                    .containsMatchIn(groupName.orEmpty()))
    return CategoryChipModel(
        label = name,
        kind = if (treatAsInflow) CategoryChipKind.Inflow else CategoryChipKind.Category,
        icon = iconForCategoryName(name, groupName),
    )
}

fun categoryChipForCategory(name: String, groupName: String? = null): CategoryChipModel {
    val inflow = isInflowCategoryName(name, groupName)
    return CategoryChipModel(
        label = name,
        kind = if (inflow) CategoryChipKind.Inflow else CategoryChipKind.Category,
        icon = iconForCategoryName(name, groupName),
    )
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
    }
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = modifier
            .background(bg, shape)
            .border(1.dp, border, shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = model.icon,
            fontSize = 12.sp,
            modifier = Modifier.padding(end = 4.dp),
        )
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
