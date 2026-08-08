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
    ): String? {
        if (!namedPayee.isNullOrBlank()) return namedPayee.trim()
        if (!transferAccountName.isNullOrBlank()) {
            return "Transfer : ${transferAccountName.trim()}"
        }

        val importPayeeName = parseImportPayeeName(importPayeeRaw)
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
        return resolve(
            namedPayee = namedPayee,
            transferAccountName = transferName,
            plaidMerchantName = txn.plaidMerchantName,
            plaidPfc = txn.plaidPfc,
            importPayeeRaw = txn.importPayeeName,
            accounts = accounts,
        )
    }
}
