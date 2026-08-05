package com.cleaningbutton.r2finance.data.auth

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

class AuthException(message: String) : Exception(message)

@Serializable
data class AuthStatusResponse(
    val allowed: Boolean = false,
    val exists: Boolean = false,
    val email: String? = null,
    val mustSetPassword: Boolean = false,
    val mfaEnabled: Boolean = false,
    val ok: Boolean = false,
    val error: String? = null,
)

@Serializable
data class AuthLoginResponse(
    val ok: Boolean = false,
    val next: String? = null,
    val email: String? = null,
    val mfaToken: String? = null,
    val token: String? = null,
    val expiresAt: Long? = null,
    val error: String? = null,
)

@Serializable
data class MfaSetupResponse(
    val secret: String? = null,
    val otpauth: String? = null,
    val error: String? = null,
)

@Serializable
data class ForgotPasswordResponse(
    val ok: Boolean = false,
    val message: String? = null,
    val website: String? = null,
    val error: String? = null,
)

class AuthApi(
    private val baseUrl: String = BuildConfig.API_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val media = "application/json; charset=utf-8".toMediaType()

    suspend fun bootstrap(): AuthStatusResponse =
        post("/v1/auth/bootstrap", "{}", AuthStatusResponse.serializer())

    suspend fun status(email: String): AuthStatusResponse =
        post(
            "/v1/auth/status",
            """{"email":${email.q()}}""",
            AuthStatusResponse.serializer(),
        )

    suspend fun setPassword(email: String, password: String): AuthLoginResponse =
        post(
            "/v1/auth/set-password",
            """{"email":${email.q()},"password":${password.q()}}""",
            AuthLoginResponse.serializer(),
        )

    suspend fun login(email: String, password: String): AuthLoginResponse =
        post(
            "/v1/auth/login",
            """{"email":${email.q()},"password":${password.q()}}""",
            AuthLoginResponse.serializer(),
        )

    suspend fun mfaSetup(email: String, password: String): MfaSetupResponse =
        post(
            "/v1/auth/mfa/setup",
            """{"email":${email.q()},"password":${password.q()}}""",
            MfaSetupResponse.serializer(),
        )

    suspend fun mfaEnable(email: String, password: String, code: String): AuthLoginResponse =
        post(
            "/v1/auth/mfa/enable",
            """{"email":${email.q()},"password":${password.q()},"code":${code.q()}}""",
            AuthLoginResponse.serializer(),
        )

    suspend fun mfaVerify(mfaToken: String, code: String): AuthLoginResponse =
        post(
            "/v1/auth/mfa/verify",
            """{"mfaToken":${mfaToken.q()},"code":${code.q()}}""",
            AuthLoginResponse.serializer(),
        )

    /** Sends email with link to finance.i-liquid.be reset page. */
    suspend fun forgotPassword(email: String): ForgotPasswordResponse =
        post(
            "/v1/auth/forgot-password",
            """{"email":${email.q()}}""",
            ForgotPasswordResponse.serializer(),
        )

    private suspend fun <T> post(
        path: String,
        body: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + path
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody(media))
            .header("Content-Type", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val err = runCatching {
                    json.decodeFromString(AuthLoginResponse.serializer(), text).error
                }.getOrNull()
                throw AuthException(err ?: "HTTP ${resp.code}: ${text.take(200)}")
            }
            json.decodeFromString(deserializer, text)
        }
    }

    private fun String.q(): String = buildString {
        append('"')
        for (c in this@q) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }
}
