package com.example.aerogcsclone.api

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object ApiService {
    private const val TAG = "ApiService"
    // IMPORTANT: Change this to your actual Django server IP address
    // For emulator: use "http://10.0.2.2:8000"
    // For real device: use your computer's IP like "http://192.168.1.100:8000"
    private const val BASE_URL = "http://10.0.2.2:8000"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun pilotRegister(request: PilotRegisterRequest): ApiResponse<PilotRegisterResponse> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Attempting pilot registration for email: ${request.email}")
                val json = gson.toJson(request)
                Log.d(TAG, "Request JSON: $json")
                val requestBody = json.toRequestBody(jsonMediaType)

                val httpRequest = Request.Builder()
                    .url("$BASE_URL/pilot-register")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(requestBody)
                    .build()

                Log.d(TAG, "Sending request to: ${httpRequest.url}")
                val response = client.newCall(httpRequest).execute()
                val responseBody = response.body?.string() ?: ""
                Log.d(TAG, "Response code: ${response.code}")
                Log.d(TAG, "Response body (first 500 chars): ${responseBody.take(500)}")

                // Check if response is HTML (Django error page)
                if (responseBody.trimStart().startsWith("<") || responseBody.contains("<!DOCTYPE")) {
                    Log.e(TAG, "Received HTML instead of JSON - Django server error")
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
                        Log.d(TAG, "Registration successful: ${successResponse.message}")
                        ApiResponse.Success(successResponse)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse success response", e)
                        ApiResponse.Error("Invalid response format: ${e.message}", response.code)
                    }
                } else {
                    try {
                        val errorResponse = gson.fromJson(responseBody, ErrorResponse::class.java)
                        Log.e(TAG, "Registration failed: ${errorResponse.error}")
                        ApiResponse.Error(errorResponse.error, errorResponse.status_code)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse error response", e)
                        ApiResponse.Error(responseBody.take(200).ifEmpty { "Unknown error" }, response.code)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error during registration", e)
                ApiResponse.Error("Network error: ${e.message}", 0)
            }
        }
    }

    suspend fun verifyOtp(request: VerifyOtpRequest): ApiResponse<MessageResponse> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Verifying OTP for email: ${request.email}")
                val json = gson.toJson(request)
                val requestBody = json.toRequestBody(jsonMediaType)

                val httpRequest = Request.Builder()
                    .url("$BASE_URL/verify-otp")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(requestBody)
                    .build()

                val response = client.newCall(httpRequest).execute()
                val responseBody = response.body?.string() ?: ""
                Log.d(TAG, "OTP verification response code: ${response.code}")

                if (responseBody.trimStart().startsWith("<") || responseBody.contains("<!DOCTYPE")) {
                    Log.e(TAG, "Received HTML instead of JSON")
                    return@withContext ApiResponse.Error("Server configuration error. Please check Django settings.", response.code)
                }

                if (response.isSuccessful) {
                    try {
                        val successResponse = gson.fromJson(responseBody, MessageResponse::class.java)
                        Log.d(TAG, "OTP verification successful")
                        ApiResponse.Success(successResponse)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse OTP success response", e)
                        ApiResponse.Error("Invalid response format: ${e.message}", response.code)
                    }
                } else {
                    try {
                        val errorResponse = gson.fromJson(responseBody, ErrorResponse::class.java)
                        Log.e(TAG, "OTP verification failed: ${errorResponse.error}")
                        ApiResponse.Error(errorResponse.error, errorResponse.status_code)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse OTP error response", e)
                        ApiResponse.Error(responseBody.take(200).ifEmpty { "Unknown error" }, response.code)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error during OTP verification", e)
                ApiResponse.Error("Network error: ${e.message}", 0)
            }
        }
    }

    suspend fun resendOtp(request: ResendOtpRequest): ApiResponse<MessageResponse> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Resending OTP for email: ${request.email}")
                val json = gson.toJson(request)
                val requestBody = json.toRequestBody(jsonMediaType)

                val httpRequest = Request.Builder()
                    .url("$BASE_URL/resend-otp")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(requestBody)
                    .build()

                val response = client.newCall(httpRequest).execute()
                val responseBody = response.body?.string() ?: ""
                Log.d(TAG, "Resend OTP response code: ${response.code}")

                if (responseBody.trimStart().startsWith("<") || responseBody.contains("<!DOCTYPE")) {
                    return@withContext ApiResponse.Error("Server configuration error", response.code)
                }

                if (response.isSuccessful) {
                    try {
                        val successResponse = gson.fromJson(responseBody, MessageResponse::class.java)
                        Log.d(TAG, "OTP resent successfully")
                        ApiResponse.Success(successResponse)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse resend OTP success response", e)
                        ApiResponse.Error("Invalid response format: ${e.message}", response.code)
                    }
                } else {
                    try {
                        val errorResponse = gson.fromJson(responseBody, ErrorResponse::class.java)
                        Log.e(TAG, "Resend OTP failed: ${errorResponse.error}")
                        ApiResponse.Error(errorResponse.error, errorResponse.status_code)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse resend OTP error response", e)
                        ApiResponse.Error(responseBody.take(200).ifEmpty { "Unknown error" }, response.code)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error during resend OTP", e)
                ApiResponse.Error("Network error: ${e.message}", 0)
            }
        }
    }

    suspend fun pilotLogin(request: PilotLoginRequest): ApiResponse<PilotLoginResponse> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Attempting login for email: ${request.email}")
                val json = gson.toJson(request)
                val requestBody = json.toRequestBody(jsonMediaType)

                val httpRequest = Request.Builder()
                    .url("$BASE_URL/pilot-login")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(requestBody)
                    .build()

                Log.d(TAG, "Sending login request to: ${httpRequest.url}")
                val response = client.newCall(httpRequest).execute()
                val responseBody = response.body?.string() ?: ""
                Log.d(TAG, "Login response code: ${response.code}")

                if (responseBody.trimStart().startsWith("<") || responseBody.contains("<!DOCTYPE")) {
                    return@withContext ApiResponse.Error("Server configuration error", response.code)
                }

                if (response.isSuccessful) {
                    try {
                        val successResponse = gson.fromJson(responseBody, PilotLoginResponse::class.java)
                        Log.d(TAG, "Login successful for pilot ID: ${successResponse.pilot_id}")
                        ApiResponse.Success(successResponse)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse login success response", e)
                        ApiResponse.Error("Invalid response format: ${e.message}", response.code)
                    }
                } else {
                    try {
                        val errorResponse = gson.fromJson(responseBody, ErrorResponse::class.java)
                        Log.e(TAG, "Login failed: ${errorResponse.error}")
                        ApiResponse.Error(errorResponse.error, errorResponse.status_code)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse login error response", e)
                        ApiResponse.Error(responseBody.take(200).ifEmpty { "Unknown error" }, response.code)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error during login", e)
                ApiResponse.Error("Network error: ${e.message}", 0)
            }
        }
    }

    suspend fun pilotLogout(request: PilotLogoutRequest): ApiResponse<MessageResponse> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Attempting logout for email: ${request.email}")
                val json = gson.toJson(request)
                val requestBody = json.toRequestBody(jsonMediaType)

                val httpRequest = Request.Builder()
                    .url("$BASE_URL/pilot-logout")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(requestBody)
                    .build()

                val response = client.newCall(httpRequest).execute()
                val responseBody = response.body?.string() ?: ""
                Log.d(TAG, "Logout response code: ${response.code}")

                if (responseBody.trimStart().startsWith("<") || responseBody.contains("<!DOCTYPE")) {
                    return@withContext ApiResponse.Error("Server configuration error", response.code)
                }

                if (response.isSuccessful) {
                    try {
                        val successResponse = gson.fromJson(responseBody, MessageResponse::class.java)
                        Log.d(TAG, "Logout successful")
                        ApiResponse.Success(successResponse)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse logout success response", e)
                        ApiResponse.Error("Invalid response format: ${e.message}", response.code)
                    }
                } else {
                    try {
                        val errorResponse = gson.fromJson(responseBody, ErrorResponse::class.java)
                        Log.e(TAG, "Logout failed: ${errorResponse.error}")
                        ApiResponse.Error(errorResponse.error, errorResponse.status_code)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse logout error response", e)
                        ApiResponse.Error(responseBody.take(200).ifEmpty { "Unknown error" }, response.code)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error during logout", e)
                ApiResponse.Error("Network error: ${e.message}", 0)
            }
        }
    }
}

// Request models
data class PilotRegisterRequest(
    val first_name: String,
    val last_name: String,
    val email: String,
    val mobile_no: String,
    val password: String,
    val re_password: String
)

data class VerifyOtpRequest(
    val email: String,
    val otp: Int
)

data class ResendOtpRequest(
    val email: String
)

data class PilotLoginRequest(
    val email: String,
    val password: String
)

data class PilotLogoutRequest(
    val email: String
)

// Response models
data class PilotRegisterResponse(
    val message: String,
    val id: Int,
    val status_code: Int
)

data class PilotLoginResponse(
    val message: String,
    val pilot_id: Int,
    val status_code: Int
)

data class MessageResponse(
    val message: String,
    val status_code: Int? = null
)

data class ErrorResponse(
    val error: String,
    val status_code: Int
)

sealed class ApiResponse<out T> {
    data class Success<T>(val data: T) : ApiResponse<T>()
    data class Error(val message: String, val statusCode: Int) : ApiResponse<Nothing>()
}
