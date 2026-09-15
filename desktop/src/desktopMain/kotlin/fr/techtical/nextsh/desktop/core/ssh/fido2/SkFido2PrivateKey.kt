// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh.fido2

import fr.techtical.nextsh.desktop.core.auth.fido2.Fido2Signer
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshPublicKey
import java.security.PrivateKey

/**
 * Cle privee FIDO2 factice pour SSHJ.
 *
 * SSHJ exige un [PrivateKey] pour authentifier via KeyProvider, mais pour les cles
 * FIDO2 (sk-ssh-ed25519@openssh.com), la cle privee reste sur le YubiKey et n'est
 * jamais exportee. Ce wrapper transporte les metadonnees necessaires a la signature
 * CTAP2 :
 *  - [credentialId] : id de la credential FIDO2 (depuis la cle publique sk-*)
 *  - [publicKey] : cle publique SkSshPublicKey pour l'application et le keyType
 *  - [fido2Signer] : interface [Fido2Signer] pour dispatcher le GetAssertion
 *    (implementation de production : [fr.techtical.nextsh.desktop.core.auth.fido2.YubiKitFido2Signer])
 *
 * Ce wrapper n'est PAS serialisable et ne contient aucune donnee secrete.
 * Son algorithm est "FIDO2-SK" (valeur arbitraire non reconnue par JCA :
 * le pipeline standard de javax.crypto.Signature ne sera jamais utilise sur cet objet).
 *
 * NOTE : dans le flow FIDO2 Desktop, [SkFido2PrivateKey] est passe a [SkFido2Signature]
 * via [initSign], qui le castera et utilisera [fido2Signer] pour signer le challenge.
 * Ce n'est pas un usage standard de JCA : c'est un transport opaque.
 */
class SkFido2PrivateKey(
    val credentialId: ByteArray,
    val publicKey: SkSshPublicKey,
    val fido2Signer: Fido2Signer,
) : PrivateKey {

    override fun getAlgorithm(): String = "FIDO2-SK"
    override fun getFormat(): String = "NONE"

    /**
     * Retourne null : la cle privee n'est jamais exportee.
     * La signature est produite sur le YubiKey via CTAP2.
     */
    override fun getEncoded(): ByteArray? = null
}
