// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.domain.model.SshKeyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.common.KeyType
import timber.log.Timber
import java.io.StringWriter
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Génération et gestion de clés SSH.
 * - Génération Ed25519, RSA-4096, ECDSA
 * - Sérialisation format OpenSSH
 * - Import depuis PEM / id_rsa
 */
@Singleton
class SshKeyManager @Inject constructor() : fr.techtical.nextsh.shared.domain.ssh.SshKeyManager {

    // ── Shared interface implementation ───────────────────────────────────────────

    /**
     * Shared interface: generates a key pair and returns (privateKeyPem, publicKeyOpenSsh).
     */
    override suspend fun generateKeyPair(
        keyType: SshKeyType,
        comment: String,
    ): Pair<String, String> {
        val result = generateKeyPair(label = comment, keyType = keyType)
        return result.privateKeyPem to result.sshKey.publicKey
    }

    /**
     * Shared interface: extracts public key from PEM, throws if not possible.
     */
    override suspend fun extractPublicKey(
        privateKeyPem: String,
        passphrase: CharArray?,
    ): String {
        return extractPublicKeyFromPem(privateKeyPem, passphrase)
            ?: throw IllegalArgumentException("Cannot extract public key from provided PEM")
    }

    data class GeneratedKeyPair(
        val sshKey: SshKey,
        val privateKeyPem: String,  // À stocker chiffré dans le vault
    )

    /**
     * Génère une paire de clés SSH.
     * Par défaut Ed25519 si supporté, sinon RSA-4096.
     */
    suspend fun generateKeyPair(
        label: String,
        keyType: SshKeyType = SshKeyType.ED25519,
    ): GeneratedKeyPair = withContext(Dispatchers.IO) {
        val keyPair = when (keyType) {
            SshKeyType.ED25519 -> generateEd25519KeyPair()
            SshKeyType.RSA_4096 -> generateRsaKeyPair(4096)
            SshKeyType.ECDSA_256 -> generateEcdsaKeyPair("secp256r1")
            SshKeyType.ECDSA_384 -> generateEcdsaKeyPair("secp384r1")
            SshKeyType.ECDSA_521 -> generateEcdsaKeyPair("secp521r1")
            SshKeyType.SK_ED25519,
            SshKeyType.SK_ECDSA_256 -> throw IllegalArgumentException(
                "Les clés FIDO2 (sk-*) ne peuvent pas être générées in-app : utilisez ssh-keygen sur un poste avec la clé matérielle"
            )
        }

        val publicKeyOpenSsh = formatPublicKeyOpenSsh(keyPair, keyType)
        val privateKeyPem = if (keyType == SshKeyType.ED25519) {
            formatEd25519OpenSshV1(keyPair, label)
        } else {
            formatPrivateKeyPem(keyPair)
        }

        val id = UUID.randomUUID().toString()

        Timber.i("Generated $keyType key pair: $label")

        GeneratedKeyPair(
            sshKey = SshKey(
                id = id,
                label = label,
                keyType = keyType,
                publicKey = publicKeyOpenSsh,
                isBiometric = false,
                keystoreAlias = null,
            ),
            privateKeyPem = privateKeyPem,
        )
    }

    /**
     * Extrait la clé publique OpenSSH depuis un PEM privé.
     */
    suspend fun extractPublicKeyFromPem(
        privateKeyPem: String,
        passphrase: CharArray? = null,
    ): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            val keyProvider = loadKeyProviderFromString(privateKeyPem, passphrase)
            passphrase?.fill('\u0000')

            val pubKey = keyProvider.public
            val keyType = KeyType.fromKey(pubKey)
            val encoded = Base64.getEncoder().encodeToString(
                net.schmizz.sshj.common.Buffer.PlainBuffer().putPublicKey(pubKey).compactData
            )
            "${keyType.toString()} $encoded nextsh-imported"
        } catch (e: Exception) {
            passphrase?.fill('\u0000')
            Timber.e(e, "Failed to extract public key from PEM")
            null
        }
    }

    /**
     * Charge un KeyProvider combinant une clé privée et un certificat OpenSSH.
     *
     * Le certificat (format "ssh-xxx-cert-v01@openssh.com AAAA...") est parsé pour en extraire
     * la PublicKey signée par la CA. SSHJ l'envoie ensuite au serveur à la place de la clé
     * publique brute, ce qui permet l'auth par certificat.
     *
     * @param privateKeyPem   Contenu PEM de la clé privée
     * @param certificatePem  Contenu du fichier -cert.pub (une ligne OpenSSH)
     * @param passphrase      Passphrase optionnelle de la clé privée (wipée après usage)
     */
    fun loadKeyProviderWithCertificate(
        privateKeyPem: String,
        certificatePem: String,
        passphrase: CharArray? = null,
    ): net.schmizz.sshj.userauth.keyprovider.KeyProvider {
        // Charger la clé privée normalement
        val baseProvider = loadKeyProviderFromString(privateKeyPem, passphrase)

        // Parser le certificat OpenSSH :
        // format attendu : "<algo> <base64> [comment]"
        val certLine = certificatePem.trim().lines()
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            ?: throw IllegalArgumentException("Certificat vide ou invalide")

        val parts = certLine.split(" ")
        if (parts.size < 2) throw IllegalArgumentException("Format de certificat invalide")
        val certBase64 = parts[1]

        val certBytes = java.util.Base64.getDecoder().decode(certBase64)
        val certPublicKey = net.schmizz.sshj.common.Buffer.PlainBuffer(certBytes)
            .readPublicKey()

        Timber.d("Certificate loaded, algo: ${certPublicKey.algorithm}")

        // Wrapper : retourne le cert comme PublicKey, la clé privée pour signer
        return object : net.schmizz.sshj.userauth.keyprovider.KeyProvider {
            override fun getPublic(): java.security.PublicKey = certPublicKey
            override fun getPrivate(): java.security.PrivateKey = baseProvider.private
            override fun getType(): net.schmizz.sshj.common.KeyType =
                net.schmizz.sshj.common.KeyType.fromKey(certPublicKey)
        }
    }

    /**
     * Charge un KeyProvider depuis le contenu string d'une clé privée.
     * Utilise KeyProviderUtil.detectKeyFileFormat() pour auto-détecter le format,
     * puis instancie le bon provider.
     *
     * Formats supportés : OpenSSH v1, PKCS#8, PKCS#1 (RSA/EC/DSA legacy PEM).
     *
     * SECURITY: La clé PEM est reçue en String (immutable, non wipeable du heap JVM).
     * SSHJ FileKeyProvider n'accepte que Reader, pas de ByteArray alternative.
     * Risque accepté (API limitation SSHJ 0.38.0).
     */
    fun loadKeyProviderFromString(
        privateKeyContent: String,
        passphrase: CharArray? = null,
    ): net.schmizz.sshj.userauth.keyprovider.KeyProvider {
        val pwFinder = if (passphrase != null) {
            net.schmizz.sshj.userauth.password.PasswordUtils.createOneOff(passphrase)
        } else null

        // Auto-détection du format via SSHJ
        val format = net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
            .detectKeyFileFormat(java.io.StringReader(privateKeyContent), false)

        Timber.d("Detected key format: $format")

        val keyFile: net.schmizz.sshj.userauth.keyprovider.FileKeyProvider = when (format) {
            net.schmizz.sshj.userauth.keyprovider.KeyFormat.OpenSSHv1 ->
                com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile()
            net.schmizz.sshj.userauth.keyprovider.KeyFormat.OpenSSH ->
                net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile()
            net.schmizz.sshj.userauth.keyprovider.KeyFormat.PKCS8 ->
                net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile()
            net.schmizz.sshj.userauth.keyprovider.KeyFormat.PuTTY ->
                net.schmizz.sshj.userauth.keyprovider.PuTTYKeyFile()
            else ->
                throw IllegalArgumentException("Format de clé non reconnu ($format)")
        }

        // Le Reader est consommé par detectKeyFileFormat, on en crée un nouveau
        keyFile.init(java.io.StringReader(privateKeyContent), pwFinder)
        return keyFile
    }

    /**
     * Formate une clé publique EC P-256 du Keystore au format OpenSSH.
     * Format: "ecdsa-sha2-nistp256 <base64> <comment>"
     */
    fun formatKeystorePublicKeyOpenSsh(publicKey: java.security.PublicKey, comment: String = "nextsh-biometric"): String {
        val sshKeyType = net.schmizz.sshj.common.KeyType.fromKey(publicKey)
        val encoded = Base64.getEncoder().encodeToString(
            net.schmizz.sshj.common.Buffer.PlainBuffer().putPublicKey(publicKey).compactData
        )
        return "${sshKeyType.toString()} $encoded $comment"
    }

    /**
     * Crée un KeyProvider SSHJ backed par une clé du Keystore Android.
     * La clé privée reste dans le Keystore ; les opérations de signature
     * passent par le Keystore hardware (TEE/StrongBox).
     *
     * IMPORTANT: L'appelant doit d'abord authentifier via BiometricPrompt
     * avant d'utiliser ce KeyProvider pour la signature SSH.
     *
     * @param alias Alias de la clé dans le Keystore Android
     * @param publicKey Clé publique correspondante (pour envoyer au serveur)
     * @param privateKey Clé privée du Keystore (ne quitte pas le hardware)
     */
    fun getKeystoreKeyProvider(
        alias: String,
        publicKey: java.security.PublicKey,
        privateKey: java.security.PrivateKey,
    ): net.schmizz.sshj.userauth.keyprovider.KeyProvider {
        return object : net.schmizz.sshj.userauth.keyprovider.KeyProvider {
            override fun getPublic(): java.security.PublicKey = publicKey
            override fun getPrivate(): java.security.PrivateKey = privateKey
            override fun getType(): net.schmizz.sshj.common.KeyType =
                net.schmizz.sshj.common.KeyType.fromKey(publicKey)
        }
    }

    // ── Key generation ──────────────────────────────────────────────────────────

    private fun generateEd25519KeyPair(): KeyPair {
        // Force the i2p EdDSA provider: SSHJ 0.38.0's buffer encoder casts to
        // `net.i2p.crypto.eddsa.EdDSAPublicKey`, which the JDK/Android native
        // Ed25519 providers do NOT produce. The provider is registered at app
        // startup (App.setupBouncyCastleProvider).
        // Algorithm "EdDSA" (the name i2p registers), not "Ed25519" which is
        // the JDK native provider's algo.
        val kpg = KeyPairGenerator.getInstance(
            "EdDSA",
            net.i2p.crypto.eddsa.EdDSASecurityProvider.PROVIDER_NAME,
        )
        return kpg.generateKeyPair()
    }

    private fun generateRsaKeyPair(bits: Int): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(bits)
        return kpg.generateKeyPair()
    }

    private fun generateEcdsaKeyPair(curve: String): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(curve))
        return kpg.generateKeyPair()
    }

    // ── Serialization ───────────────────────────────────────────────────────────

    private fun formatPublicKeyOpenSsh(keyPair: KeyPair, keyType: SshKeyType): String {
        val pubKey = keyPair.public
        val sshKeyType = KeyType.fromKey(pubKey)
        val encoded = Base64.getEncoder().encodeToString(
            net.schmizz.sshj.common.Buffer.PlainBuffer().putPublicKey(pubKey).compactData
        )
        return "${sshKeyType.toString()} $encoded nextsh-generated"
    }

    private fun formatPrivateKeyPem(keyPair: KeyPair): String {
        // Format PKCS#8 PEM
        val encoded = Base64.getEncoder().encodeToString(keyPair.private.encoded)
        val sb = StringBuilder()
        sb.appendLine("-----BEGIN PRIVATE KEY-----")
        encoded.chunked(64).forEach { sb.appendLine(it) }
        sb.appendLine("-----END PRIVATE KEY-----")
        return sb.toString()
    }

    /**
     * Serialises an Ed25519 keypair in OpenSSH v1 format: the only format SSHJ
     * 0.38.0 can round-trip for Ed25519 (PKCS8 chokes on OID 1.3.101.112).
     * Mirrors the Desktop implementation; see `DesktopSshKeyManager` for the
     * format spec reference.
     */
    private fun formatEd25519OpenSshV1(keyPair: KeyPair, comment: String): String {
        val privKey = keyPair.private as net.i2p.crypto.eddsa.EdDSAPrivateKey
        val pubKey = keyPair.public as net.i2p.crypto.eddsa.EdDSAPublicKey
        val seed = privKey.seed
        val A = pubKey.abyte

        val publicKeyBlock = net.schmizz.sshj.common.Buffer.PlainBuffer().apply {
            putString("ssh-ed25519")
            putBytes(A)
        }.compactData

        val checkInt = java.security.SecureRandom().nextInt()
        val privateBuf = net.schmizz.sshj.common.Buffer.PlainBuffer()
        privateBuf.putUInt32(checkInt.toLong() and 0xFFFFFFFFL)
        privateBuf.putUInt32(checkInt.toLong() and 0xFFFFFFFFL)
        privateBuf.putString("ssh-ed25519")
        privateBuf.putBytes(A)
        privateBuf.putBytes(seed + A)
        privateBuf.putString(comment.ifBlank { "nextsh-generated" })
        var padByte = 1
        while (privateBuf.wpos() % 8 != 0) {
            privateBuf.putByte(padByte++.toByte())
        }
        val privateSection = privateBuf.compactData

        val container = net.schmizz.sshj.common.Buffer.PlainBuffer()
        container.putRawBytes("openssh-key-v1\u0000".toByteArray(Charsets.ISO_8859_1))
        container.putString("none")
        container.putString("none")
        container.putString("")
        container.putUInt32(1L)
        container.putBytes(publicKeyBlock)
        container.putBytes(privateSection)

        val b64 = Base64.getEncoder().encodeToString(container.compactData)
        return buildString {
            appendLine("-----BEGIN OPENSSH PRIVATE KEY-----")
            b64.chunked(70).forEach { appendLine(it) }
            appendLine("-----END OPENSSH PRIVATE KEY-----")
        }
    }
}
