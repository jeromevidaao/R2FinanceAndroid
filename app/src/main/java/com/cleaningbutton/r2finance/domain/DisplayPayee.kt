package com.cleaningbutton.r2finance.domain

import com.cleaningbutton.r2finance.data.local.entity.AccountEntity
import com.cleaningbutton.r2finance.data.local.entity.TransactionEntity
import org.json.JSONObject

/**
 * Display payee for ledger rows — bank imports often arrive with empty
 * payeeId but a clear Plaid / import description.
 *
 * Credit-card payments follow reconciliation practice:
 *   "Payment for credit Family Reserve (ending 8053)"
 */
object DisplayPayee {

    fun parseImportPayeeName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        if (s.startsWith("{")) {
            return runCatching {
                val j = JSONObject(s)
                sequenceOf("importedPayee", "import_payee_name", "payee")
                    .mapNotNull { key ->
                        j.optString(key, "").trim().takeIf { it.isNotEmpty() }
                    }
                    .firstOrNull()
            }.getOrNull()
        }
        return s
    }

    fun extractCardEnding(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val patterns = listOf(
            Regex("""ending\s*(?:in\s*)?#?(\d{4})\b""", RegexOption.IGNORE_CASE),
            Regex("""\bcard\s+#?(\d{4})\b""", RegexOption.IGNORE_CASE),
            Regex("""\*{2,}(\d{4})\b"""),
        )
        for (re in patterns) {
            val m = re.find(text) ?: continue
            return m.groupValues[1]
        }
        return null
    }

    private fun isCreditPaymentHint(
        plaidMerchantName: String?,
        plaidPfc: String?,
        importPayeeName: String?,
    ): Boolean {
        val pfc = (plaidPfc ?: "").uppercase()
        if (
            pfc.contains("LOAN_PAYMENT") ||
            pfc.contains("CREDIT_CARD_PAYMENT") ||
            pfc == "LOAN_PAYMENTS"
        ) {
            return true
        }
        val blob = "${plaidMerchantName.orEmpty()} ${importPayeeName.orEmpty()}".lowercase()
        return Regex("""payment\s+to\s+.*card""").containsMatchIn(blob) ||
            Regex("""credit\s*card""").containsMatchIn(blob) ||
            blob.contains("autopay") ||
            Regex("""payment\s+thank\s+you""").containsMatchIn(blob) ||
            Regex("""card\s+payment""").containsMatchIn(blob)
    }

    private fun findCreditAccountByEnding(
        accounts: List<AccountEntity>,
        ending: String,
    ): AccountEntity? {
        val creditTypes = setOf(AccountType.creditCard, AccountType.lineOfCredit)
        return accounts.firstOrNull { a ->
            !a.closed && a.type in creditTypes && a.name.contains(ending)
        }
    }

    private fun accountBaseName(name: String, ending: String): String {
        val n = name.trim().replace(Regex("""\s*$ending\s*$"""), "").trim()
        return n.ifEmpty { name.trim() }
    }

    fun formatCreditPaymentPayee(ending: String, accounts: List<AccountEntity>): String {
        val acct = findCreditAccountByEnding(accounts, ending)
        if (acct != null) {
            val base = accountBaseName(acct.name, ending)
            return "Payment for credit $base (ending $ending)"
        }
        return "Payment for credit card (ending $ending)"
    }

    fun cleanPlaidMerchantName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        var s = name.trim()
        s = s.replace(Regex("""\s+\d{1,2}/\d{1,2}(?:/\d{2,4})?\s*$"""), "").trim()
        s = s.replace(Regex("""\s+\d{4}-\d{2}-\d{2}\s*$"""), "").trim()
        if (s == s.uppercase() && s.any { it.isLetter() } && s.length > 3) {
            s = s.lowercase().split(Regex("""\s+""")).joinToString(" ") { w ->
                if (w.length <= 2) w.uppercase()
                else w.replaceFirstChar { it.titlecase() }
            }
        }
        return s.ifEmpty { null }
    }

    fun isGenericVenmoPayee(text: String?): Boolean {
        val s = text?.trim().orEmpty()
        if (s.isEmpty()) return false
        if (s.equals("venmo", ignoreCase = true)) return true
        if (
            s.startsWith("venmo", ignoreCase = true) &&
            Regex("""payment|cashout|des:|web id|ppd|orig""", RegexOption.IGNORE_CASE)
                .containsMatchIn(s)
        ) {
            return true
        }
        return false
    }

    private fun looksLikeVenmoPersonalDesc(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || isGenericVenmoPayee(t)) return false
        if (Regex("""^(.+?)\s+["“](.+?)["”]\s*$""").containsMatchIn(t)) return true
        // Title Case person - note (not ALL-CAPS merchant - CITY)
        if (Regex("""^[A-Z][a-z]+(?:\s+[A-Z][a-z'.-]+)+\s-\s\S""").containsMatchIn(t)) {
            return true
        }
        if (Regex("""standard\s+transfer""", RegexOption.IGNORE_CASE).containsMatchIn(t)) {
            return true
        }
        // Bare Venmo notes ("Fire wood") — reject ALL-CAPS merchant labels
        if (t.length <= 80 && t.any { it.isLowerCase() } &&
            !Regex("""^[A-Z0-9\s#&.'/-]+$""").matches(t)
        ) {
            return true
        }
        return false
    }

    /**
     * Venmo Personal note for UI: "Person - note".
     * Only Venmo-style labels (quoted note or stamped "Name - note").
     */
    fun venmoDescriptionLabel(
        plaidDescription: String?,
        plaidName: String?,
        plaidMerchantName: String?,
    ): String? {
        val d = plaidDescription?.trim()
        if (!d.isNullOrEmpty() && looksLikeVenmoPersonalDesc(d)) return d
        for (raw in listOf(plaidName, plaidMerchantName)) {
            if (raw.isNullOrBlank() || isGenericVenmoPayee(raw)) continue
            val s = raw.trim()
            val m = Regex("""^(.+?)\s+["“](.+?)["”]\s*$""").find(s)
            if (m != null) {
                return "${m.groupValues[1].trim()} - ${m.groupValues[2].trim()}"
            }
            if (Regex("""\s-\s""").containsMatchIn(s) && !s.startsWith("venmo", ignoreCase = true)) {
                return s
            }
        }
        return null
    }

    /**
     * @return human payee or null when nothing known (UI shows "No payee" / "—")
     */
    fun resolve(
        namedPayee: String?,
        transferAccountName: String?,
        plaidMerchantName: String?,
        plaidPfc: String?,
        importPayeeRaw: String?,
        accounts: List<AccountEntity>,
        plaidName: String? = null,
        plaidDescription: String? = null,
    ): String? {
        val named = namedPayee?.trim()?.takeIf { it.isNotEmpty() }
        val venmoLabel = venmoDescriptionLabel(plaidDescription, plaidName, plaidMerchantName)
        if (named != null && isGenericVenmoPayee(named) && venmoLabel != null) {
            return venmoLabel
        }
        if (named != null) return named
        if (!transferAccountName.isNullOrBlank()) {
            return "Transfer : ${transferAccountName.trim()}"
        }

        val importPayeeName = parseImportPayeeName(importPayeeRaw)
        if (isGenericVenmoPayee(importPayeeName) && venmoLabel != null) {
            return venmoLabel
        }
        val ending = extractCardEnding(plaidMerchantName)
            ?: extractCardEnding(importPayeeName)

        if (
            ending != null &&
            isCreditPaymentHint(plaidMerchantName, plaidPfc, importPayeeName)
        ) {
            return formatCreditPaymentPayee(ending, accounts)
        }

        val paymentBlob = "${plaidMerchantName.orEmpty()} ${importPayeeName.orEmpty()}"
        if (ending != null && paymentBlob.contains("payment", ignoreCase = true)) {
            return formatCreditPaymentPayee(ending, accounts)
        }

        if (venmoLabel != null) return venmoLabel
        cleanPlaidMerchantName(plaidMerchantName)?.let { return it }
        if (!importPayeeName.isNullOrBlank()) return importPayeeName
        return null
    }

    fun resolveForTxn(
        txn: TransactionEntity,
        namedPayee: String?,
        accounts: List<AccountEntity>,
    ): String? {
        val transferName = txn.transferAccountId?.let { tid ->
            accounts.firstOrNull { it.id == tid }?.name
        }
        val base = resolve(
            namedPayee = namedPayee,
            transferAccountName = transferName,
            plaidMerchantName = txn.plaidMerchantName,
            plaidPfc = txn.plaidPfc,
            importPayeeRaw = txn.importPayeeName,
            accounts = accounts,
            plaidName = txn.plaidName,
            plaidDescription = txn.plaidDescription,
        )
        return enhanceAmazon(base, txn)
    }

    /** Append matched Amazon item titles to the payee label. */
    fun enhanceAmazon(base: String?, txn: TransactionEntity): String? {
        val summary = txn.amazonItemsSummary?.trim()?.takeIf { it.isNotEmpty() }
            ?: txn.amazonItemsJoined
                ?.split("|")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.take(3)
                ?.joinToString(", ")
                ?.takeIf { it.isNotEmpty() }
        if (summary.isNullOrBlank()) return base
        val label = base?.trim()?.takeIf { it.isNotEmpty() } ?: "Amazon"
        if (label.contains(summary)) return label
        if (label.contains(" — ") && label.contains("amazon", ignoreCase = true)) return label
        return "$label — $summary"
    }
}
