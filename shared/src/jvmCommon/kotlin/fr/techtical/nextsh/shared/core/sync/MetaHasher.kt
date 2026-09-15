// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import java.security.MessageDigest
import java.util.Base64

private val urlSafeEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

internal object MetaHasher {
    /** SHA-256 of [bytes], Base64 URL-safe without padding. */
    fun sha256Base64(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return urlSafeEncoder.encodeToString(digest)
    }
}
