// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.ssh.fido2

/**
 * Types de cles SSH FIDO2 (security keys).
 * OpenSSH 8.2+ supporte ces algorithmes pour les cles materielles.
 */
enum class SkKeyType(val sshName: String, val curveName: String?) {
    SK_ED25519("sk-ssh-ed25519@openssh.com", null),
    SK_ECDSA_256("sk-ecdsa-sha2-nistp256@openssh.com", "nistp256"),
}
