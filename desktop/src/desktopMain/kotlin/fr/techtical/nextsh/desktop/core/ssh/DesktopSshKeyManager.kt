// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.shared.core.ssh.OpenSshPrivateKeyWriter
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.ssh.SshKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyFormat
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.StringReader
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Desktop SSH key management. Generates key pairs using JDK 17 native providers
 * (Ed25519 via JEP 339, RSA / ECDSA via SunEC) and serialises public keys in
 * OpenSSH format via SSHJ's [Buffer.PlainBuffer].
 */
class DesktopSshKeyManager : SshKeyManager {

    /**
     * The [comment] (the label typed by the user) is deliberately NOT written into the
     * key: both the public line and the private section carry the constant
     * [GENERATED_KEY_COMMENT], as on Android. A label with a line break or an invisible
     * format character would otherwise split or disguise the `authorized_keys` line.
     */
    @Suppress("UNUSED_PARAMETER")
    override suspend fun generateKeyPair(
        keyType: SshKeyType,
        comment: String,
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val keyPair = when (keyType) {
            // Force the i2p EdDSA provider so the resulting keys are instances of
            // `net.i2p.crypto.eddsa.EdDSAPublicKey`: SSHJ 0.38.0 can only encode
            // Ed25519 through that type. See Main.kt for provider registration.
            // Algorithm name is "EdDSA" (the generic name i2p exposes): not "Ed25519"
            // which the JDK native provider uses.
            SshKeyType.ED25519 -> KeyPairGenerator.getInstance(
                "EdDSA",
                net.i2p.crypto.eddsa.EdDSASecurityProvider.PROVIDER_NAME,
            ).generateKeyPair()
            SshKeyType.RSA_4096 -> KeyPairGenerator.getInstance("RSA").apply { initialize(4096) }.generateKeyPair()
            SshKeyType.ECDSA_256 -> generateEcdsa("secp256r1")
            SshKeyType.ECDSA_384 -> generateEcdsa("secp384r1")
            SshKeyType.ECDSA_521 -> generateEcdsa("secp521r1")
            SshKeyType.SK_ED25519, SshKeyType.SK_ECDSA_256 ->
                throw IllegalArgumentException("Les clés FIDO2 (sk-*) ne sont pas générables in-app")
        }
        val pem = if (keyType == SshKeyType.ED25519) {
            formatEd25519OpenSshV1(keyPair)
        } else {
            formatPkcs8Pem(keyPair)
        }
        pem to formatPublicKeyOpenSsh(keyPair)
    }

    override suspend fun extractPublicKey(
        privateKeyPem: String,
        passphrase: CharArray?,
    ): String = withContext(Dispatchers.IO) {
        val pwFinder = passphrase?.let { PasswordUtils.createOneOff(it) }
        val format = KeyProviderUtil.detectKeyFileFormat(StringReader(privateKeyPem), false)
        val keyFile = when (format) {
            KeyFormat.OpenSSHv1 -> com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile()
            KeyFormat.OpenSSH -> OpenSSHKeyFile()
            KeyFormat.PKCS8 -> PKCS8KeyFile()
            else -> throw IllegalArgumentException("Format de clé non reconnu ($format)")
        }
        keyFile.init(StringReader(privateKeyPem), pwFinder)
        val pubKey = keyFile.public
        passphrase?.fill('\u0000')
        val sshKeyType = KeyType.fromKey(pubKey)
        val encoded = Base64.getEncoder().encodeToString(
            Buffer.PlainBuffer().putPublicKey(pubKey).compactData,
        )
        "$sshKeyType $encoded nextsh-imported"
    }

    private fun generateEcdsa(curve: String): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()

    private fun formatPublicKeyOpenSsh(keyPair: KeyPair): String {
        val sshKeyType = KeyType.fromKey(keyPair.public)
        val encoded = Base64.getEncoder().encodeToString(
            Buffer.PlainBuffer().putPublicKey(keyPair.public).compactData,
        )
        return "$sshKeyType $encoded $GENERATED_KEY_COMMENT"
    }

    /** PKCS#8 PEM: works for RSA and EC, SSHJ reads it via `PKCS8KeyFile`. */
    private fun formatPkcs8Pem(keyPair: KeyPair): String {
        val encoded = Base64.getEncoder().encodeToString(keyPair.private.encoded)
        val sb = StringBuilder()
        sb.appendLine("-----BEGIN PRIVATE KEY-----")
        encoded.chunked(64).forEach { sb.appendLine(it) }
        sb.appendLine("-----END PRIVATE KEY-----")
        return sb.toString()
    }

    /**
     * OpenSSH v1 PEM of an Ed25519 keypair, written by the shared writer: the format ssh-keygen produces by
     * default and the only one SSHJ can round-trip for Ed25519 (`PKCS8KeyFile` doesn't understand the Ed25519
     * OID 1.3.101.112 in SSHJ 0.38.0). Requires the keypair to come from the i2p EdDSA provider: the seed is
     * read from it, the public key is computed from the seed.
     */
    private fun formatEd25519OpenSshV1(keyPair: KeyPair): String {
        val seed = (keyPair.private as net.i2p.crypto.eddsa.EdDSAPrivateKey).seed.copyOf() // 32 bytes
        return try {
            OpenSshPrivateKeyWriter.ed25519(seed, GENERATED_KEY_COMMENT)
        } finally {
            seed.fill(0)
        }
    }

    companion object {
        /** Constant comment of every generated key (public line and private section). */
        const val GENERATED_KEY_COMMENT = "nextsh-generated"
    }
}
