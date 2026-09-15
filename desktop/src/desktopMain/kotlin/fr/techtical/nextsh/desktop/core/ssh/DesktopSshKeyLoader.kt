// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import fr.techtical.nextsh.desktop.core.ssh.fido2.SkKeyHandle
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshPublicKey
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyFormat
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.keyprovider.PuTTYKeyFile
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.StringReader
import java.util.Base64

/**
 * Desktop SSHJ [KeyProvider] loader. Mirrors the Android equivalent at
 * `:app/core/ssh/SshKeyManager` but lives here so we don't leak SSHJ into
 * `:shared`. Both implementations stay in sync by design (any fix ported to
 * both).
 *
 * Parses an OpenSSH/PKCS8/PuTTY PEM string into the correct SSHJ file-backed
 * provider, optionally using a passphrase for encrypted keys, and returns a
 * provider that `SSHClient.authPublickey(username, provider)` accepts directly.
 *
 * SECURITY: the PEM arrives as a String (JVM-immutable, non-wipeable). SSHJ's
 * `FileKeyProvider.init` API only accepts `Reader`, so there is no byte-level
 * alternative. Risk accepted at the `VaultManager.getPrivateKey` boundary.
 */
object DesktopSshKeyLoader {

    /**
     * @param pem raw PEM content of the private key
     * @param passphrase optional passphrase: wiped by the caller
     * @throws IllegalArgumentException if the key format is not recognised
     * @throws java.io.IOException / SSHJ exceptions if the passphrase is wrong
     *         or the key is malformed: caller detects passphrase errors via
     *         [isLikelyPassphraseError].
     */
    fun loadKeyProviderFromString(
        pem: String,
        passphrase: CharArray? = null,
    ): KeyProvider {
        val pwFinder = passphrase?.let { PasswordUtils.createOneOff(it) }

        val format = KeyProviderUtil.detectKeyFileFormat(StringReader(pem), false)

        val keyFile: FileKeyProvider = when (format) {
            KeyFormat.OpenSSHv1 -> OpenSSHKeyV1KeyFile()
            KeyFormat.OpenSSH -> OpenSSHKeyFile()
            KeyFormat.PKCS8 -> PKCS8KeyFile()
            KeyFormat.PuTTY -> PuTTYKeyFile()
            else -> throw IllegalArgumentException("Format de clé non reconnu ($format)")
        }

        keyFile.init(StringReader(pem), pwFinder)
        return keyFile
    }

    /**
     * Loads a key provider that authenticates with an OpenSSH certificate.
     * The caller must pass the `-cert.pub` content alongside the private key.
     *
     * The certificate line format is `ssh-xxx-cert-v01@openssh.com AAAA... [comment]`:
     * we parse the Base64 blob into a PublicKey that SSHJ will present to the
     * server in place of the raw public key during auth. Signing is done with
     * the underlying private key.
     */
    fun loadKeyProviderWithCertificate(
        pem: String,
        certificatePem: String,
        passphrase: CharArray? = null,
    ): KeyProvider {
        val baseProvider = loadKeyProviderFromString(pem, passphrase)

        val certLine = certificatePem.trim().lines()
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            ?: throw IllegalArgumentException("Certificat vide ou invalide")

        val parts = certLine.split(" ")
        if (parts.size < 2) throw IllegalArgumentException("Format de certificat invalide")
        val certBase64 = parts[1]

        val certBytes = java.util.Base64.getDecoder().decode(certBase64)
        val certPublicKey = Buffer.PlainBuffer(certBytes).readPublicKey()

        return object : KeyProvider {
            override fun getPublic(): java.security.PublicKey = certPublicKey
            override fun getPrivate(): java.security.PrivateKey = baseProvider.private
            override fun getType(): KeyType = KeyType.fromKey(certPublicKey)
        }
    }

    /**
     * Construit un [SkKeyHandle] depuis un [SshKey] vault dont le type est SK_ED25519
     * ou SK_ECDSA_256 et dont [SshKey.fido2CredentialId] est non null.
     *
     * Pour ces clés, il n'y a PAS de clé privée à parser : la partie privée reste
     * sur le YubiKey. On reconstruit uniquement la clé publique depuis le champ
     * [SshKey.publicKey] (format OpenSSH : "sk-ssh-ed25519@openssh.com AAAA...").
     *
     * @param key  Entrée du vault avec keyType SK_* et fido2CredentialId non null.
     * @return [SkKeyHandle] prêt à passer à [SkAuthMethod], ou null si le parsing échoue.
     * @throws IllegalArgumentException si la clé n'est pas SK_* ou si fido2CredentialId est null.
     */
    fun loadSkKeyHandle(key: SshKey): SkKeyHandle {
        require(key.keyType == SshKeyType.SK_ED25519 || key.keyType == SshKeyType.SK_ECDSA_256) {
            "loadSkKeyHandle: keyType must be SK_ED25519 or SK_ECDSA_256, got ${key.keyType}"
        }
        require(key.fido2CredentialId != null) {
            "loadSkKeyHandle: fido2CredentialId must not be null for FIDO2 key ${key.id}"
        }

        // Parser la clé publique depuis la ligne authorized_keys (format OpenSSH)
        val skPublicKey = SkSshPublicKey.fromAuthorizedKeysLine(key.publicKey)
            ?: throw IllegalArgumentException(
                "loadSkKeyHandle: cannot parse SK public key for key ${key.id} (publicKey='${key.publicKey.take(60)}...')"
            )

        // Décoder le credentialId (stocké en Base64 dans la BDD)
        val credentialIdBytes = try {
            Base64.getDecoder().decode(key.fido2CredentialId)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(
                "loadSkKeyHandle: fido2CredentialId is not valid Base64 for key ${key.id}", e
            )
        }

        return SkKeyHandle(
            skPublicKey = skPublicKey,
            credentialId = credentialIdBytes,
            rpId = skPublicKey.application,
        )
    }

    /**
     * Heuristic: was [e] raised because the key needed a (different) passphrase?
     *
     * SSHJ does not throw a single canonical exception for "bad passphrase":
     * the raised class depends on key format: `net.schmizz.sshj.common.SSHException`
     * with a message mentioning password/passphrase, or wrapped
     * `BadPaddingException` / `InvalidKeyException` for AES-decrypt failure on
     * the key body. We match on the common markers plus `IOException` with a
     * passphrase-related message.
     */
    fun isLikelyPassphraseError(e: Throwable): Boolean {
        val chain = generateSequence<Throwable>(e) { it.cause }.toList()
        return chain.any { t ->
            val msg = t.message?.lowercase() ?: ""
            t is javax.crypto.BadPaddingException ||
                t is java.security.InvalidKeyException ||
                "password" in msg || "passphrase" in msg ||
                "decrypt" in msg || "tag mismatch" in msg
        }
    }
}
