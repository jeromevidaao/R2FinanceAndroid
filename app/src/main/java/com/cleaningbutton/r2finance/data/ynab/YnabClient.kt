package com.cleaningbutton.r2finance.data.ynab

import com.cleaningbutton.r2finance.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class YnabException(message: String, val httpCode: Int? = null) : Exception(message)

/**
 * Minimal YNAB REST client (Bearer PAT). Rate limit: 200 req/hour.
 */
class YnabClient(
    private val tokenProvider: () -> String?,
    private val baseUrl: String = BuildConfig.YNAB_API_BASE,
    private val client: OkHttpClient = defaultClient(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun listPlans(): List<YnabPlanSummary> = get("/plans", YnabPlansResponse.serializer()).data.plans

    suspend fun listAccounts(planId: String): Pair<List<YnabAccount>, Long> {
        val data = get("/plans/$planId/accounts", YnabAccountsResponse.serializer()).data
        return data.accounts to data.serverKnowledge
    }

    suspend fun listCategories(planId: String): Pair<List<YnabCategoryGroup>, Long> {
        val data = get("/plans/$planId/categories", YnabCategoriesResponse.serializer()).data
        return data.categoryGroups to data.serverKnowledge
    }

    suspend fun listPayees(planId: String): Pair<List<YnabPayee>, Long> {
        val data = get("/plans/$planId/payees", YnabPayeesResponse.serializer()).data
        return data.payees to data.serverKnowledge
    }

    /**
     * @param sinceDate ISO date; pass early date for full history (API defaults to ~1 year).
     */
    suspend fun listTransactions(
        planId: String,
        sinceDate: String = "1990-01-01",
    ): Pair<List<YnabTransaction>, Long> {
        val path = "/plans/$planId/transactions?since_date=$sinceDate"
        val data = get(path, YnabTransactionsResponse.serializer()).data
        return data.transactions to data.serverKnowledge
    }

    suspend fun listScheduled(planId: String): Pair<List<YnabScheduledTransaction>, Long> {
        val data = get(
            "/plans/$planId/scheduled_transactions",
            YnabScheduledTransactionsResponse.serializer(),
        ).data
        return data.scheduledTransactions to data.serverKnowledge
    }

    private suspend fun <T> get(
        path: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T = withContext(Dispatchers.IO) {
        val token = tokenProvider()?.trim().orEmpty()
        if (token.isEmpty()) throw YnabException("YNAB personal access token not set")

        val url = baseUrl.trimEnd('/') + path
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw YnabException(
                    "YNAB HTTP ${resp.code}: ${body.take(300)}",
                    httpCode = resp.code,
                )
            }
            json.decodeFromString(deserializer, body)
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
    }
}
