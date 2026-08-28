package com.schoolms.mobile.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores only non-secret session metadata. The encryption key is held by the
 * Android Keystore; Firebase Auth retains its own credentials separately.
 */
@Suppress("DEPRECATION")
class SecureSessionStore(context: Context) {
    private val preferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun save(username: String, role: Role, expiresAtMillis: Long) {
        preferences.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_ROLE, role.name)
            .putLong(KEY_EXPIRES_AT, expiresAtMillis)
            .putBoolean(KEY_BIOMETRIC_ENABLED, false)
            .apply()
    }

    fun username(): String = preferences.getString(KEY_USERNAME, "").orEmpty()

    fun role(): Role? = preferences.getString(KEY_ROLE, null)
        ?.let { saved -> Role.entries.firstOrNull { it.name == saved } }

    fun expiresAtMillis(): Long = preferences.getLong(KEY_EXPIRES_AT, 0L)

    fun isBiometricEnabled(): Boolean = preferences.getBoolean(KEY_BIOMETRIC_ENABLED, false)

    fun setBiometricEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    companion object {
        private const val FILE_NAME = "schoolhub_secure_session"
        private const val KEY_USERNAME = "username"
        private const val KEY_ROLE = "role"
        private const val KEY_EXPIRES_AT = "expires_at_millis"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
    }
}
