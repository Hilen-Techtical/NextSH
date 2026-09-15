// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh.fido2

import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshPublicKey

/**
 * Handle transportant les metadonnees d'une cle SK-* depuis le vault,
 * sans contenir de matiere privee (la cle privee reste sur le YubiKey).
 *
 * Produit par [fr.techtical.nextsh.desktop.core.ssh.DesktopSshKeyLoader.loadSkKeyHandle]
 * pour les cles dont [fr.techtical.nextsh.shared.domain.model.SshKey.keyType] est
 * SK_ED25519 ou SK_ECDSA_256 et dont [fr.techtical.nextsh.shared.domain.model.SshKey.fido2CredentialId]
 * est non null.
 *
 * @param skPublicKey  Cle publique SSH sk-* reconstruite depuis le blob vault (publicKey field).
 * @param credentialId Identifiant de credential FIDO2 (CBOR bytes, stocke en Base64 dans la BDD).
 * @param rpId         RP-ID pour l'assertion CTAP2 GetAssertion (ex : "ssh:").
 *                     Correspond a [SkSshPublicKey.application].
 */
data class SkKeyHandle(
    val skPublicKey: SkSshPublicKey,
    val credentialId: ByteArray,
    val rpId: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SkKeyHandle) return false
        return credentialId.contentEquals(other.credentialId) &&
            rpId == other.rpId &&
            skPublicKey === other.skPublicKey
    }

    override fun hashCode(): Int {
        var result = credentialId.contentHashCode()
        result = 31 * result + rpId.hashCode()
        return result
    }
}
