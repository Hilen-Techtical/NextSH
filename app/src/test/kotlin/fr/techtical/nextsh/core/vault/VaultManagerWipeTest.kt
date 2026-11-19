// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import android.content.SharedPreferences
import android.security.keystore.UserNotAuthenticatedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The wipe of the vault works on the RAW preferences of its file: nothing is decrypted (a `clear()` of
 * EncryptedSharedPreferences walks `getAll()`, which decrypts every name and every value to a String), the
 * master key of the Keystore is not asked for, and the vault need not be open.
 */
class VaultManagerWipeTest {

    /** The raw preferences: names and values are opaque here, like the encrypted ones in the file. */
    private class MemoryPrefs(initial: Map<String, Any?>) : SharedPreferences {
        val data = LinkedHashMap(initial)

        /** [commit] answers this, and writes nothing when it is false. */
        var commitResult = true

        /** Keys that a removal does not delete (a write that did not reach the file). */
        val stuck = mutableSetOf<String>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val removals = mutableListOf<String>()
            private var clearAll = false
            override fun putString(key: String?, value: String?) = this
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?): SharedPreferences.Editor { key?.let(removals::add); return this }
            override fun clear(): SharedPreferences.Editor { clearAll = true; return this }
            override fun commit(): Boolean {
                if (!commitResult) return false
                if (clearAll) data.clear()
                removals.filter { it !in stuck }.forEach(data::remove)
                return true
            }
            override fun apply() { commit() }
        }
    }

    private val reserved = listOf(
        "${VaultManager.RESERVED_KEY_PREFIX}key_keyset__",
        "${VaultManager.RESERVED_KEY_PREFIX}value_keyset__",
    )

    private val calls = mutableListOf<String>()
    private var legacyPresent = false
    private var legacyDeleted = true
    private var probeFailure: Throwable? = null

    private fun prefsWithSecrets() = MemoryPrefs(
        buildMap {
            reserved.forEach { put(it, "keyset") }
            put("AbCdEf1", "encrypted password")
            put("AbCdEf2", "encrypted key")
            put("AbCdEf3", "encrypted passphrase")
        },
    )

    private fun newManager(prefs: MemoryPrefs): VaultManager {
        val opener = object : VaultPrefsOpener {
            override fun ensureMasterKey() { calls += "ensureMasterKey" }
            override fun open(): SharedPreferences { calls += "open"; return prefs }
            override fun rawPrefs(): SharedPreferences { calls += "rawPrefs"; return prefs }
            override fun deleteLegacyVault(): Boolean { calls += "deleteLegacyVault"; legacyPresent = !legacyDeleted; return legacyDeleted }
            override fun legacyVaultPresent(): Boolean = legacyPresent
        }
        return VaultManager(MasterKeyProbe { calls += "probe"; probeFailure?.let { throw it } }, opener)
    }

    @Test
    fun `every entry but the two reserved ones goes, and true says the wipe is confirmed`() {
        val prefs = prefsWithSecrets()

        assertTrue(newManager(prefs).wipeAllEntries())

        assertEquals(reserved.toSet(), prefs.data.keys, "the key sets of the file stay: the open instance needs them")
    }

    @Test
    fun `the vault is not opened, the master key is not asked for and the probe does not run`() {
        val prefs = prefsWithSecrets()

        newManager(prefs).wipeAllEntries()

        assertEquals(listOf("rawPrefs", "deleteLegacyVault"), calls, "nothing else: no ensureMasterKey, no probe, no open")
    }

    @Test
    fun `a wipe works while the authentication window is closed`() {
        probeFailure = UserNotAuthenticatedException("window expired")
        val prefs = prefsWithSecrets()

        newManager(prefs).wipeVault()

        assertEquals(reserved.toSet(), prefs.data.keys)
        assertFalse("probe" in calls)
    }

    @Test
    fun `a commit that does not go through is not a confirmed wipe`() {
        val prefs = prefsWithSecrets().also { it.commitResult = false }

        assertFalse(newManager(prefs).wipeAllEntries())
        assertThrows(IOException::class.java) { newManager(prefs).wipeVault() }
    }

    @Test
    fun `an entry that is still in the file after the write is a failure`() {
        val prefs = prefsWithSecrets().also { it.stuck += "AbCdEf2" }

        assertFalse(newManager(prefs).wipeAllEntries())
        assertFalse(newManager(prefs).isWiped())
    }

    @Test
    fun `an old vault file that cannot be deleted is a failure`() {
        legacyDeleted = false

        assertFalse(newManager(prefsWithSecrets()).wipeAllEntries())
    }

    @Test
    fun `wipeVault raises a fixed text without anything of the vault`() {
        val prefs = prefsWithSecrets().also { it.commitResult = false }

        val failure = assertThrows(IOException::class.java) { newManager(prefs).wipeVault() }

        assertEquals("Vault could not be erased", failure.message)
    }

    @Test
    fun `a vault that holds only its key sets is wiped`() {
        val prefs = MemoryPrefs(reserved.associateWith { "keyset" })

        assertTrue(newManager(prefs).isWiped())
    }

    @Test
    fun `a vault that holds an entry, or an old vault file, is not wiped`() {
        assertFalse(newManager(prefsWithSecrets()).isWiped())

        legacyPresent = true
        assertFalse(newManager(MemoryPrefs(reserved.associateWith { "keyset" })).isWiped())
    }

    @Test
    fun `the check of a wipe does not decrypt nor open anything either`() {
        newManager(prefsWithSecrets()).isWiped()

        assertEquals(listOf("rawPrefs"), calls)
    }
}
