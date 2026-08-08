package com.cleaningbutton.r2finance.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleaningbutton.r2finance.data.AppContainer
import com.cleaningbutton.r2finance.data.cloud.CloudConnectorAccountPreview
import com.cleaningbutton.r2finance.domain.Money
import java.text.DateFormat
import java.util.Date

/** Positive balances — YNAB-style green for quick scan. */
private val BalancePositive = Color(0xFF3DCC91)
/** Mild red for debt / negative. */
private val BalanceNegative = Color(0xFFFF8A96)

private data class BankBrand(
    val id: String,
    val name: String,
    val short: String,
    val bg: Long,
    val fg: Long,
)

private val BANKS = listOf(
    BankBrand("boa", "Bank of America", "BoA", 0xFFE31837, 0xFFFFFFFF),
    BankBrand("chase", "Chase", "Chase", 0xFF117ACA, 0xFFFFFFFF),
    BankBrand("vanguard", "Vanguard", "VG", 0xFFC41230, 0xFFFFFFFF),
    BankBrand("venmo", "Venmo", "Venmo", 0xFF008CFF, 0xFFFFFFFF),
)

private fun brandFor(connectorId: String?): BankBrand {
    val id = connectorId.orEmpty().lowercase()
    return BANKS.find { it.id == id }
        ?: BankBrand(id.ifBlank { "bank" }, id.ifBlank { "Bank" }, id.take(3).uppercase().ifBlank { "?" }, 0xFF546E7A, 0xFFFFFFFF)
}

private data class ConnectorAccountRow(
    val key: String,
    val ownerEmail: String,
    val connectorId: String,
    val institutionName: String,
    val account: CloudConnectorAccountPreview,
    val amount: Double?,
    val credit: Boolean,
)

/**
 * Accounts — paint from process-scoped [AppContainer.connectorsCache].
 * Tab enter only ensureWarm (no-op when ready). Network = first warm + manual refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    container: AppContainer,
    /** Kept for nav compatibility; Accounts no longer opens YNAB registers. */
    onOpenAccount: (String) -> Unit = {},
) {
    val cache = container.connectorsCache
    val connectors by cache.connectors.collectAsStateWithLifecycle()
    val ready by cache.ready.collectAsStateWithLifecycle()
    val loading by cache.loading.collectAsStateWithLifecycle()
    val refreshing by cache.refreshing.collectAsStateWithLifecycle()
    val error by cache.error.collectAsStateWithLifecycle()
    val statusMessage by cache.statusMessage.collectAsStateWithLifecycle()

    // Paint RAM immediately; first process visit loads once, then no-op.
    LaunchedEffect(Unit) {
        cache.ensureWarm(probeBalancesIfNeeded = true)
    }

    val rows = remember(connectors) {
        val out = mutableListOf<ConnectorAccountRow>()
        for (c in connectors) {
            if (!c.connected) continue
            for (a in c.accountsPreview) {
                out += ConnectorAccountRow(
                    key = "${c.email}-${c.connectorId}-${a.accountId}",
                    ownerEmail = c.email.orEmpty(),
                    connectorId = c.connectorId.orEmpty(),
                    institutionName = c.institutionName
                        ?: c.institution
                        ?: brandFor(c.connectorId).name,
                    account = a,
                    amount = a.displayAmount(),
                    credit = a.isCredit(),
                )
            }
        }
        out.sortedWith(
            compareBy<ConnectorAccountRow> { it.credit }
                .thenBy { it.institutionName.lowercase() }
                .thenBy { it.account.name.lowercase() },
        )
    }

    val assetRows = remember(rows) { rows.filter { !it.credit } }
    val creditRows = remember(rows) { rows.filter { it.credit } }

    val capital = remember(rows) {
        var assets = 0.0
        var creditOwed = 0.0
        var hasAssets = false
        var hasCredit = false
        for (r in rows) {
            val amt = r.amount ?: continue
            if (r.credit) {
                creditOwed += kotlin.math.abs(amt)
                hasCredit = true
            } else {
                assets += amt
                hasAssets = true
            }
        }
        Triple(
            if (hasAssets) assets else null,
            if (hasCredit) creditOwed else null,
            if (hasAssets || hasCredit) {
                (if (hasAssets) assets else 0.0) - (if (hasCredit) creditOwed else 0.0)
            } else {
                null
            },
        )
    }

    val connectedCount = remember(connectors) { connectors.count { it.connected } }
    val lastBalancesAt = remember(connectors) {
        connectors.mapNotNull { it.lastBalancesAt }.maxOrNull()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accounts") },
                actions = {
                    IconButton(
                        enabled = !refreshing && !(loading && !ready),
                        onClick = { cache.refresh(probeBalances = true) },
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh balances")
                    }
                },
            )
        },
    ) { padding ->
        when {
            // Full-screen spinner only before first RAM snapshot.
            !ready && connectors.isEmpty() && (loading || error == null) -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = "Loading connectors…",
                        modifier = Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            !ready && error != null && connectors.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Could not load connectors", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TextButton(
                        onClick = { cache.refresh(probeBalances = false) },
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        Text("Retry")
                    }
                }
            }
            else -> {
                // ready or has cached connectors — always paint list (mid-refresh banner only).
                LazyColumn(
                    modifier = Modifier.padding(padding),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    item(key = "summary") {
                        CapitalSummary(
                            net = capital.third,
                            assets = capital.first,
                            creditOwed = capital.second,
                        )
                    }
                    item(key = "meta") {
                        val asOf = lastBalancesAt?.let {
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(it))
                        }
                        Text(
                            text = buildString {
                                append("$connectedCount connected bank link")
                                if (connectedCount != 1) append('s')
                                if (asOf != null) append(" · balances as of $asOf")
                                else append(" · tap refresh to pull available amounts once")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        if (statusMessage != null) {
                            Text(
                                text = statusMessage.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            )
                        }
                        if (refreshing) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(
                                    text = " Working…",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }

                    if (connectedCount == 0) {
                        item(key = "empty") {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    "No bank connectors yet",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = "Link BoA, Chase, Vanguard, or Venmo on the website " +
                                        "(Connectors). This tab shows available balances from " +
                                        "the connector cache — not the R2Finance ledger. " +
                                        "Plaid is for match + balance refresh; the app only " +
                                        "talks to R2FinanceAPI.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    } else if (rows.isEmpty()) {
                        item(key = "no-balances") {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    "Connected — balances not cached yet",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = "Tap the refresh icon once to pull available amounts " +
                                        "from Plaid into the connector cache. After that, " +
                                        "Accounts loads from the connector only.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                                TextButton(onClick = { cache.refresh(probeBalances = true) }) {
                                    Text("Refresh balances")
                                }
                            }
                        }
                    } else {
                        if (assetRows.isNotEmpty()) {
                            item(key = "hdr-assets") {
                                GroupHeader(
                                    title = "Cash & investments",
                                    totalMilli = sumMilli(assetRows),
                                )
                            }
                            items(assetRows, key = { it.key }) { row ->
                                ConnectorAccountRowUi(row)
                            }
                            item(key = "sp-assets") { Spacer(Modifier.height(12.dp)) }
                        }
                        if (creditRows.isNotEmpty()) {
                            item(key = "hdr-credit") {
                                GroupHeader(
                                    title = "Credit",
                                    totalMilli = sumMilli(creditRows),
                                    asDebt = true,
                                )
                            }
                            items(creditRows, key = { it.key }) { row ->
                                ConnectorAccountRowUi(row)
                            }
                            item(key = "sp-credit") { Spacer(Modifier.height(12.dp)) }
                        }
                    }

                    item(key = "connectors-strip") {
                        Text(
                            text = "CONNECTORS",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                    items(BANKS, key = { "bank-${it.id}" }) { bank ->
                        val linked = connectors.filter {
                            it.connected && it.connectorId.equals(bank.id, ignoreCase = true)
                        }
                        ListItem(
                            leadingContent = {
                                InstitutionIcon(
                                    mark = bank.short,
                                    bg = Color(bank.bg),
                                    fg = Color(bank.fg),
                                )
                            },
                            headlineContent = {
                                Text(bank.name, fontWeight = FontWeight.SemiBold)
                            },
                            supportingContent = {
                                Text(
                                    if (linked.isEmpty()) {
                                        "Not linked"
                                    } else {
                                        linked.joinToString(" · ") { c ->
                                            val who = c.email?.substringBefore('@').orEmpty()
                                            val n = c.accountsPreview.size
                                            buildString {
                                                if (who.isNotEmpty()) append(who)
                                                if (n > 0) {
                                                    if (isNotEmpty()) append(" · ")
                                                    append("$n acct")
                                                }
                                            }
                                        }
                                    },
                                )
                            },
                            trailingContent = {
                                Text(
                                    text = when {
                                        linked.isEmpty() -> "—"
                                        linked.size == 1 -> "Connected"
                                        else -> "${linked.size} linked"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (linked.isNotEmpty()) {
                                        BalancePositive
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                        )
                    }
                    item(key = "footer-sp") { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

private fun sumMilli(rows: List<ConnectorAccountRow>): Long? {
    var sum = 0.0
    var any = false
    for (r in rows) {
        val a = r.amount ?: continue
        sum += kotlin.math.abs(a)
        any = true
    }
    return if (any) Money.fromMajorUnits(sum) else null
}

@Composable
private fun CapitalSummary(
    net: Double?,
    assets: Double?,
    creditOwed: Double?,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "Capital",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = net?.let { Money.format(Money.fromMajorUnits(it)) } ?: "—",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = balanceColor(net?.let { Money.fromMajorUnits(it) } ?: 0L),
        )
        Text(
            text = "Assets − credit owed",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryChip(
                label = "Assets",
                value = assets,
                modifier = Modifier.weight(1f),
            )
            SummaryChip(
                label = "Credit owed",
                value = creditOwed,
                asDebt = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SummaryChip(
    label: String,
    value: Double?,
    modifier: Modifier = Modifier,
    asDebt: Boolean = false,
) {
    val milli = value?.let { Money.fromMajorUnits(it) }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = milli?.let { Money.format(it) } ?: "—",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = when {
                milli == null -> MaterialTheme.colorScheme.onSurfaceVariant
                asDebt -> BalanceNegative
                else -> balanceColor(milli)
            },
        )
    }
}

@Composable
private fun GroupHeader(
    title: String,
    totalMilli: Long?,
    asDebt: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.8.sp,
        )
        Text(
            text = totalMilli?.let { Money.format(it) } ?: "—",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = when {
                totalMilli == null -> MaterialTheme.colorScheme.onSurfaceVariant
                asDebt -> BalanceNegative
                else -> balanceColor(totalMilli)
            },
        )
    }
}

@Composable
private fun ConnectorAccountRowUi(row: ConnectorAccountRow) {
    val brand = brandFor(row.connectorId)
    val milli = row.amount?.let { Money.fromMajorUnits(it) }
    val displayMilli = when {
        milli == null -> null
        row.credit -> -kotlin.math.abs(milli)
        else -> milli
    }
    ListItem(
        leadingContent = {
            InstitutionIcon(
                mark = brand.short,
                bg = Color(brand.bg),
                fg = Color(brand.fg),
            )
        },
        headlineContent = {
            Text(
                text = row.account.name,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            val mask = row.account.mask?.let { "····$it" }
            val owner = row.ownerEmail.substringBefore('@').takeIf { it.isNotBlank() }
            val type = listOfNotNull(row.account.type, row.account.subtype)
                .joinToString(" / ")
                .ifBlank { null }
            Text(
                listOfNotNull(row.institutionName, mask, owner, type).joinToString(" · "),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Text(
                text = displayMilli?.let { Money.format(it) } ?: "—",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    displayMilli == null -> MaterialTheme.colorScheme.onSurfaceVariant
                    row.credit -> BalanceNegative
                    else -> balanceColor(displayMilli)
                },
            )
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    )
}

@Composable
private fun InstitutionIcon(
    mark: String,
    bg: Color,
    fg: Color,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = mark,
            color = fg,
            fontWeight = FontWeight.Bold,
            fontSize = if (mark.length >= 3) 10.sp else 14.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun balanceColor(milli: Long): Color = when {
    milli > 0L -> BalancePositive
    milli < 0L -> BalanceNegative
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
