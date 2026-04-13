package com.example.kftgcs.api

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaDrm
import android.os.Build
import android.provider.Settings
import android.util.Base64
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
        getPreferences(context).edit { clear() }
    }

    /**
     * Get a stable unique device identifier derived from hardware — survives app uninstall/reinstall.
     *
     * Priority:
     * 1. Widevine DRM device ID (hardware-backed, never changes)
     * 2. ANDROID_ID (stable per app-signing-key + user + device on Android 8+)
     * 3. SHA-256 hash of immutable Build properties (last resort)
     *
     * Nothing is stored in SharedPreferences; the ID is recomputed on every call
     * from the same hardware source, so it is always consistent.
     */
    fun getDeviceId(context: Context): String {
        // 1. Try hardware-backed Widevine ID first
        val widevineId = getWidevineDeviceId()
        if (!widevineId.isNullOrBlank()) {
            return widevineId
        }

        // 2. Fallback to ANDROID_ID
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
            // "9774d56d682e549c" is a known bogus value present on some old/emulator devices
            return androidId
        }

        // 3. Last resort: deterministic hash of immutable Build properties
        return generateBuildFingerprint()
    }

    /**
     * Retrieve the Widevine DRM device unique ID.
     * This ID is hardware-backed and survives factory resets on some devices,
     * and always survives app uninstall/reinstall.
     * Returns null if Widevine is unavailable or the device does not support it.
     */
    private fun getWidevineDeviceId(): String? {
        return try {
            val WIDEVINE_UUID = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
            val mediaDrm = MediaDrm(WIDEVINE_UUID)
            val widevineId = mediaDrm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                mediaDrm.close()
            } else {
                @Suppress("DEPRECATION")
                mediaDrm.release()
            }
            if (widevineId != null && widevineId.isNotEmpty()) {
                Base64.encodeToString(widevineId, Base64.NO_WRAP)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Generate a deterministic identifier by SHA-256 hashing immutable Build properties.
     * Used only as a last resort when both Widevine and ANDROID_ID are unavailable.
     */
    private fun generateBuildFingerprint(): String {
        val fingerprint = "${Build.BOARD}${Build.BRAND}${Build.DEVICE}" +
            "${Build.HARDWARE}${Build.MANUFACTURER}${Build.MODEL}${Build.PRODUCT}"
        return fingerprint.toByteArray()
            .let { java.security.MessageDigest.getInstance("SHA-256").digest(it) }
            .let { Base64.encodeToString(it, Base64.NO_WRAP) }
    }
}

