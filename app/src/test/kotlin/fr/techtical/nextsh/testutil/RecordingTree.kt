// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.testutil

import timber.log.Timber

/**
 * Collects what the app logs through Timber, to prove a code path never puts a secret (or an
 * untrusted value) in the log. Plant it with [Timber.plant] and always [Timber.uproot] it in a
 * finally block: Timber's forest is global to the test JVM.
 */
internal class RecordingTree : Timber.Tree() {

    data class Entry(val priority: Int, val message: String, val throwable: Throwable?)

    val entries = mutableListOf<Entry>()

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        entries += Entry(priority, message, t)
    }
}
