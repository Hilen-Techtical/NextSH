// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

class EphemeralKeypair(val publicKey: ByteArray, private val privateKey: ByteArray) {
    fun privateKey(): ByteArray = privateKey
    fun wipePrivate() { privateKey.fill(0) }
}
