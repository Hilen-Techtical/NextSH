// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.domain.ssh

import fr.techtical.nextsh.shared.domain.model.SshKeyType

interface SshKeyManager {
    /** Returns pair of (privateKeyPem, publicKeyOpenSsh). */
    suspend fun generateKeyPair(keyType: SshKeyType, comment: String): Pair<String, String>
    suspend fun extractPublicKey(privateKeyPem: String, passphrase: CharArray? = null): String
}
