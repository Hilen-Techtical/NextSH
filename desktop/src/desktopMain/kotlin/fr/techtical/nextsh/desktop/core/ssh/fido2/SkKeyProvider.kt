// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh.fido2

import fr.techtical.nextsh.desktop.core.auth.fido2.Fido2Signer
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshPublicKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.io.IOException
import java.security.PrivateKey
import java.security.PublicKey

/**
 * Implementation [KeyProvider] SSHJ pour les cles SK (FIDO2 / YubiKey).
 *
 * Utilise par [net.schmizz.sshj.SSHClient.authPublickey] pour l'authentification
 * "publickey" standard. MAIS pour les cles FIDO2 sk-*, la voie recommendee est
 * d'utiliser [fr.techtical.nextsh.shared.core.ssh.fido2.SkAuthMethod] directement via
 * [net.schmizz.sshj.SSHClient.auth] : cela evite le pipeline KeyAlgorithm de SSHJ
 * et donne un controle total sur le challenge/response FIDO2.
 *
 * Ce [SkKeyProvider] est fourni pour compatibilite avec les helpers SSHJ qui
 * demandent un [KeyProvider] (ex. tests, outils de diagnostic). En production,
 * [DesktopSshSessionManager.connectWithFido2] utilisera [SkAuthMethod] directement.
 *
 * [getPublic] : reconstruit l'[EdDSAPublicKey] depuis le blob wire [SkSshPublicKey].
 * [getPrivate] : retourne un [SkFido2PrivateKey] factice (opaque, non JCA-standard).
 * [getType] : retourne [KeyType.ED25519] (le KeyType SSHJ le plus proche pour ed25519-sk).
 */
class SkKeyProvider(
    private val skPublicKey: SkSshPublicKey,
    private val credentialId: ByteArray,
    private val fido2Signer: Fido2Signer,
) : KeyProvider {

    private val edPublicKey: EdDSAPublicKey by lazy {
        val spec = EdDSANamedCurveTable.getByName("Ed25519")
        EdDSAPublicKey(EdDSAPublicKeySpec(skPublicKey.rawKeyData, spec))
    }

    /**
     * Retourne la cle publique ED25519 reconstructe depuis le blob wire sk-*.
     * Le type JCA est [EdDSAPublicKey] (i2p) : compatible avec SSHJ pour la nego.
     */
    @Throws(IOException::class)
    override fun getPublic(): PublicKey = edPublicKey

    /**
     * Retourne un [SkFido2PrivateKey] factice qui transporte credentialId + fidoManager.
     * Ce PrivateKey ne contient aucune donnee secrete : la cle privee reste sur le YubiKey.
     */
    @Throws(IOException::class)
    override fun getPrivate(): PrivateKey = SkFido2PrivateKey(
        credentialId = credentialId,
        publicKey = skPublicKey,
        fido2Signer = fido2Signer,
    )

    /**
     * Retourne [KeyType.ED25519] : le type SSHJ le plus proche pour les cles ed25519-sk.
     * Le KeyAlgorithm "sk-ssh-ed25519@openssh.com" est gere par [SkKeyAlgorithm] et
     * [SkAuthMethod], pas par ce KeyType.
     */
    override fun getType(): KeyType = KeyType.ED25519
}
