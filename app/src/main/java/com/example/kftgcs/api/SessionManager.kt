package com.example.kftgcs.api

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * SessionManager - Secure session storage using EncryptedSharedPreferences
 *
 * SECURITY FEATURES:
 * - All session data is encrypted using AES-256-GCM
 * - Encryption keys are stored in Android Keystore
 * - Protects against backup extraction and rooted device access
 *
 * Stored data:
 * - Email (encrypted)
 * - Pilot ID (encrypted)
 * - Admin ID (encrypted)
 * - Login status (encrypted)
 * - First/Last name (encrypted)
 */
object SessionManager {
    private const val PREF_NAME = "secure_pilot_session"
    private const val KEY_EMAIL = "email"
    private const val KEY_PILOT_ID = "pilot_id"
    private const val KEY_ADMIN_ID = "admin_id"
    private const val KEY_SUPER_ADMIN_ID = "super_admin_id"
    private const val KEY_IS_LOGGED_IN = "is_logged_in"
    private const val KEY_FIRST_NAME = "first_name"
    private const val KEY_LAST_NAME = "last_name"
    private const val KEY_DEVICE_ID = "device_id"

    // Legacy preferences name for migration
    private const val LEGACY_PREF_NAME = "pilot_session"

    /**
     * Get encrypted SharedPreferences instance
     * Uses AES-256-GCM for both key and value encryption
     */
    private fun getPreferences(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREF_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback to regular SharedPreferences only if encryption fails
            // This should rarely happen but prevents app crashes
            context.getSharedPreferences(PREF_NAME + "_fallback", Context.MODE_PRIVATE)
        }
    }

    /**
     * Migrate data from old plaintext SharedPreferences to encrypted storage
     * Call this once during app startup or upgrade
     */
    fun migrateFromPlaintextStorage(context: Context) {
        try {
            val legacyPrefs = context.getSharedPreferences(LEGACY_PREF_NAME, Context.MODE_PRIVATE)

            // Check if migration is needed
            if (!legacyPrefs.contains(KEY_EMAIL) && !legacyPrefs.contains(KEY_PILOT_ID)) {
                // No legacy data to migrate, but still fix adminId if needed
                fixAdminIdIfNeeded(context)
                return
            }


            val email = legacyPrefs.getString(KEY_EMAIL, null)
            val pilotId = legacyPrefs.getInt(KEY_PILOT_ID, -1)
            val adminId = legacyPrefs.getInt(KEY_ADMIN_ID, 1) // Use 1 as default (matches DB)
            val isLoggedIn = legacyPrefs.getBoolean(KEY_IS_LOGGED_IN, false)
            val firstName = legacyPrefs.getString(KEY_FIRST_NAME, null)
            val lastName = legacyPrefs.getString(KEY_LAST_NAME, null)

            // Save to encrypted storage
            val encryptedPrefs = getPreferences(context)
            encryptedPrefs.edit {
                email?.let { putString(KEY_EMAIL, it) }
                if (pilotId != -1) putInt(KEY_PILOT_ID, pilotId)
                putInt(KEY_ADMIN_ID, adminId)
                putBoolean(KEY_IS_LOGGED_IN, isLoggedIn)
                firstName?.let { putString(KEY_FIRST_NAME, it) }
                lastName?.let { putString(KEY_LAST_NAME, it) }
            }

            // Clear legacy plaintext storage
            legacyPrefs.edit { clear() }

            // Also fix adminId if needed
            fixAdminIdIfNeeded(context)

        } catch (e: Exception) {
        }
    }

    /**
     * Fix adminId if it's set to an invalid value
     * The database has Admin(id=1)
     * This is public so it can be called on app startup
     */
    fun fixAdminIdIfNeeded(context: Context) {
        try {
            val prefs = getPreferences(context)
            val currentAdminId = prefs.getInt(KEY_ADMIN_ID, 1)
            if (currentAdminId == 2) {
                prefs.edit {
                    putInt(KEY_ADMIN_ID, 1)
                }
            }
        } catch (e: Exception) {
        }
    }

    fun saveSession(context: Context, email: String, pilotId: Int, adminId: Int = 1, superAdminId: Int = -1) {
        getPreferences(context).edit {
            putString(KEY_EMAIL, email)
            putInt(KEY_PILOT_ID, pilotId)
            putInt(KEY_ADMIN_ID, adminId)
            if (superAdminId > 0) putInt(KEY_SUPER_ADMIN_ID, superAdminId)
            putBoolean(KEY_IS_LOGGED_IN, true)
        }
    }

    fun saveAdminId(context: Context, adminId: Int) {
        getPreferences(context).edit {
            putInt(KEY_ADMIN_ID, adminId)
        }
    }

    fun saveSuperAdminId(context: Context, superAdminId: Int) {
        getPreferences(context).edit {
            putInt(KEY_SUPER_ADMIN_ID, superAdminId)
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
        return getPreferences(context).getInt(KEY_ADMIN_ID, 1) // Default to 1 if not set (matches DB)
    }

    fun getSuperAdminId(context: Context): Int {
        return getPreferences(context).getInt(KEY_SUPER_ADMIN_ID, -1)
    }

    fun isLoggedIn(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_IS_LOGGED_IN, false)
    }

    fun clearSession(context: Context) {
        val prefs = getPreferences(context)
        // Preserve the device ID across session clears (logout/login)
        val deviceId = prefs.getString(KEY_DEVICE_ID, null)
        prefs.edit {
            clear()
            // Restore device ID if it existed
            deviceId?.let { putString(KEY_DEVICE_ID, it) }
        }
    }

    /**
     * Get a stable unique device identifier that persists across app reinstalls.
     *
     * Strategy:
     * 1. First, check if we have a previously generated device ID stored (survives reinstall
     *    if the app data was backed up, or on devices where SharedPreferences persists)
     * 2. If not, use ANDROID_ID as a base combined with device hardware identifiers
     *    to generate a deterministic UUID that is stable for the physical device.
     * 3. Store this generated ID so it's consistently returned.
     *
     * Note: This approach creates a device-specific ID based on hardware properties
     * that won't change even if the app is reinstalled, as long as it's the same
     * physical device.
     */
    fun getDeviceId(context: Context): String {
        val prefs = getPreferences(context)

        // Check if we already have a stored device ID
        val storedId = prefs.getString(KEY_DEVICE_ID, null)
        if (!storedId.isNullOrBlank() && storedId != "unknown") {
            return storedId
        }

        // Generate a new stable device ID based on hardware properties
        val deviceId = generateStableDeviceId(context)

        // Store for future use
        prefs.edit {
            putString(KEY_DEVICE_ID, deviceId)
        }

        return deviceId
    }

    /**
     * Generates a stable device identifier based on hardware properties.
     * This ID remains the same even after app uninstall/reinstall on the same device.
     *
     * IMPORTANT: Only uses properties that NEVER change for a given physical device:
     * - ANDROID_ID: Stable per (app signing key + user + device) on Android 8+.
     *   Does NOT change on reinstall as long as the same signing key is used.
     * - Build.BOARD, BRAND, MANUFACTURER, MODEL, HARDWARE: Immutable hardware properties.
     *
     * NOT included (these cause instability):
     * - Build.FINGERPRINT: Changes with every OS update / security patch.
     * - Build.SERIAL: Returns "unknown" on Android 8+ without READ_PHONE_STATE permission.
     */
    private fun generateStableDeviceId(context: Context): String {
        // ANDROID_ID is the primary stable identifier
        // On Android 8+, it's unique per (app signing key, user, device) and
        // persists across reinstalls as long as the app is signed with the same key
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""

        // Immutable hardware properties that NEVER change for a physical device
        val deviceBoard = Build.BOARD
        val deviceBrand = Build.BRAND
        val deviceManufacturer = Build.MANUFACTURER
        val deviceModel = Build.MODEL
        val deviceHardware = Build.HARDWARE

        // Create a stable hash from ONLY immutable device properties
        val combinedString = buildString {
            // ANDROID_ID is the primary unique identifier
            append(androidId)
            // Hardware properties provide additional uniqueness and stability
            append(deviceBoard)
            append(deviceBrand)
            append(deviceManufacturer)
            append(deviceModel)
            append(deviceHardware)
        }

        // Generate a deterministic UUID from the combined string
        return try {
            UUID.nameUUIDFromBytes(combinedString.toByteArray()).toString()
        } catch (e: Exception) {
            // Fallback to ANDROID_ID if UUID generation fails
            androidId.ifBlank { "unknown-${System.currentTimeMillis()}" }
        }
    }
}

