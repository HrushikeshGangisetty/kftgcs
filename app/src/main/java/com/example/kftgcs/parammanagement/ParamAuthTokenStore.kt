package com.example.kftgcs.parammanagement

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists access/refresh tokens + cached user profile for the Param Management
 * secondary auth flow. Backed by SharedPreferences (no encryption — these are
 * short-lived bearer tokens for an internal endpoint).
 */
class ParamAuthTokenStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveTokens(
        accessToken: String,
        refreshToken: String,
        expiresInSeconds: Long?,
        user: ParamUser?
    ) {
        val expiresAtMillis = expiresInSeconds?.let { System.currentTimeMillis() + it * 1000L }
        prefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            putString(KEY_REFRESH_TOKEN, refreshToken)
            if (expiresAtMillis != null) putLong(KEY_EXPIRES_AT, expiresAtMillis) else remove(KEY_EXPIRES_AT)
            if (user != null) {
                putString(KEY_USER_ID, user.id)
                putString(KEY_USER_EMAIL, user.email)
                putString(KEY_USER_FULL_NAME, user.fullName)
                putString(KEY_USER_ROLE, user.role)
            }
            apply()
        }
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun getCachedUser(): ParamUser? {
        val id = prefs.getString(KEY_USER_ID, null)
        val email = prefs.getString(KEY_USER_EMAIL, null)
        val fullName = prefs.getString(KEY_USER_FULL_NAME, null)
        val role = prefs.getString(KEY_USER_ROLE, null)
        return if (id == null && email == null && fullName == null && role == null) null
        else ParamUser(id, email, fullName, role)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "param_auth_tokens"
        private const val KEY_ACCESS_TOKEN  = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT    = "expires_at_ms"
        private const val KEY_USER_ID        = "user_id"
        private const val KEY_USER_EMAIL     = "user_email"
        private const val KEY_USER_FULL_NAME = "user_full_name"
        private const val KEY_USER_ROLE      = "user_role"
    }
}
