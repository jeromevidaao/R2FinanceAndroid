package com.cleaningbutton.r2finance.data.cloud

import com.cleaningbutton.r2finance.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class CloudPlanResponse(val plan: CloudPlan? = null)

@Serializable
data class CloudPlan(
    val name: String = "Plan",
    val ynabPlanId: String? = null,
    val currency: String = "USD",
    val serverKnowledge: Long = 0,
)

@Serializable
data class CloudAccountsResponse(val accounts: List<CloudAccount> = emptyList())

@Serializable
data class CloudAccount(
    val ynabId: String,
    val name: String,
    val type: String = "checking",
    val balance: Long = 0,
    val onBudget: Boolean = true,
    val closed: Boolean = false,
    val note: String? = null,
    val transferPayeeId: String? = null,
)

@Serializable
data class CloudCategoriesResponse(
    val groups: List<CloudCategoryGroup> = emptyList(),
    val categories: List<CloudCategory> = emptyList(),
)

@Serializable
data class CloudCategoryGroup(
    val ynabId: String,
    val name: String,
    val hidden: Boolean = false,
)

@Serializable
data class CloudCategory(
    val ynabId: String,
    val name: String,
    val categoryGroupId: String? = null,
    val hidden: Boolean = false,
)

@Serializable
data class CloudPayeesResponse(val payees: List<CloudPayee> = emptyList())

@Serializable
data class CloudPayee(
    val ynabId: String,
    val name: String,
    val transferAccountId: String? = null,
)

@Serializable
data class CloudTransactionsResponse(val transactions: List<CloudTransaction> = emptyList())

@Serializable
data class CloudTransaction(
    val ynabId: String,
    val accountId: String,
    val date: String,
    val amount: Long,
    val payeeId: String? = null,
    val categoryId: String? = null,
    val memo: String? = null,
    val cleared: String = "uncleared",
    val approved: Boolean = true,
    val flagColor: String? = null,
    val transferAccountId: String? = null,
    val transferTransactionId: String? = null,
    val importId: String? = null,
    val subtransactions: List<CloudSubTransaction> = emptyList(),
)

@Serializable
data class CloudSubTransaction(
    val ynabId: String? = null,
    val amount: Long = 0,
    val payeeId: String? = null,
    val categoryId: String? = null,
    val memo: String? = null,
)

class CloudApi(
    private val baseUrl: String = BuildConfig.API_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun getPlan(): CloudPlan =
        get("/v1/plan", CloudPlanResponse.serializer()).plan ?: CloudPlan()

    suspend fun getAccounts(): List<CloudAccount> =
        get("/v1/accounts", CloudAccountsResponse.serializer()).accounts

    suspend fun getCategories(): CloudCategoriesResponse =
        get("/v1/categories", CloudCategoriesResponse.serializer())

    suspend fun getPayees(): List<CloudPayee> =
        get("/v1/payees", CloudPayeesResponse.serializer()).payees

    suspend fun getTransactions(): List<CloudTransaction> =
        get("/v1/transactions", CloudTransactionsResponse.serializer()).transactions

    private suspend fun <T> get(
        path: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + path
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error("Cloud API HTTP ${resp.code}: ${body.take(200)}")
            }
            json.decodeFromString(deserializer, body)
        }
    }
}
