// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.shortcut

import android.content.Intent
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk

/**
 * An [Intent] that keeps its extras in a map, so that the intent a shortcut is published with can
 * be handed to the code that reads it, in a JVM test where the real class is a stub. Like the real
 * one, `getStringExtra` gives null for an extra that is not a string.
 */
internal class FakeIntent(
    initialExtras: Map<String, Any> = emptyMap(),
    flags: Int = 0,
) {
    val extras: MutableMap<String, Any> = initialExtras.toMutableMap()

    val intent: Intent = mockk<Intent>(relaxed = true).also { mock ->
        every { mock.putExtra(any<String>(), any<String>()) } answers {
            extras[firstArg()] = secondArg<String>()
            mock
        }
        every { mock.getStringExtra(any()) } answers { extras[firstArg()] as? String }
        every { mock.removeExtra(any()) } answers {
            extras.remove(firstArg<String>())
            Unit
        }
        every { mock.flags } returns flags
    }
}

/** A [SharedPreferences] in memory, with the one write the token store makes. */
internal class FakeSharedPreferences : SharedPreferences {
    val values = mutableMapOf<String, Any?>()

    /** How many times something was written to the "disk". */
    var writes = 0
        private set

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key!!] as String? ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getLong(key: String?, defValue: Long): Long = defValue
    override fun getFloat(key: String?, defValue: Float): Float = defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
    override fun contains(key: String?): Boolean = values.containsKey(key!!)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = also { pending[key!!] = value }
        override fun putStringSet(key: String?, set: MutableSet<String>?): SharedPreferences.Editor = also { pending[key!!] = set }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = also { pending[key!!] = value }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = also { pending[key!!] = value }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = also { pending[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = also { pending[key!!] = value }
        override fun remove(key: String?): SharedPreferences.Editor = also { pending[key!!] = null }
        override fun clear(): SharedPreferences.Editor = also { values.clear() }

        override fun commit(): Boolean {
            values.putAll(pending)
            writes++
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
