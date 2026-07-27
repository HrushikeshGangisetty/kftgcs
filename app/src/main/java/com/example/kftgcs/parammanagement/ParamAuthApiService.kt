package com.example.kftgcs.parammanagement

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Auth backend for the Param Management secondary login screen.
 *
 * This is a SEPARATE server from the main `kftgcs.com` API exposed by [com.example.kftgcs.api.ApiService].
 * Base URL: http://13.233.2.207:5000  (HTTP — whitelisted in network_security_config.xml)
 *
 * Endpoints:
 *  - POST /mobile/auth/login    (no auth)  → access + refresh tokens + user profile
 *  - POST /mobile/auth/refresh  (no auth)  → new access token from refresh token
 *  - GET  /mobile/auth/me       (Bearer)   → current user profile
 */
object ParamAuthApiService {

    private const val BASE_URL = "http://13.233.2.207:5000"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ──────────────────────────────────────────────────────────────────
    // POST /mobile/auth/login
    // ──────────────────────────────────────────────────────────────────
    suspend fun login(request: ParamLoginRequest): ParamAuthResult<ParamLoginResponse> =
        withContext(Dispatchers.IO) {
            try {
                val json = gson.toJson(request)
                val maskedJson = json.replace(Regex("\"password\":\"[^\"]*\""), "\"password\":\"***\"")
                Timber.d("ParamAuth → POST $BASE_URL/mobile/auth/login body=$maskedJson")

                val httpReq = Request.Builder()
                    .url("$BASE_URL/mobile/auth/login")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(json.toRequestBody(jsonMediaType))
                    .build()

                val resp = client.newCall(httpReq).execute()
                val body = resp.body?.string() ?: ""
                Timber.d("ParamAuth ← login code=${resp.code} body=${body.take(300)}")

                parseResponse<ParamLoginResponse>(resp.code, body)
            } catch (e: Exception) {
                Timber.e(e, "ParamAuth login network error")
                ParamAuthResult.Error("Network error: ${e.message ?: e.javaClass.simpleName}", 0)
            }
        }

    // ──────────────────────────────────────────────────────────────────
    // POST /mobile/auth/refresh
    // ──────────────────────────────────────────────────────────────────
    suspend fun refresh(request: ParamRefreshRequest): ParamAuthResult<ParamRefreshResponse> =
        withContext(Dispatchers.IO) {
            try {
                val json = gson.toJson(request)
                Timber.d("ParamAuth → POST $BASE_URL/mobile/auth/refresh")

                val httpReq = Request.Builder()
                    .url("$BASE_URL/mobile/auth/refresh")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(json.toRequestBody(jsonMediaType))
                    .build()

                val resp = client.newCall(httpReq).execute()
                val body = resp.body?.string() ?: ""
                Timber.d("ParamAuth ← refresh code=${resp.code}")

                parseResponse<ParamRefreshResponse>(resp.code, body)
            } catch (e: Exception) {
                Timber.e(e, "ParamAuth refresh network error")
                ParamAuthResult.Error("Network error: ${e.message ?: e.javaClass.simpleName}", 0)
            }
        }

    // ──────────────────────────────────────────────────────────────────
    // GET /mobile/auth/me  (Bearer accessToken)
    // ──────────────────────────────────────────────────────────────────
    suspend fun getMe(accessToken: String): ParamAuthResult<ParamUser> =
        withContext(Dispatchers.IO) {
            try {
                Timber.d("ParamAuth → GET $BASE_URL/mobile/auth/me")

                val httpReq = Request.Builder()
                    .url("$BASE_URL/mobile/auth/me")
                    .addHeader("Accept", "application/json")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()

                val resp = client.newCall(httpReq).execute()
                val body = resp.body?.string() ?: ""
                Timber.d("ParamAuth ← me code=${resp.code} body=${body.take(300)}")

                parseResponse<ParamUser>(resp.code, body)
            } catch (e: Exception) {
                Timber.e(e, "ParamAuth getMe network error")
                ParamAuthResult.Error("Network error: ${e.message ?: e.javaClass.simpleName}", 0)
            }
        }

    // ──────────────────────────────────────────────────────────────────
    // Shared response parser
    // ──────────────────────────────────────────────────────────────────
    private inline fun <reified T> parseResponse(code: Int, body: String): ParamAuthResult<T> {
        // Detect HTML error pages early
        if (body.trimStart().startsWith("<") || body.contains("<!DOCTYPE", ignoreCase = true)) {
            Timber.e("ParamAuth received HTML instead of JSON (code=$code)")
            return ParamAuthResult.Error("Server returned an HTML page (status $code)", code)
        }

        return if (code in 200..299) {
            try {
                val parsed = gson.fromJson(body, T::class.java)
                if (parsed == null) ParamAuthResult.Error("Empty response body", code)
                else ParamAuthResult.Success(parsed)
            } catch (e: Exception) {
                Timber.e(e, "ParamAuth failed to parse success body")
                ParamAuthResult.Error("Invalid response format: ${e.message}", code)
            }
        } else {
            // Try to parse a structured error; fall back to body snippet
            val msg = try {
                gson.fromJson(body, ParamAuthErrorResponse::class.java)
                    ?.let { it.message ?: it.error }
                    ?: body.take(200)
            } catch (e: Exception) {
                body.take(200)
            }
            val friendly = when (code) {
                401 -> msg.ifBlank { "Invalid email or password" }
                403 -> msg.ifBlank { "Access forbidden" }
                404 -> msg.ifBlank { "Endpoint not found" }
                in 500..599 -> "Server error ($code): ${msg.ifBlank { "try again later" }}"
                else -> msg.ifBlank { "Request failed ($code)" }
            }
            ParamAuthResult.Error(friendly, code)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// Request / response models
// @SerializedName preserves field names through R8/ProGuard.
// ─────────────────────────────────────────────────────────────────────

data class ParamLoginRequest(
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String
)

data class ParamLoginResponse(
    @SerializedName("accessToken") val accessToken: String,
    @SerializedName("refreshToken") val refreshToken: String,
    @SerializedName("expiresIn") val expiresIn: Long? = null,
    @SerializedName("user") val user: ParamUser? = null
)

data class ParamRefreshRequest(
    @SerializedName("refreshToken") val refreshToken: String
)

data class ParamRefreshResponse(
    @SerializedName("accessToken") val accessToken: String,
    @SerializedName("expiresIn") val expiresIn: Long? = null,
    @SerializedName("refreshToken") val refreshToken: String? = null
)

data class ParamUser(
    @SerializedName("id") val id: String? = null,
    @SerializedName("email") val email: String? = null,
    @SerializedName("fullName") val fullName: String? = null,
    @SerializedName("role") val role: String? = null
)

data class ParamAuthErrorResponse(
    @SerializedName("message") val message: String? = null,
    @SerializedName("error") val error: String? = null,
    @SerializedName("statusCode") val statusCode: Int? = null
)

sealed class ParamAuthResult<out T> {
    data class Success<T>(val data: T) : ParamAuthResult<T>()
    data class Error(val message: String, val statusCode: Int) : ParamAuthResult<Nothing>()
}
