package com.example.kftgcs.api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit

object ApiService {

    // ===========================================
    // SECURITY: Server configuration
    // In production, these values are loaded from BuildConfig (set in build.gradle.kts)
    // BuildConfig values are injected at build time from local.properties
    // ===========================================

    /**
     * Server configuration helper object
     * Attempts to read from BuildConfig first, falls back to defaults for development
     */
    private object ServerConfig {
        // Default values for production (using domain)
        private const val DEFAULT_SERVER_IP = "kftgcs.com"
        private const val DEFAULT_SERVER_PORT = "443"
        // SECURITY: Use HTTPS for secure communication
        private const val DEFAULT_API_URL = "https://kftgcs.com"

        val apiBaseUrl: String
            get() = tryGetBuildConfigString("API_BASE_URL") ?: DEFAULT_API_URL

        val serverIp: String
            get() = tryGetBuildConfigString("SERVER_IP") ?: DEFAULT_SERVER_IP

        val serverPort: String
            get() = tryGetBuildConfigString("SERVER_PORT") ?: DEFAULT_SERVER_PORT

        val useProductionServer: Boolean
            get() = tryGetBuildConfigBoolean("USE_PRODUCTION_SERVER") ?: false

        /**
         * Try to get a String field from BuildConfig using reflection
         * Returns null if BuildConfig is not available
         */
        private fun tryGetBuildConfigString(fieldName: String): String? {
            return try {
                val buildConfigClass = Class.forName("com.example.kftgcs.BuildConfig")
                val field = buildConfigClass.getField(fieldName)
                field.get(null) as? String
            } catch (e: Exception) {
                Timber.w("BuildConfig.$fieldName not available, using default")
                null
            }
        }

        /**
         * Try to get a Boolean field from BuildConfig using reflection
         * Returns null if BuildConfig is not available
         */
        private fun tryGetBuildConfigBoolean(fieldName: String): Boolean? {
            return try {
                val buildConfigClass = Class.forName("com.example.kftgcs.BuildConfig")
                val field = buildConfigClass.getField(fieldName)
                field.get(null) as? Boolean
            } catch (e: Exception) {
                Timber.w("BuildConfig.$fieldName not available, using default")
                null
            }
        }
    }

    // ===========================================
    // BASE URL CONFIGURATION
    // ===========================================

    // Compute BASE_URL once at initialization
    private val BASE_URL: String by lazy {
        val url = when {
            // If using production server (release builds)
            ServerConfig.useProductionServer -> ServerConfig.apiBaseUrl

            // Development: use DEBUG_API_URL from local.properties (supports http for local server)
            else -> ServerConfig.apiBaseUrl
        }
        Timber.d("BASE_URL selected: $url (useProduction=${ServerConfig.useProductionServer})")
        url
    }

    // ===========================================
    // SECURITY: Certificate Pinning Configuration
    // ===========================================

    /**
     * Certificate pinner for production HTTPS connections.
     * This prevents man-in-the-middle attacks by validating server certificates.
     *
     * IMPORTANT: Update these pins when server certificates are rotated.
     * To get the SHA256 pin for your server certificate:
     * 1. Run: openssl s_client -connect api.yourserver.com:443 | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64
     * 2. Or use https://www.ssllabs.com/ssltest/ to analyze your server
     *
     * Include at least 2 pins (primary + backup) to prevent lockouts during certificate rotation.
     *
     * SECURITY WARNING: The placeholder pins below MUST be replaced with real certificate
     * pins before deploying to production. Placeholder pins will cause connection failures
     * in production and log security warnings.
     */
    // Placeholder indicator - set to false once real pins are configured
    private const val CERTIFICATE_PINS_CONFIGURED = false

    // Primary certificate pin (current certificate) - REPLACE WITH REAL PIN
    private const val PRIMARY_CERTIFICATE_PIN = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    // Backup certificate pin (next certificate or CA intermediate) - REPLACE WITH REAL PIN
    private const val BACKUP_CERTIFICATE_PIN = "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="

    private val certificatePinner: CertificatePinner by lazy {
        if (!CERTIFICATE_PINS_CONFIGURED) {
            Timber.w("SECURITY WARNING: Certificate pins are not configured! " +
                    "Replace placeholder pins with real certificate pins before production deployment.")
        }

        CertificatePinner.Builder()
            // Primary certificate pin (current certificate)
            .add(getProductionHostname(), PRIMARY_CERTIFICATE_PIN)
            // Backup certificate pin (next certificate or CA intermediate)
            .add(getProductionHostname(), BACKUP_CERTIFICATE_PIN)
            .build()
    }

    /**
     * Extracts hostname from the production API URL for certificate pinning
     */
    private fun getProductionHostname(): String {
        return try {
            val url = ServerConfig.apiBaseUrl
            java.net.URL(url).host
        } catch (e: Exception) {
            Timber.w("Failed to parse production hostname, using default")
            "api.production.com" // Fallback placeholder
        }
    }

    /**
     * Creates OkHttpClient with appropriate security settings based on build configuration.
     * - Production: Certificate pinning enabled for HTTPS connections
     * - Development: No certificate pinning (allows local HTTP testing)
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .apply {
                // Only apply certificate pinning in production with HTTPS
                if (ServerConfig.useProductionServer && ServerConfig.apiBaseUrl.startsWith("https")) {
                    certificatePinner(certificatePinner)
                    Timber.d("Certificate pinning enabled for production")
                } else {
                    Timber.d("Certificate pinning disabled (development mode or HTTP)")
                }
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun pilotRegister(request: PilotRegisterRequest): ApiResponse<PilotRegisterResponse> {
        return withContext(Dispatchers.IO) {
            try {
                Timber.d("Attempting pilot registration for email: ${request.email}")
                val json = gson.toJson(request)
                Timber.d("Request JSON: $json")
                val requestBody = json.toRequestBody(jsonMediaType)

                val httpRequest = Request.Builder()
                    .url("$BASE_URL/api/pilot-register")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(requestBody)
                    .build()

                Timber.d("Sending request to: ${httpRequest.url}")
                val response = client.newCall(httpRequest).execute()
                val responseBody = response.body?.string() ?: ""
                Timber.d("Response code: ${response.code}")
                Timber.d("Response body (first 500 chars): ${responseBody.take(500)}")

                // Check if response is HTML (Django error page)
                if (responseBody.trimStart().startsWith("<") || responseBody.contains("<!DOCTYPE")) {
                    Timber.e("Received HTML instead of JSON - Django server error")
                    val errorMsg = when {
                        responseBody.contains("CSRF") -> "CSRF verification failed. Please configure Django to exempt these API endpoints from CSRF protection."
                        responseBody.contains("404") -> "API endpoint not found. Make sure Django URL routing is configured correctly."
                        responseBody.contains("500") -> "Django server error. Check Django server logs."
                        else -> "Server returned HTML instead of JSON. Check Django configuration."
                    }
                    return@withContext ApiResponse.Error(errorMsg, response.code)
                }

                if (response.isSuccessful) {
                    try {
                        val successResponse = gson.fromJson(responseBody, PilotRegisterResponse::class.java)
                        Timber.d("Registration successful: ${successResponse.message}")
                        ApiResponse.Success(successResponse)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse success response")
                        ApiResponse.Error("Invalid response format: ${e.message}", response.code)
                    }
                } else {
                    try {
                        val errorResponse = gson.fromJson(responseBody, ErrorResponse::class.java)
                        Timber.e("Registration failed: ${errorResponse.error}")
                        ApiResponse.Error(errorResponse.error, errorResponse.status_code)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse error response")
                        ApiResponse.Error(responseBody.take(200).ifEmpty { "Unknown error" }, response.code)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Network error during registration")
                ApiResponse.Error("Network error: ${e.message}", 0)
            }
        }
    }

    suspend fun verifyOtp(request: VerifyOtpRequest): ApiResponse<MessageResponse> {
        return withContext(Dispatchers.IO) {
            try {
                Timber.d("Verifying OTP for email: ${request.email}")
                val json = gson.toJson(request)
                val requestBody = json.toRequestBody(jsonMediaType)

                val httpRequest = Request.Builder()
                    .url("$BASE_URL/api/verify-otp")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(requestBody)
                    .build()

                val response = client.newCall(httpRequest).execute()
                val responseBody = response.body?.string() ?: ""
                Timber.d("OTP verification response code: ${response.code}")

                if (responseBody.trimStart().startsWith("<") || responseBody.contains("<!DOCTYPE")) {
                    Timber.e("Received HTML instead of JSON")
                    return@withContext ApiResponse.Error("Server configuration error. Please check Django settings.", response.code)
                }

                if (response.isSuccessful) {
                    try {
                        val successResponse = gson.fromJson(responseBody, MessageResponse::class.java)
                        Timber.d("OTP verification successful")
                        ApiResponse.Success(successResponse)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse OTP success response")
                        ApiResponse.Error("Invalid response format: ${e.message}", response.code)
                    }
                } else {
                    try {
                        val errorResponse = gson.fromJson(responseBody, ErrorResponse::class.java)
                        Timber.e("OTP verification failed: ${errorResponse.error}")
                        ApiResponse.Error(errorResponse.error, errorResponse.status_code)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse OTP error response")
                        ApiResponse.Error(responseBody.take(200).ifEmpty { "Unknown error" }, response.code)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Network error during OTP verification")
                ApiResponse.Error("Network error: ${e.message}", 0)
            }
        }
    }

    suspend fun resendOtp(request: ResendOtpRequest): ApiResponse<MessageResponse> {
        return withContext(Dispatchers.IO) {
            try {
                Timber.d("Resending OTP for email: ${request.email}")
                val json = gson.toJson(request)
                val requestBody = json.toRequestBody(jsonMediaType)

                val httpRequest = Request.Builder()
                    .url("$BASE_URL/api/resend-otp")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(requestBody)
                    .build()

                val response = client.newCall(httpRequest).execute()
                val responseBody = response.body?.string() ?: ""
                Timber.d("Resend OTP response code: ${response.code}")

                if (responseBody.trimStart().startsWith("<") || responseBody.contains("<!DOCTYPE")) {
                    return@withContext ApiResponse.Error("Server configuration error", response.code)
                }

                if (response.isSuccessful) {
                    try {
                        val successResponse = gson.fromJson(responseBody, MessageResponse::class.java)
                        Timber.d("OTP resent successfully")
                        ApiResponse.Success(successResponse)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse resend OTP success response")
                        ApiResponse.Error("Invalid response format: ${e.message}", response.code)
                    }
                } else {
                    try {
                        val errorResponse = gson.fromJson(responseBody, ErrorResponse::class.java)
                        Timber.e("Resend OTP failed: ${errorResponse.error}")
                        ApiResponse.Error(errorResponse.error, errorResponse.status_code)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse resend OTP error response")
                        ApiResponse.Error(responseBody.take(200).ifEmpty { "Unknown error" }, response.code)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Network error during resend OTP")
                ApiResponse.Error("Network error: ${e.message}", 0)
            }
        }
    }

    suspend fun pilotLogin(request: PilotLoginRequest): ApiResponse<PilotLoginResponse> {
        return withContext(Dispatchers.IO) {
            try {
                Timber.d("=== PILOT LOGIN API CALL ===")
                Timber.d("Attempting login for email: ${request.email}")

                val json = gson.toJson(request)
                Timber.d("Request JSON (password masked): ${json.replace(Regex("\"password\":\"[^\"]*\""), "\"password\":\"***\"")}")

                val requestBody = json.toRequestBody(jsonMediaType)

                val httpRequest = Request.Builder()
                    .url("$BASE_URL/api/pilot-login")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(requestBody)
                    .build()

                Timber.d("Sending login request to: ${httpRequest.url}")
                Timber.d("Request headers: ${httpRequest.headers}")

                val response = client.newCall(httpRequest).execute()
                val responseBody = response.body?.string() ?: ""
                Timber.d("Login response code: ${response.code}")
                Timber.d("Login response body (first 500 chars): ${responseBody.take(500)}")
                Timber.d("Response headers: ${response.headers}")

                if (responseBody.trimStart().startsWith("<") || responseBody.contains("<!DOCTYPE")) {
                    Timber.e("Received HTML response instead of JSON - server error")
                    return@withContext ApiResponse.Error("Server configuration error", response.code)
                }

                if (response.isSuccessful) {
                    try {
                        val successResponse = gson.fromJson(responseBody, PilotLoginResponse::class.java)
                        Timber.d("Login successful for pilot ID: ${successResponse.pilot_id}")
                        Timber.d("Login success message: ${successResponse.message}")
                        ApiResponse.Success(successResponse)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse login success response")
                        ApiResponse.Error("Invalid response format: ${e.message}", response.code)
                    }
                } else {
                    Timber.w("Login request failed with status code: ${response.code}")
                    try {
                        val errorResponse = gson.fromJson(responseBody, ErrorResponse::class.java)
                        Timber.e("Login failed: ${errorResponse.error}, status_code: ${errorResponse.status_code}")
                        ApiResponse.Error(errorResponse.error, errorResponse.status_code)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse login error response")
                        Timber.e("Raw response body: $responseBody")
                        ApiResponse.Error(responseBody.take(200).ifEmpty { "Unknown error" }, response.code)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Network error during login")
                ApiResponse.Error("Network error: ${e.message}", 0)
            }
        }
    }

    suspend fun fetchAllAdmins(): ApiResponse<AdminListResponse> {
        return withContext(Dispatchers.IO) {
            try {
                Timber.d("Fetching all admins from: $BASE_URL/api/view-all-admins")
                val requestBody = "{}".toRequestBody(jsonMediaType)

                val httpRequest = Request.Builder()
                    .url("$BASE_URL/api/view-all-admins")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(requestBody)
                    .build()

                val response = client.newCall(httpRequest).execute()
                val responseBody = response.body?.string() ?: ""
                Timber.d("Fetch admins response code: ${response.code}, body: ${responseBody.take(300)}")

                if (responseBody.trimStart().startsWith("<") || responseBody.contains("<!DOCTYPE")) {
                    Timber.e("Received HTML instead of JSON (code: ${response.code})")
                    return@withContext ApiResponse.Error("Server returned error (${response.code}). Endpoint may not be deployed.", response.code)
                }

                if (response.isSuccessful) {
                    try {
                        val successResponse = gson.fromJson(responseBody, AdminListResponse::class.java)
                        Timber.d("Fetched ${successResponse.count} admins: ${successResponse.data.map { it.name }}")
                        ApiResponse.Success(successResponse)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse admin list response")
                        ApiResponse.Error("Invalid response format: ${e.message}", response.code)
                    }
                } else {
                    try {
                        val errorResponse = gson.fromJson(responseBody, ErrorResponse::class.java)
                        Timber.e("Fetch admins failed: ${errorResponse.error}")
                        ApiResponse.Error(errorResponse.error, errorResponse.status_code)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse error response: ${responseBody.take(200)}")
                        ApiResponse.Error(responseBody.take(200).ifEmpty { "Unknown error" }, response.code)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Network error fetching admins")
                ApiResponse.Error("Network error: ${e.message}", 0)
            }
        }
    }

    suspend fun checkVehicleServiceLimit(vehicleId: String): ApiResponse<VehicleListResponse> {
        return withContext(Dispatchers.IO) {
            try {
                Timber.d("Checking service limit for vehicle: $vehicleId")
                val json = """{"action":"view_android","vehicle_id":"$vehicleId"}"""
                val requestBody = json.toRequestBody(jsonMediaType)

                val httpRequest = Request.Builder()
                    .url("$BASE_URL/api/view-vehicles")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(requestBody)
                    .build()

                val response = client.newCall(httpRequest).execute()
                val responseBody = response.body?.string() ?: ""
                Timber.d("Vehicle check response code: ${response.code}")

                if (responseBody.trimStart().startsWith("<") || responseBody.contains("<!DOCTYPE")) {
                    return@withContext ApiResponse.Error("Server configuration error", response.code)
                }

                if (response.isSuccessful) {
                    try {
                        val successResponse = gson.fromJson(responseBody, VehicleListResponse::class.java)
                        Timber.d("Vehicle check successful: ${successResponse.vehicles.size} vehicles")
                        ApiResponse.Success(successResponse)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse vehicle list response")
                        ApiResponse.Error("Invalid response format: ${e.message}", response.code)
                    }
                } else {
                    try {
                        val errorResponse = gson.fromJson(responseBody, ErrorResponse::class.java)
                        ApiResponse.Error(errorResponse.error, errorResponse.status_code)
                    } catch (e: Exception) {
                        ApiResponse.Error(responseBody.take(200).ifEmpty { "Unknown error" }, response.code)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Network error during vehicle check")
                ApiResponse.Error("Network error: ${e.message}", 0)
            }
        }
    }

    suspend fun pilotLogout(request: PilotLogoutRequest): ApiResponse<MessageResponse> {
        return withContext(Dispatchers.IO) {
            try {
                Timber.d("Attempting logout for email: ${request.email}")
                val json = gson.toJson(request)
                val requestBody = json.toRequestBody(jsonMediaType)

                val httpRequest = Request.Builder()
                    .url("$BASE_URL/api/pilot-logout")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(requestBody)
                    .build()

                val response = client.newCall(httpRequest).execute()
                val responseBody = response.body?.string() ?: ""
                Timber.d("Logout response code: ${response.code}")

                if (responseBody.trimStart().startsWith("<") || responseBody.contains("<!DOCTYPE")) {
                    return@withContext ApiResponse.Error("Server configuration error", response.code)
                }

                if (response.isSuccessful) {
                    try {
                        val successResponse = gson.fromJson(responseBody, MessageResponse::class.java)
                        Timber.d("Logout successful")
                        ApiResponse.Success(successResponse)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse logout success response")
                        ApiResponse.Error("Invalid response format: ${e.message}", response.code)
                    }
                } else {
                    try {
                        val errorResponse = gson.fromJson(responseBody, ErrorResponse::class.java)
                        Timber.e("Logout failed: ${errorResponse.error}")
                        ApiResponse.Error(errorResponse.error, errorResponse.status_code)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse logout error response")
                        ApiResponse.Error(responseBody.take(200).ifEmpty { "Unknown error" }, response.code)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Network error during logout")
                ApiResponse.Error("Network error: ${e.message}", 0)
            }
        }
    }
}

// Request models
// @SerializedName annotations ensure field names survive R8/ProGuard obfuscation in release builds
data class PilotRegisterRequest(
    @SerializedName("company_name") val company_name: String,
    @SerializedName("first_name") val first_name: String,
    @SerializedName("last_name") val last_name: String,
    @SerializedName("email") val email: String,
    @SerializedName("mobile_no") val mobile_no: String,
    @SerializedName("password") val password: String,
    @SerializedName("re_password") val re_password: String
)

data class VerifyOtpRequest(
    @SerializedName("email") val email: String,
    @SerializedName("otp") val otp: Int,
    @SerializedName("device_id") val device_id: String
)

data class ResendOtpRequest(
    @SerializedName("email") val email: String
)

data class PilotLoginRequest(
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String,
    @SerializedName("device_id") val device_id: String
)

data class PilotLogoutRequest(
    @SerializedName("email") val email: String
)

// Response models
data class PilotRegisterResponse(
    @SerializedName("message") val message: String,
    @SerializedName("id") val id: Int,
    @SerializedName("status_code") val status_code: Int
)

data class PilotLoginResponse(
    @SerializedName("message") val message: String,
    @SerializedName("pilot_id") val pilot_id: Int,
    @SerializedName("admin_id") val admin_id: Int = 1,
    @SerializedName("superadmin_id") val superadmin_id: Int = -1,
    @SerializedName("status_code") val status_code: Int
)

data class MessageResponse(
    @SerializedName("message") val message: String,
    @SerializedName("status_code") val status_code: Int? = null
)

data class ErrorResponse(
    @SerializedName("error") val error: String,
    @SerializedName("status_code") val status_code: Int
)

sealed class ApiResponse<out T> {
    data class Success<T>(val data: T) : ApiResponse<T>()
    data class Error(val message: String, val statusCode: Int) : ApiResponse<Nothing>()
}

// Admin / Company list models
data class AdminInfo(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String? = null,
    @SerializedName("contact_name") val contact_name: String? = null,
    @SerializedName("email") val email: String? = null,
    @SerializedName("mobile_no") val mobile_no: String? = null,
    @SerializedName("address") val address: String? = null,
    @SerializedName("drones") val drones: Int? = null,
    @SerializedName("pilots") val pilots: Int? = null,
    @SerializedName("logo_url") val logo_url: String? = null,
    @SerializedName("gst_url") val gst_url: String? = null,
    @SerializedName("approval") val approval: Int? = null,
    @SerializedName("status") val status: Int? = null,
    @SerializedName("created_on") val created_on: String? = null
)

data class AdminListResponse(
    @SerializedName("count") val count: Int,
    @SerializedName("data") val data: List<AdminInfo>
)

// Vehicle service limit models
data class VehicleInfo(
    @SerializedName("vehicle_id") val vehicle_id: String,
    @SerializedName("vehicle_name") val vehicle_name: String? = null,
    @SerializedName("mission_count") val mission_count: Int = 0,
    @SerializedName("max_missions_allowed") val max_missions_allowed: Int? = null,
    @SerializedName("is_limit_reached") val is_limit_reached: Boolean = false
)

data class VehicleListResponse(
    @SerializedName("vehicles") val vehicles: List<VehicleInfo>,
    @SerializedName("view_mode") val view_mode: String? = null
)

