package com.example.aerogcsclone.api

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object SessionManager {
    private const val PREF_NAME = "pilot_session"
    private const val KEY_EMAIL = "email"
    private const val KEY_PILOT_ID = "pilot_id"
    private const val KEY_ADMIN_ID = "admin_id"
    private const val KEY_IS_LOGGED_IN = "is_logged_in"
    private const val KEY_FIRST_NAME = "first_name"
    private const val KEY_LAST_NAME = "last_name"

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun saveSession(context: Context, email: String, pilotId: Int, adminId: Int = 1) {
        getPreferences(context).edit {
            putString(KEY_EMAIL, email)
            putInt(KEY_PILOT_ID, pilotId)
            putInt(KEY_ADMIN_ID, adminId)
            putBoolean(KEY_IS_LOGGED_IN, true)
        }
    }

    fun saveAdminId(context: Context, adminId: Int) {
        getPreferences(context).edit {
            putInt(KEY_ADMIN_ID, adminId)
        }
    }

    fun saveUserDetails(context: Context, firstName: String, lastName: String) {
        getPreferences(context).edit {
            putString(KEY_FIRST_NAME, firstName)
            putString(KEY_LAST_NAME, lastName)
        }
    }

    fun getEmail(context: Context): String? {
        return getPreferences(context).getString(KEY_EMAIL, null)
    }

    fun getPilotId(context: Context): Int {
        return getPreferences(context).getInt(KEY_PILOT_ID, -1)
    }

    fun getAdminId(context: Context): Int {
        return getPreferences(context).getInt(KEY_ADMIN_ID, 1) // Default to 1 if not set
    }

    fun isLoggedIn(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_IS_LOGGED_IN, false)
    }

    fun clearSession(context: Context) {
        getPreferences(context).edit {
            clear()
        }
    }
}

