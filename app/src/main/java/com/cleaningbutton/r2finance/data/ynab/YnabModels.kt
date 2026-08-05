package com.cleaningbutton.r2finance.data.ynab

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class YnabPlansResponse(val data: YnabPlansData)

@Serializable
data class YnabPlansData(
    val plans: List<YnabPlanSummary> = emptyList(),
    @SerialName("default_plan") val defaultPlan: YnabPlanSummary? = null,
)

@Serializable
data class YnabPlanSummary(
    val id: String,
    val name: String,
    @SerialName("last_modified_on") val lastModifiedOn: String? = null,
    @SerialName("first_month") val firstMonth: String? = null,
    @SerialName("last_month") val lastMonth: String? = null,
    @SerialName("currency_format") val currencyFormat: YnabCurrencyFormat? = null,
)

@Serializable
data class YnabCurrencyFormat(
    @SerialName("iso_code") val isoCode: String? = null,
)

@Serializable
data class YnabAccountsResponse(val data: YnabAccountsData)

@Serializable
data class YnabAccountsData(
    val accounts: List<YnabAccount> = emptyList(),
    @SerialName("server_knowledge") val serverKnowledge: Long = 0,
)

@Serializable
data class YnabAccount(
    val id: String,
    val name: String,
    val type: String,
    @SerialName("on_budget") val onBudget: Boolean = true,
    val closed: Boolean = false,
    val note: String? = null,
    val balance: Long = 0,
    @SerialName("cleared_balance") val clearedBalance: Long = 0,
    @SerialName("uncleared_balance") val unclearedBalance: Long = 0,
    @SerialName("transfer_payee_id") val transferPayeeId: String? = null,
    val deleted: Boolean = false,
)

@Serializable
data class YnabCategoriesResponse(val data: YnabCategoriesData)

@Serializable
data class YnabCategoriesData(
    @SerialName("category_groups") val categoryGroups: List<YnabCategoryGroup> = emptyList(),
    @SerialName("server_knowledge") val serverKnowledge: Long = 0,
)

@Serializable
data class YnabCategoryGroup(
    val id: String,
    val name: String,
    val hidden: Boolean = false,
    val deleted: Boolean = false,
    val categories: List<YnabCategory> = emptyList(),
)

@Serializable
data class YnabCategory(
    val id: String,
    @SerialName("category_group_id") val categoryGroupId: String,
    val name: String,
    val hidden: Boolean = false,
    val note: String? = null,
    val deleted: Boolean = false,
)

@Serializable
data class YnabPayeesResponse(val data: YnabPayeesData)

@Serializable
data class YnabPayeesData(
    val payees: List<YnabPayee> = emptyList(),
    @SerialName("server_knowledge") val serverKnowledge: Long = 0,
)

@Serializable
data class YnabPayee(
    val id: String,
    val name: String,
    @SerialName("transfer_account_id") val transferAccountId: String? = null,
    val deleted: Boolean = false,
)

@Serializable
data class YnabTransactionsResponse(val data: YnabTransactionsData)

@Serializable
data class YnabTransactionsData(
    val transactions: List<YnabTransaction> = emptyList(),
    @SerialName("server_knowledge") val serverKnowledge: Long = 0,
)

@Serializable
data class YnabTransaction(
    val id: String,
    val date: String,
    val amount: Long,
    val memo: String? = null,
    val cleared: String = "uncleared",
    val approved: Boolean = true,
    @SerialName("flag_color") val flagColor: String? = null,
    @SerialName("account_id") val accountId: String,
    @SerialName("payee_id") val payeeId: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("transfer_account_id") val transferAccountId: String? = null,
    @SerialName("transfer_transaction_id") val transferTransactionId: String? = null,
    @SerialName("matched_transaction_id") val matchedTransactionId: String? = null,
    @SerialName("import_id") val importId: String? = null,
    @SerialName("import_payee_name") val importPayeeName: String? = null,
    @SerialName("import_payee_name_original") val importPayeeNameOriginal: String? = null,
    val deleted: Boolean = false,
    val subtransactions: List<YnabSubTransaction> = emptyList(),
)

@Serializable
data class YnabSubTransaction(
    val id: String,
    @SerialName("transaction_id") val transactionId: String? = null,
    val amount: Long,
    val memo: String? = null,
    @SerialName("payee_id") val payeeId: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("transfer_account_id") val transferAccountId: String? = null,
    @SerialName("transfer_transaction_id") val transferTransactionId: String? = null,
    val deleted: Boolean = false,
)

@Serializable
data class YnabScheduledTransactionsResponse(val data: YnabScheduledData)

@Serializable
data class YnabScheduledData(
    @SerialName("scheduled_transactions") val scheduledTransactions: List<YnabScheduledTransaction> = emptyList(),
    @SerialName("server_knowledge") val serverKnowledge: Long = 0,
)

@Serializable
data class YnabScheduledTransaction(
    val id: String,
    @SerialName("date_first") val dateFirst: String,
    @SerialName("date_next") val dateNext: String,
    val frequency: String = "never",
    val amount: Long,
    val memo: String? = null,
    @SerialName("flag_color") val flagColor: String? = null,
    @SerialName("account_id") val accountId: String,
    @SerialName("payee_id") val payeeId: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("transfer_account_id") val transferAccountId: String? = null,
    val deleted: Boolean = false,
)
