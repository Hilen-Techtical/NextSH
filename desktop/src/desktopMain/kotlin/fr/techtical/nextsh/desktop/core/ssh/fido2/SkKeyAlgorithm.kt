// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh.fido2

import com.hierynomus.sshj.key.KeyAlgorithm
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshPublicKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.signature.Signature
import java.security.GeneralSecurityException
import java.security.PublicKey

/**
 * Implementation [KeyAlgorithm] pour "sk-ssh-ed25519@openssh.com".
 *
 * Enregistree dans la [net.schmizz.sshj.DefaultConfig] via [registerIn] pour que SSHJ
 * negocie cet algorithme pendant le handshake d'authentification.
 *
 * Responsabilites :
 * - [readPubKeyFromBuffer] : deserialise la cle publique ED25519 brute (32 bytes) depuis
 *   le wire SSH sk-ssh-ed25519@openssh.com → retourne un [EdDSAPublicKey] standard JCA.
 *   Le champ "application" (ex. "ssh:") est lu et ignore : il est transporte dans
 *   [SkFido2PrivateKey] pour le rpId GetAssertion.
 * - [putPubKeyIntoBuffer] : serialise un [EdDSAPublicKey] dans le format wire sk-ed25519.
 *   Note : l'application est ecrite comme "ssh:" (valeur par defaut : peut etre override).
 * - [newSignature] : cree une instance [SkFido2Signature] qui signe via YubiKey CTAP2.
 *   Les callbacks touch sont injectes via [Factory] lors de la creation du SSHClient.
 *
 * NOTE importante sur [getKeyFormat] : on retourne [KeyType.ED25519] pour reutiliser
 * la serialisation ed25519 de SSHJ (readPubKeyFromBuffer du KeyType.ED25519 serait
 * faux pour sk- car le format est different). On override readPubKeyFromBuffer ici.
 * getKeyFormat() est utilise par SSHJ pour le fingerprinting de cle hote : non applicable
 * ici (c'est une cle user, pas une cle hote). On retourne ED25519 par compatibilite.
 */
class SkKeyAlgorithm(
    private val skKeyType: SkKeyType = SkKeyType.SK_ED25519,
    private val signatureFactory: Factory.Named<Signature>,
) : KeyAlgorithm {

    override fun getKeyAlgorithm(): String = skKeyType.sshName

    override fun getKeyFormat(): KeyType = KeyType.ED25519

    /**
     * Deserialise la partie cle publique du wire SSH sk-ssh-ed25519@openssh.com.
     *
     * Le buffer a deja consomme le string "sk-ssh-ed25519@openssh.com" (le type).
     * On lit ensuite :
     *   string ed25519_public_key_bytes (32 bytes)
     *   string application (ex. "ssh:")
     *
     * On retourne un EdDSAPublicKey reconnu par le reste de SSHJ pour les verifications
     * de signature cote client (fingerprinting, comparaison avec le vault).
     */
    @Throws(GeneralSecurityException::class)
    override fun readPubKeyFromBuffer(buf: Buffer<*>): PublicKey {
        return try {
            val rawKey = buf.readStringAsBytes()   // 32 bytes ed25519 public key
            buf.readString()                        // application (ex. "ssh:") : ignore ici

            val spec = EdDSANamedCurveTable.getByName("Ed25519")
            val keySpec = EdDSAPublicKeySpec(rawKey, spec)
            EdDSAPublicKey(keySpec)
        } catch (e: Buffer.BufferException) {
            throw GeneralSecurityException("Failed to read sk-ssh-ed25519 public key", e)
        }
    }

    /**
     * Serialise une cle publique ED25519 dans le format wire sk-ssh-ed25519.
     *
     * Note : l'application "ssh:" est hard-coded ici. Si une application differente
     * est requise, elle sera disponible dans [SkFido2PrivateKey.publicKey.application]
     * via [SkFido2Signature]. Pour la serialisation wire (feeler request), "ssh:" est
     * la valeur standard des cles SSH FIDO2.
     */
    override fun putPubKeyIntoBuffer(pk: PublicKey, buf: Buffer<*>) {
        val edKey = pk as EdDSAPublicKey
        buf.putString(edKey.abyte) // 32 bytes raw ed25519 key
        buf.putString("ssh:")       // application par defaut
    }

    override fun newSignature(): Signature = signatureFactory.create()

    companion object {

        /**
         * Fabrique une [Factory.Named] de [KeyAlgorithm] pour sk-ssh-ed25519.
         * Injecter dans [net.schmizz.sshj.DefaultConfig.setKeyAlgorithms].
         *
         * @param onTouchRequired Callback appele avant le touch YubiKey (pour dialog UI)
         * @param onTouchDone     Callback appele apres reception de la reponse
         */
        fun factory(
            onTouchRequired: (deviceLabel: String) -> Unit = {},
            onTouchDone: () -> Unit = {},
        ): Factory.Named<KeyAlgorithm> {
            return object : Factory.Named<KeyAlgorithm> {
                override fun getName(): String = SkKeyType.SK_ED25519.sshName
                override fun create(): KeyAlgorithm = SkKeyAlgorithm(
                    skKeyType = SkKeyType.SK_ED25519,
                    signatureFactory = object : Factory.Named<Signature> {
                        override fun getName(): String = SkKeyType.SK_ED25519.sshName
                        override fun create(): Signature =
                            SkFido2Signature(SkKeyType.SK_ED25519, onTouchRequired, onTouchDone)
                    },
                )
            }
        }

        /**
         * Enregistre le KeyAlgorithm sk-ssh-ed25519 dans un [net.schmizz.sshj.DefaultConfig] existant,
         * en prepend sur la liste (priorite haute pour la negotiation de l'algorithme).
         *
         * Appeler avant [net.schmizz.sshj.SSHClient.connect] pour que le handshake
         * inclue "sk-ssh-ed25519@openssh.com" dans la liste des algorithmes proposes.
         *
         * @param config Le [net.schmizz.sshj.DefaultConfig] a modifier
         * @param onTouchRequired Callback appele avant le touch YubiKey
         * @param onTouchDone     Callback appele apres reception de la reponse YubiKey
         */
        fun registerIn(
            config: net.schmizz.sshj.DefaultConfig,
            onTouchRequired: (deviceLabel: String) -> Unit = {},
            onTouchDone: () -> Unit = {},
        ) {
            val existing = config.keyAlgorithms.toMutableList()
            existing.add(0, factory(onTouchRequired, onTouchDone))
            config.setKeyAlgorithms(existing)
        }
    }
}
