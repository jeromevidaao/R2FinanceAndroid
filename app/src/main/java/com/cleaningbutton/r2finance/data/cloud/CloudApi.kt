package com.cleaningbutton.r2finance.data.cloud

import com.cleaningbutton.r2finance.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    /** Hex color from DDB (Reflect / charts). */
    val color: String? = null,
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
    /** Stable client key (device clientId or YNAB id). */
    val id: String? = null,
    val clientId: String? = null,
    val ynabId: String = "",
    val accountId: String = "",
    val date: String = "",
    val amount: Long = 0,
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
    /** Present on /v1/inbox only. */
    val accountName: String? = null,
    val payeeName: String? = null,
    val reason: String? = null,
    val onBudget: Boolean? = null,
) {
    /** Room / local primary key preference. */
    fun stableId(): String = id?.takeIf { it.isNotBlank() }
        ?: clientId?.takeIf { it.isNotBlank() }
        ?: ynabId
}

@Serializable
data class CloudSubTransaction(
    val ynabId: String? = null,
    val amount: Long = 0,
    val payeeId: String? = null,
    val categoryId: String? = null,
    val memo: String? = null,
)

@Serializable
data class CloudInboxResponse(
    val count: Int = 0,
    val unapproved: Int = 0,
    val uncategorized: Int = 0,
    val transactions: List<CloudTransaction> = emptyList(),
)

@Serializable
data class CloudMutationResponse(
    val marked: CloudMarked? = null,
    val push: CloudPushResult? = null,
    val error: String? = null,
)

@Serializable
data class CloudMarked(
    val ok: Boolean = false,
    val ynabTxnId: String? = null,
    val categoryYnabId: String? = null,
    val approved: Boolean? = null,
)

@Serializable
data class CloudPushResult(
    val pushed: Int = 0,
    val failed: Int = 0,
    val error: String? = null,
)

@Serializable
data class CloudSyncTickResponse(
    val pull: CloudPullResult? = null,
    val push: CloudPushResult? = null,
)

@Serializable
data class CloudPullResult(
    val mode: String? = null,
    val itemsUpserted: Int = 0,
    val serverKnowledge: Long = 0,
)

@Serializable
private data class CategorizeRequest(
    val ynabTxnId: String,
    val categoryYnabId: String,
    val approved: Boolean = true,
    val push: Boolean = true,
)

@Serializable
private data class ApproveRequest(
    val ynabTxnId: String,
    val push: Boolean = true,
)

@Serializable
data class DevicePushPayee(
    val clientId: String,
    val name: String,
    val ynabId: String? = null,
    val updatedAt: Long? = null,
    val deleted: Boolean = false,
)

@Serializable
data class DevicePushTransaction(
    val clientId: String,
    val ynabId: String? = null,
    val accountId: String,
    val date: String,
    val amount: Long,
    val payeeId: String? = null,
    val payeeName: String? = null,
    val categoryId: String? = null,
    val memo: String? = null,
    val cleared: String = "uncleared",
    val approved: Boolean = true,
    val deleted: Boolean = false,
    val importId: String? = null,
    val updatedAt: Long? = null,
)

@Serializable
data class DevicePushRequest(
    val payees: List<DevicePushPayee> = emptyList(),
    val transactions: List<DevicePushTransaction> = emptyList(),
)

@Serializable
data class DevicePushResponse(
    val ok: Boolean = false,
    val accepted: DevicePushAccepted? = null,
    val failed: DevicePushAccepted? = null,
    val error: String? = null,
)

@Serializable
data class DevicePushAccepted(
    val payees: Int = 0,
    val transactions: Int = 0,
)

/**
 * Authenticated R2Finance cloud client.
 *
 * Every ledger/sync call requires a valid household session (Jerome or Ngoc).
 * Pass [tokenProvider] so requests include `Authorization: Bearer …`.
 */
class CloudApi(
    private val baseUrl: String = BuildConfig.API_BASE_URL,
    private val tokenProvider: () -> String? = { null },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mediaJson = "application/json; charset=utf-8".toMediaType()

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

    suspend fun getInbox(): CloudInboxResponse =
        get("/v1/inbox", CloudInboxResponse.serializer())

    /** Pull YNAB → DDB then push pending DDB → YNAB. */
    suspend fun syncTick(): CloudSyncTickResponse =
        post("/v1/sync/tick", "{}", CloudSyncTickResponse.serializer())

    suspend fun categorize(
        ynabTxnId: String,
        categoryYnabId: String,
        approved: Boolean = true,
        push: Boolean = true,
    ): CloudMutationResponse {
        val payload = json.encodeToString(
            CategorizeRequest.serializer(),
            CategorizeRequest(ynabTxnId, categoryYnabId, approved, push),
        )
        return post("/v1/transactions/categorize", payload, CloudMutationResponse.serializer())
    }

    suspend fun approve(ynabTxnId: String, push: Boolean = true): CloudMutationResponse {
        val payload = json.encodeToString(
            ApproveRequest.serializer(),
            ApproveRequest(ynabTxnId, push),
        )
        return post("/v1/transactions/approve", payload, CloudMutationResponse.serializer())
    }

    /**
     * Land phone Room PENDING_PUSH rows into DynamoDB.
     * Does **not** wait for YNAB — backend tick/schedule pushes later.
     */
    suspend fun devicePush(request: DevicePushRequest): DevicePushResponse {
        val payload = json.encodeToString(DevicePushRequest.serializer(), request)
        return post("/v1/device/push", payload, DevicePushResponse.serializer())
    }

    private fun Request.Builder.applyAuth(): Request.Builder {
        header("X-R2Finance-Client", "android")
        val token = tokenProvider()?.trim().orEmpty()
        if (token.isNotEmpty()) {
            header("Authorization", "Bearer $token")
        }
        return this
    }

    private suspend fun <T> get(
        path: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + path
        val req = Request.Builder().url(url).get().applyAuth().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error("Cloud API HTTP ${resp.code}: ${body.take(200)}")
            }
            json.decodeFromString(deserializer, body)
        }
    }

    private suspend fun <T> post(
        path: String,
        jsonBody: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + path
        val req = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(mediaJson))
            .applyAuth()
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error("Cloud API HTTP ${resp.code}: ${body.take(200)}")
            }
            json.decodeFromString(deserializer, body)
        }
    }
}
