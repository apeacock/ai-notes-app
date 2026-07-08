package com.ai.notes.data.preferences

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApiKeyManagerTest {
    private lateinit var manager: ApiKeyManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        manager = ApiKeyManager(context)
        manager.clearApiKey()
    }

    @Test
    fun hasApiKeyIsFalseInitially() {
        assertFalse(manager.hasApiKey())
        assertNull(manager.getApiKey())
    }

    @Test
    fun saveApiKeyThenRetrieveReturnsSameValue() {
        manager.saveApiKey("sk-ant-test-key-123")
        assertEquals("sk-ant-test-key-123", manager.getApiKey())
        assertTrue(manager.hasApiKey())
    }

    @Test
    fun clearApiKeyRemovesStoredKey() {
        manager.saveApiKey("sk-ant-test-key-123")
        manager.clearApiKey()
        assertNull(manager.getApiKey())
        assertFalse(manager.hasApiKey())
    }

    @Test
    fun saveApiKeyOverwritesPreviousValue() {
        manager.saveApiKey("first-key")
        manager.saveApiKey("second-key")
        assertEquals("second-key", manager.getApiKey())
    }

    @Test
    fun recoversWhenPrefsFileCannotBeDecrypted() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        manager.saveApiKey("sk-ant-before-corruption")

        // Simulate a restored-from-backup or corrupted state: overwrite the Tink keysets stored
        // inside the prefs file with garbage, so EncryptedSharedPreferences.create() can no
        // longer decrypt it with this device's Keystore master key.
        context.getSharedPreferences("ai_notes_secure_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("__androidx_security_crypto_encrypted_prefs_key_keyset__", "not-a-keyset")
            .putString("__androidx_security_crypto_encrypted_prefs_value_keyset__", "not-a-keyset")
            .commit()

        // Must not throw: the corrupt file is dropped and recreated empty.
        val recovered = ApiKeyManager(context)
        assertNull(recovered.getApiKey())

        // And the recovered store is fully functional again.
        recovered.saveApiKey("sk-ant-after-recovery")
        assertEquals("sk-ant-after-recovery", recovered.getApiKey())
    }
}
