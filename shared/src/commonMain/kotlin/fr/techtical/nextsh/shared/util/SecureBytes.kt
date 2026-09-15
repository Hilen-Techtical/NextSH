// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.util

object SecureBytes {
    fun wipe(data: ByteArray) {
        data.fill(0)
    }
    fun wipe(data: CharArray) {
        data.fill('\u0000')
    }
}
