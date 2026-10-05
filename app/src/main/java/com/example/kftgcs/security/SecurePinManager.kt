package com.example.kftgcs.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * SecurePinManager - Handles secure PIN storage using Android Keystore
 *
 * SECURITY FEATURES:
 * - PIN is encrypted using AES-256-GCM before storage
 * - Encryption key is stored in Android Keystore (hardware-backed on supported devices)
 * - Key material never leaves the secure hardware
 * - Protected against extraction even on rooted devices
 *
 * This replaces plaintext SharedPreferences storage with encrypted storage.
 */
object SecurePinManager {

    private const val KEY_ALIAS = "aerogcs_pin_encryption_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    // SharedPreferences keys for storing encrypted data
    private const val PREFS_NAME = "secure_pin_prefs"
    private const val KEY_ENCRYPTED_PIN = "encrypted_pin"
    private const val KEY_IV = "encryption_iv"

    /**
     * Get or create the encryption key from Android Keystore
     * The key is generated with hardware-backed security when available
     */
    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        // Return existing key if it exists
        keyStore.getKey(KEY_ALIAS, null)?.let {
            return it as SecretKey
        }

        // Generate new key
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val keyGenSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Require user authentication for extra security (optional - uncomment if needed)
            // .setUserAuthenticationRequired(true)
            // .setUserAuthenticationValidityDurationSeconds(30)
            .build()

        keyGenerator.init(keyGenSpec)

        return keyGenerator.generateKey()
    }

    /**
     * Encrypt and save the PIN securely
     * @param context Application context
     * @param pin The 4-digit PIN to encrypt and store
     */
    fun savePin(context: Context, pin: String) {
        try {
            val secretKey = getOrCreateSecretKey()

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)

            val encryptedBytes = cipher.doFinal(pin.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv

            // Store encrypted PIN and IV
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_ENCRYPTED_PIN, Base64.encodeToString(encryptedBytes, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .apply()

        } catch (e: Exception) {
            throw SecurityException("Failed to save PIN securely", e)
        }
    }

    /**
     * Check if a PIN has been set
     * @param context Application context
     * @return true if a PIN is stored
     */
    fun isPinSet(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(KEY_ENCRYPTED_PIN) && prefs.contains(KEY_IV)
    }

    /**
     * Migrate from old plaintext storage to secure storage
     * Call this once during app upgrade to migrate existing PINs
     * @param context Application context
     */
    fun migrateFromPlaintextStorage(context: Context) {
        try {
            // Check for old plaintext PIN
            val oldPrefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            val plaintextPin = oldPrefs.getString("pin", null)

            if (plaintextPin != null) {
                // Save using secure storage
                savePin(context, plaintextPin)

                // Remove old plaintext PIN
                oldPrefs.edit().remove("pin").apply()
            }
        } catch (e: Exception) {
            // Migration failed - continue silently
        }
    }
}

