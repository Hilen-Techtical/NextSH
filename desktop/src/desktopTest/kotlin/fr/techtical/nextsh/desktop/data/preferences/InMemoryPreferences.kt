// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.preferences

import java.util.prefs.AbstractPreferences

/**
 * A preferences node held in memory: tests of `DesktopSettingsStore` must never write into the real store of
 * the user (the Windows registry under `HKCU\Software\JavaSoft\Prefs`).
 */
internal class InMemoryPreferences(
    parent: AbstractPreferences? = null,
    name: String = "",
) : AbstractPreferences(parent, name) {

    private val values = HashMap<String, String>()
    private val children = HashMap<String, InMemoryPreferences>()

    override fun putSpi(key: String, value: String) {
        values[key] = value
    }

    override fun getSpi(key: String): String? = values[key]

    override fun removeSpi(key: String) {
        values.remove(key)
    }

    override fun removeNodeSpi() {
        values.clear()
    }

    override fun keysSpi(): Array<String> = values.keys.toTypedArray()

    override fun childrenNamesSpi(): Array<String> = children.keys.toTypedArray()

    override fun childSpi(name: String): AbstractPreferences = children.getOrPut(name) { InMemoryPreferences(this, name) }

    override fun syncSpi() = Unit

    override fun flushSpi() = Unit
}
