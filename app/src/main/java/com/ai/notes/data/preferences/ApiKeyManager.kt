package com.ai.notes.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class ApiKeyManager(context: Context) {
    private val prefs: SharedPreferences = try {
        createEncryptedPrefs(context)
    } catch (e: Exception) {
        // The prefs file can't be decrypted with this device's Keystore key — typically a file
        // restored from backup onto a new device, or a corrupted keyset. Without this recovery
        // the app crashes on every launch. Drop the file and start fresh; the user is prompted
        // to re-enter their API key.
        context.deleteSharedPreferences(PREFS_FILE_NAME)
        createEncryptedPrefs(context)
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveApiKey(key: String) {
        prefs.edit().putString(KEY_API_KEY, key).apply()
    }

    fun getApiKey(): String? = prefs.getString(KEY_API_KEY, null)

    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    fun hasApiKey(): Boolean = !getApiKey().isNullOrEmpty()

    companion object {
        private const val PREFS_FILE_NAME = "ai_notes_secure_prefs"
        private const val KEY_API_KEY = "claude_api_key"
    }
}
