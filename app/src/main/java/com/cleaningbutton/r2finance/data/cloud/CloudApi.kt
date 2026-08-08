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
    /** Soft-delete tombstone from server (delta sync). */
    val deleted: Boolean = false,
    val updatedAt: Long = 0,
    val subtransactions: List<CloudSubTransaction> = emptyList(),
    /** Present on /v1/inbox only. */
    val accountName: String? = null,
    val payeeName: String? = null,
    val reason: String? = null,
    val onBudget: Boolean? = null,
    /** Plaid enrichment from cloud (optional). */
    val plaidTransactionId: String? = null,
    val plaidMerchantName: String? = null,
    val plaidPaymentChannel: String? = null,
    val plaidPfc: String? = null,
    /** "City, ST" (US) or "City, Country" (intl). */
    val locationDisplay: String? = null,
    val locationSource: String? = null,
) {
    /** Room / local primary key preference. */
    fun stableId(): String = id?.takeIf { it.isNotBlank() }
        ?: clientId?.takeIf { it.isNotBlank() }
        ?: ynabId
}

@Serializable
data class CloudSyncChangesResponse(
    val mode: String = "full",
    val serverTime: Long = 0,
    val cursor: Long = 0,
    val since: Long = 0,
    val plan: CloudPlan? = null,
    val accounts: List<CloudAccountDelta> = emptyList(),
    val groups: List<CloudCategoryGroupDelta> = emptyList(),
    val categories: List<CloudCategoryDelta> = emptyList(),
    val payees: List<CloudPayeeDelta> = emptyList(),
    val transactions: List<CloudTransaction> = emptyList(),
    val counts: CloudSyncCounts? = null,
    /** True when more transaction pages remain — do not advance local cursor yet. */
    val hasMore: Boolean = false,
    val txnOffset: Int = 0,
    val nextTxnOffset: Int = 0,
    val txnLimit: Int = 0,
    val txnTotal: Int = 0,
)

@Serializable
data class CloudSyncCounts(
    val accounts: Int = 0,
    val groups: Int = 0,
    val categories: Int = 0,
    val payees: Int = 0,
    val transactions: Int = 0,
    val txnTotal: Int = 0,
)

@Serializable
data class CloudAccountDelta(
    val ynabId: String,
    val name: String,
    val type: String = "checking",
    val balance: Long = 0,
    val onBudget: Boolean = true,
    val closed: Boolean = false,
    val note: String? = null,
    val transferPayeeId: String? = null,
    val deleted: Boolean = false,
    val updatedAt: Long = 0,
)

@Serializable
data class CloudCategoryGroupDelta(
    val ynabId: String,
    val name: String,
    val hidden: Boolean = false,
    val deleted: Boolean = false,
    val updatedAt: Long = 0,
)

@Serializable
data class CloudCategoryDelta(
    val ynabId: String,
    val name: String,
    val categoryGroupId: String? = null,
    val hidden: Boolean = false,
    val color: String? = null,
    val deleted: Boolean = false,
    val updatedAt: Long = 0,
)

@Serializable
data class CloudPayeeDelta(
    val ynabId: String,
    val name: String,
    val transferAccountId: String? = null,
    val deleted: Boolean = false,
    val updatedAt: Long = 0,
)

@Serializable
data class CloudSubTransaction(
    val ynabId: String? = null,
    val amount: Long = 0,
    val payeeId: String? = null,
    val categoryId: String? = null,
    val memo: String? = null,
    val transferAccountId: String? = null,
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

@Serializable
data class CloudConnectorsResponse(
    val email: String? = null,
    val connectors: List<CloudConnectorStatus> = emptyList(),
)

@Serializable
data class CloudHouseholdConnectorsResponse(
    val requester: String? = null,
    val users: List<CloudHouseholdUserConnectors> = emptyList(),
)

@Serializable
data class CloudHouseholdUserConnectors(
    val email: String = "",
    val connectors: List<CloudConnectorStatus> = emptyList(),
)

@Serializable
data class CloudConnectorStatus(
    val connectorId: String? = null,
    val email: String? = null,
    val provider: String = "plaid",
    val institution: String? = null,
    val institutionName: String? = null,
    val configured: Boolean = false,
    val connected: Boolean = false,
    val itemId: String? = null,
    val connectedAt: Long? = null,
    val accountCount: Int? = null,
    val accountsPreview: List<CloudConnectorAccountPreview> = emptyList(),
    val lastBalancesAt: Long? = null,
)

@Serializable
data class CloudConnectorAccountPreview(
    val accountId: String = "",
    val name: String = "",
    val officialName: String? = null,
    val mask: String? = null,
    val type: String? = null,
    val subtype: String? = null,
    val available: Double? = null,
    val current: Double? = null,
    val limit: Double? = null,
    val isoCurrencyCode: String? = null,
) {
    /** Prefer Plaid available; fall back to current. */
    fun displayAmount(): Double? = available ?: current

    fun isCredit(): Boolean {
        val t = type.orEmpty().lowercase()
        val s = subtype.orEmpty().lowercase()
        return t == "credit" || s == "credit card" || s == "paypal"
    }
}

@Serializable
data class CloudRefreshBalancesResponse(
    val ok: Boolean = false,
    val email: String? = null,
    val refreshedAt: Long? = null,
    val results: List<CloudRefreshBalanceResult> = emptyList(),
)

@Serializable
data class CloudRefreshBalanceResult(
    val connectorId: String = "",
    val ok: Boolean? = null,
    val skipped: Boolean? = null,
    val reason: String? = null,
    val accountCount: Int? = null,
    val lastBalancesAt: Long? = null,
    val error: String? = null,
)

@Serializable
data class CloudConnectorAccountsResponse(
    val ok: Boolean = false,
    val connectorId: String? = null,
    val email: String? = null,
    val institutionName: String? = null,
    val itemId: String? = null,
    val connected: Boolean? = null,
    val accounts: List<CloudConnectorLiveAccount> = emptyList(),
    val accountsPreview: List<CloudConnectorAccountPreview> = emptyList(),
    val lastBalancesAt: Long? = null,
    val source: String? = null,
)

@Serializable
data class CloudConnectorLiveAccount(
    val accountId: String = "",
    val name: String = "",
    val officialName: String? = null,
    val mask: String? = null,
    val type: String? = null,
    val subtype: String? = null,
    val balances: CloudConnectorBalances = CloudConnectorBalances(),
)

@Serializable
data class CloudConnectorBalances(
    val available: Double? = null,
    val current: Double? = null,
    val limit: Double? = null,
    val isoCurrencyCode: String? = null,
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

    /**
     * Local-first sync: full snapshot (`since=0` / `full=1`) or incremental
     * changes since the last server cursor (includes deleted tombstones).
     * Transactions may be paged via [txnOffset] / [CloudSyncChangesResponse.hasMore].
     */
    suspend fun getSyncChanges(
        since: Long = 0L,
        full: Boolean = false,
        txnOffset: Int = 0,
    ): CloudSyncChangesResponse {
        val q = buildString {
            append("/v1/sync/changes?")
            if (full || since <= 0L) {
                append("full=1")
            } else {
                append("since=").append(since)
            }
            if (txnOffset > 0) {
                append("&txnOffset=").append(txnOffset)
            }
        }
        return get(q, CloudSyncChangesResponse.serializer())
    }

    /**
     * Fetch all transaction pages and merge into one pack. Full snapshots with
     * ~7k+ rows exceed the Lambda 6MB response limit in a single request.
     */
    suspend fun getSyncChangesAll(
        since: Long = 0L,
        full: Boolean = false,
    ): CloudSyncChangesResponse {
        var txnOffset = 0
        var first: CloudSyncChangesResponse? = null
        val allTxns = ArrayList<CloudTransaction>()
        // Safety: 40 × 2500 = 100k rows — well above the household ledger.
        repeat(40) {
            val pack = getSyncChanges(since = since, full = full, txnOffset = txnOffset)
            if (first == null) first = pack
            allTxns.addAll(pack.transactions)
            if (!pack.hasMore) {
                val base = first!!
                return base.copy(
                    plan = base.plan ?: pack.plan,
                    accounts = base.accounts.ifEmpty { pack.accounts },
                    groups = base.groups.ifEmpty { pack.groups },
                    categories = base.categories.ifEmpty { pack.categories },
                    payees = base.payees.ifEmpty { pack.payees },
                    transactions = allTxns,
                    hasMore = false,
                    txnOffset = 0,
                    nextTxnOffset = allTxns.size,
                    txnTotal = if (pack.txnTotal > 0) pack.txnTotal else allTxns.size,
                    cursor = when {
                        pack.cursor > 0L -> pack.cursor
                        pack.serverTime > 0L -> pack.serverTime
                        else -> base.cursor
                    },
                    serverTime = if (pack.serverTime > 0L) pack.serverTime else base.serverTime,
                    mode = pack.mode.ifBlank { base.mode },
                    counts = CloudSyncCounts(
                        accounts = base.counts?.accounts ?: base.accounts.size,
                        groups = base.counts?.groups ?: base.groups.size,
                        categories = base.counts?.categories ?: base.categories.size,
                        payees = base.counts?.payees ?: base.payees.size,
                        transactions = allTxns.size,
                        txnTotal = if (pack.txnTotal > 0) pack.txnTotal else allTxns.size,
                    ),
                )
            }
            txnOffset = if (pack.nextTxnOffset > 0) pack.nextTxnOffset else allTxns.size
        }
        error("sync/changes pagination exceeded max pages")
    }

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

    // ── Bank connectors (cached balances for Accounts) ────────────────

    /** Connectors for the signed-in email (includes accountsPreview + balances). */
    suspend fun getConnectors(): CloudConnectorsResponse =
        get("/v1/connectors", CloudConnectorsResponse.serializer())

    /** All household members × banks with accountsPreview when present. */
    suspend fun getHouseholdConnectors(): CloudHouseholdConnectorsResponse =
        get("/v1/connectors?household=1", CloudHouseholdConnectorsResponse.serializer())

    /**
     * Live Plaid accounts/get for every connected bank → writes balances
     * onto CONNECTOR meta. Accounts UI then reloads the cache.
     */
    suspend fun refreshConnectorBalances(): CloudRefreshBalancesResponse =
        post(
            "/v1/connectors/refresh-balances",
            "{}",
            CloudRefreshBalancesResponse.serializer(),
        )

    /** Single bank accounts. Default cache; [live]=true probes Plaid. */
    suspend fun getConnectorAccounts(bankId: String, live: Boolean = false): CloudConnectorAccountsResponse {
        val q = if (live) "?live=1" else ""
        return get(
            "/v1/connectors/$bankId/accounts$q",
            CloudConnectorAccountsResponse.serializer(),
        )
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
