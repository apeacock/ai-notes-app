package com.ai.notes.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** In-memory SharedPreferences; only the String operations ApiKeyManager uses are meaningful. */
private class FakeSharedPreferences : SharedPreferences {
    val values = mutableMapOf<String, Any?>()

    override fun getAll(): Map<String, *> = values.toMap()
    override fun getString(key: String, defValue: String?): String? = values[key] as String? ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = defValues
    override fun getInt(key: String, defValue: Int) = defValue
    override fun getLong(key: String, defValue: Long) = defValue
    override fun getFloat(key: String, defValue: Float) = defValue
    override fun getBoolean(key: String, defValue: Boolean) = defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = this
        override fun putInt(key: String, value: Int) = this
        override fun putLong(key: String, value: Long) = this
        override fun putFloat(key: String, value: Float) = this
        override fun putBoolean(key: String, value: Boolean) = this
        override fun remove(key: String) = apply { removals += key }
        override fun clear() = this
        override fun commit(): Boolean {
            apply()
            return true
        }

        // Writes land only on apply/commit, so a mutant that drops apply() is observable.
        override fun apply() {
            removals.forEach { values.remove(it) }
            values.putAll(pending)
        }
    }
}

class ApiKeyManagerTest {
    private val context = mockk<Context>(relaxed = true)
    private val prefs = FakeSharedPreferences()

    @Before
    fun setup() {
        mockkConstructor(MasterKey.Builder::class)
        every { anyConstructed<MasterKey.Builder>().setKeyScheme(any()) } answers { self as MasterKey.Builder }
        every { anyConstructed<MasterKey.Builder>().build() } returns mockk()
        mockkStatic(EncryptedSharedPreferences::class)
        every {
            EncryptedSharedPreferences.create(any<Context>(), any<String>(), any<MasterKey>(), any(), any())
        } returns prefs
    }

    @After
    fun teardown() = unmockkAll()

    @Test
    fun `opens the encrypted prefs file with AES256 schemes`() {
        ApiKeyManager(context)

        verify {
            EncryptedSharedPreferences.create(
                context,
                "ai_notes_secure_prefs",
                any<MasterKey>(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        verify { anyConstructed<MasterKey.Builder>().setKeyScheme(MasterKey.KeyScheme.AES256_GCM) }
    }

    @Test
    fun `has no API key initially`() {
        val manager = ApiKeyManager(context)

        assertNull(manager.getApiKey())
        assertFalse(manager.hasApiKey())
    }

    @Test
    fun `saveApiKey persists the key under its prefs entry`() {
        val manager = ApiKeyManager(context)

        manager.saveApiKey("sk-ant-123")

        assertEquals("sk-ant-123", manager.getApiKey())
        assertTrue(manager.hasApiKey())
        assertEquals(mapOf("claude_api_key" to "sk-ant-123"), prefs.values)
    }

    @Test
    fun `an empty saved key does not count as having a key`() {
        val manager = ApiKeyManager(context)

        manager.saveApiKey("")

        assertFalse(manager.hasApiKey())
    }

    @Test
    fun `clearApiKey removes the stored key`() {
        val manager = ApiKeyManager(context)
        manager.saveApiKey("sk-ant-123")

        manager.clearApiKey()

        assertNull(manager.getApiKey())
        assertFalse(manager.hasApiKey())
    }

    @Test
    fun `an undecryptable prefs file is deleted and recreated`() {
        every {
            EncryptedSharedPreferences.create(any<Context>(), any<String>(), any<MasterKey>(), any(), any())
        } throws SecurityException("bad keyset") andThen prefs

        val manager = ApiKeyManager(context)
        manager.saveApiKey("sk-ant-123")

        verify(exactly = 1) { context.deleteSharedPreferences("ai_notes_secure_prefs") }
        assertEquals("sk-ant-123", manager.getApiKey())
    }

    @Test
    fun `a readable prefs file is never deleted`() {
        ApiKeyManager(context)

        verify(exactly = 0) { context.deleteSharedPreferences(any()) }
    }
}
