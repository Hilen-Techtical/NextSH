// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.ssh

import com.hierynomus.sshj.transport.cipher.BlockCiphers
import com.hierynomus.sshj.transport.cipher.ChachaPolyCiphers
import com.hierynomus.sshj.transport.cipher.GcmCiphers
import com.hierynomus.sshj.userauth.keyprovider.bcrypt.BCrypt
import fr.techtical.nextsh.shared.core.backup.BackupLimits
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.transport.cipher.Cipher
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.math.BigInteger
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64

/** Outcome of [OpenSshKeyV1Reader.read]. */
internal sealed class OpenSshRead {
    /** The file cannot give a proven key: [outcome] says why. */
    class Failed(val outcome: SshPublicKeyDerivation) : OpenSshRead()

    /** The private key, and the public key copy of the clear envelope in canonical encoding. */
    class Read(val material: PrivateKeyMaterial, val envelope: ByteArray) : OpenSshRead()
}

/**
 * Bounded reader of the OpenSSH v1 private key format (`openssh-key-v1`, PROTOCOL.key of OpenSSH), used
 * INSTEAD of SSHJ's `OpenSSHKeyV1KeyFile`, which (0.38) takes the public key from the clear envelope without
 * comparing it to the private key, runs bcrypt with the rounds of the file whatever their number (even
 * without a passphrase), and allocates lengths announced by the file. Here:
 * - every length is bounded before allocation ([SshWireReader]);
 * - bcrypt rounds over [SshPublicKeyDeriver.MAX_BCRYPT_ROUNDS] give `TooCostly` and the KDF is never run,
 *   checked before the cipher, KDF and key type lists; an encrypted key outside those lists gives
 *   `UnsupportedEncryption`; without a passphrase an encrypted key gives `NeedsPassphrase` without any KDF;
 * - the private key is returned as material, its public key is computed from it by the deriver.
 * Decryption reuses SSHJ's ciphers and bcrypt, exactly as SSHJ does, so a key opened here opens at connection.
 */
internal object OpenSshKeyV1Reader {

    private val MAGIC = "openssh-key-v1".encodeToByteArray() + byteArrayOf(0)
    private const val NONE = "none"
    private const val BCRYPT = "bcrypt"
    private const val MAX_NAME_BYTES = 64
    private const val MAX_KDF_OPTIONS_BYTES = 1024

    /** 16 384 bits (the OpenSSH ceiling) plus a sign byte. */
    const val MAX_RSA_MPINT_BYTES = SshPublicKeyDeriver.MAX_RSA_BITS / 8 + 1

    /** 521 bits rounded up, plus a sign byte. */
    private const val MAX_EC_SCALAR_BYTES = 67

    /** The ciphers SSHJ 0.38 accepts for a key file (same list as `OpenSSHKeyV1KeyFile`). */
    private val CIPHERS: Map<String, Factory.Named<Cipher>> = listOf<Factory.Named<Cipher>>(
        BlockCiphers.TripleDESCBC(),
        BlockCiphers.AES128CBC(),
        BlockCiphers.AES192CBC(),
        BlockCiphers.AES256CBC(),
        BlockCiphers.AES128CTR(),
        BlockCiphers.AES192CTR(),
        BlockCiphers.AES256CTR(),
        GcmCiphers.AES256GCM(),
        GcmCiphers.AES128GCM(),
        ChachaPolyCiphers.CHACHA_POLY_OPENSSH(),
    ).associateBy { it.name }

    /** Reads [body] (the base64-decoded content between the PEM lines). Throws [SshWireException] when malformed. */
    fun read(body: ByteArray, passphrase: CharArray?): OpenSshRead {
        val r = SshWireReader(body)
        if (!r.raw(MAGIC.size).contentEquals(MAGIC)) return failed(SshPublicKeyDerivation.Unreadable)
        val cipherName = r.text(MAX_NAME_BYTES)
        val kdfName = r.text(MAX_NAME_BYTES)
        val kdfOptions = r.string(MAX_KDF_OPTIONS_BYTES)
        if (r.uint32() != 1L) return failed(SshPublicKeyDerivation.Unreadable) // a file holds exactly one key
        val envelopeBlob = r.string(BackupLimits.MAX_PUBLIC_KEY_BLOB_BYTES)
        val section = r.string()
        val tag = r.rest()

        val encrypted = cipherName != NONE
        // SECURITY: the cost comes BEFORE any list (cipher, KDF, key type): bcrypt rounds over the bound are
        // refused even with a cipher or a key type this reader does not take, so that no other reader (SSHJ,
        // which runs any number of rounds for a cipher it knows) is ever handed such a file.
        var salt = ByteArray(0)
        var rounds = 0L
        if (encrypted && kdfName == BCRYPT) {
            val options = SshWireReader(kdfOptions)
            salt = options.string(MAX_KDF_OPTIONS_BYTES)
            rounds = options.uint32()
            if (options.remaining() != 0 || rounds < 1) return failed(SshPublicKeyDerivation.Unreadable)
            if (rounds > SshPublicKeyDeriver.MAX_BCRYPT_ROUNDS) return failed(SshPublicKeyDerivation.TooCostly)
        }

        val envelopeType = SshWireReader(envelopeBlob).text(MAX_NAME_BYTES)
        // sk-* (FIDO2: the private part is a handle on the authenticator), ssh-dss, certificates, future types.
        if (envelopeType !in SUPPORTED_TYPES) return failed(SshPublicKeyDerivation.Unsupported)
        val envelope = canonicalPublicBlob(envelopeBlob)

        val plain: ByteArray = if (!encrypted) {
            if (kdfName != NONE || kdfOptions.isNotEmpty() || tag.isNotEmpty()) return failed(SshPublicKeyDerivation.Unreadable)
            section
        } else {
            // An encrypted key outside the lists: never handed to another reader, the platform refuses it.
            if (kdfName != BCRYPT) return failed(SshPublicKeyDerivation.UnsupportedEncryption)
            val factory = CIPHERS[cipherName] ?: return failed(SshPublicKeyDerivation.UnsupportedEncryption)
            val cipher = factory.create()
            if (tag.size != cipher.authenticationTagSize) return failed(SshPublicKeyDerivation.Unreadable)
            if (passphrase == null || passphrase.isEmpty()) return failed(SshPublicKeyDerivation.NeedsPassphrase)
            when (val decrypted = decrypt(cipher, section, tag, salt, rounds.toInt(), passphrase)) {
                is Decryption.Refused -> return failed(decrypted.outcome)
                is Decryption.Plain -> decrypted.bytes
            }
        }
        try {
            return readPrivateSection(plain, encrypted, envelope)
        } finally {
            plain.fill(0) // the clear private section, encrypted in the file or not
        }
    }

    private fun failed(outcome: SshPublicKeyDerivation): OpenSshRead = OpenSshRead.Failed(outcome)

    private sealed class Decryption {
        class Plain(val bytes: ByteArray) : Decryption()

        class Refused(val outcome: SshPublicKeyDerivation) : Decryption()
    }

    /**
     * Same steps as SSHJ: bcrypt gives key then IV, the cipher decrypts in place. A cipher that cannot be set
     * up (no provider for it on this device) is [SshPublicKeyDerivation.UnsupportedEncryption]. Only a refused AEAD tag
     * is a [SshPublicKeyDerivation.WrongPassphrase] here (the check integers decide for the other ciphers);
     * any other failure is a broken file.
     */
    private fun decrypt(cipher: Cipher, section: ByteArray, tag: ByteArray, salt: ByteArray, rounds: Int, passphrase: CharArray): Decryption {
        val password = utf8(passphrase)
        val keyIv = ByteArray(cipher.getIVSize() + cipher.blockSize)
        try {
            BCrypt().pbkdf(password, salt, rounds, keyIv)
        } finally {
            password.fill(0)
        }
        val key = keyIv.copyOfRange(0, cipher.blockSize)
        val iv = keyIv.copyOfRange(cipher.blockSize, keyIv.size)
        keyIv.fill(0)
        try {
            try {
                cipher.init(Cipher.Mode.Decrypt, key, iv)
            } catch (e: Exception) {
                return Decryption.Refused(SshPublicKeyDerivation.UnsupportedEncryption)
            }
            val buffer = section + tag
            try {
                cipher.update(buffer, 0, section.size)
                return Decryption.Plain(buffer.copyOf(section.size))
            } catch (e: Exception) {
                if (cipher.authenticationTagSize > 0) return Decryption.Refused(SshPublicKeyDerivation.WrongPassphrase)
                return Decryption.Refused(SshPublicKeyDerivation.Unreadable)
            } finally {
                buffer.fill(0)
            }
        } finally {
            key.fill(0)
            iv.fill(0)
        }
    }

    private fun utf8(chars: CharArray): ByteArray {
        val encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        if (encoded.hasArray()) encoded.array().fill(0)
        return bytes
    }

    private fun readPrivateSection(plain: ByteArray, encrypted: Boolean, envelope: ByteArray): OpenSshRead {
        if (plain.size % 8 != 0) return failed(SshPublicKeyDerivation.Unreadable) // SSHJ refuses it too
        val r = SshWireReader(plain)
        if (r.uint32() != r.uint32()) {
            // The two check integers differ: a wrong passphrase (or a corrupted clear key).
            return failed(if (encrypted) SshPublicKeyDerivation.WrongPassphrase else SshPublicKeyDerivation.Unreadable)
        }
        val material = when (val type = r.text(MAX_NAME_BYTES)) {
            ED25519 -> {
                val publicCopy = r.string(ED25519_KEY_BYTES * 2)
                val privateKey = r.string(ED25519_KEY_BYTES * 4)
                if (publicCopy.size != ED25519_KEY_BYTES || privateKey.size != ED25519_KEY_BYTES * 2) {
                    return failed(SshPublicKeyDerivation.Unreadable)
                }
                // seed || A: only the seed is used, A is recomputed (the copies of the file are never trusted).
                val seed = privateKey.copyOfRange(0, ED25519_KEY_BYTES)
                privateKey.fill(0)
                PrivateKeyMaterial.Ed25519(seed)
            }
            RSA -> {
                val n = r.mpint(MAX_RSA_MPINT_BYTES)
                val e = r.mpint(MAX_RSA_MPINT_BYTES)
                val d = r.mpint(MAX_RSA_MPINT_BYTES)
                val iqmp = r.mpint(MAX_RSA_MPINT_BYTES)
                val p = r.mpint(MAX_RSA_MPINT_BYTES)
                val q = r.mpint(MAX_RSA_MPINT_BYTES)
                // Cheap consistency first: forged primes of full size would make the proof cost seconds.
                if (p.bitLength() < 2 || q.bitLength() < 2 || p.multiply(q) != n) return failed(SshPublicKeyDerivation.Unreadable)
                // CRT exponents computed like SSHJ does (RFC 8017, 3.2).
                PrivateKeyMaterial.Rsa(n, e, d, p, q, d.mod(p.subtract(BigInteger.ONE)), d.mod(q.subtract(BigInteger.ONE)), iqmp)
            }
            else -> {
                val curve = EcCurve.bySshType(type) ?: return failed(SshPublicKeyDerivation.Unsupported)
                if (r.text(MAX_NAME_BYTES) != curve.sshCurve) return failed(SshPublicKeyDerivation.Unreadable)
                r.string(curve.pointBytes) // public copy, never trusted: the key is d times the generator
                PrivateKeyMaterial.Ecdsa(curve, r.mpint(MAX_EC_SCALAR_BYTES))
            }
        }
        r.string() // comment
        val padding = r.rest()
        for (i in padding.indices) {
            if (padding[i] != (i + 1).toByte()) return failed(SshPublicKeyDerivation.Unreadable)
        }
        return OpenSshRead.Read(material, envelope)
    }

    /** The public key blob [blob] re-encoded canonically, after an exact structure check. */
    private fun canonicalPublicBlob(blob: ByteArray): ByteArray {
        val r = SshWireReader(blob)
        val type = r.text(MAX_NAME_BYTES)
        val out = SshWireWriter().string(type)
        when (type) {
            ED25519 -> {
                val a = r.string(ED25519_KEY_BYTES * 2)
                if (a.size != ED25519_KEY_BYTES) throw SshWireException()
                out.string(a)
            }
            RSA -> {
                val e = r.mpint(MAX_RSA_MPINT_BYTES)
                val n = r.mpint(MAX_RSA_MPINT_BYTES)
                out.mpint(e).mpint(n)
            }
            else -> {
                val curve = EcCurve.bySshType(type) ?: throw SshWireException()
                if (r.text(MAX_NAME_BYTES) != curve.sshCurve) throw SshWireException()
                val point = r.string(curve.pointBytes)
                if (point.size != curve.pointBytes) throw SshWireException()
                out.string(curve.sshCurve).string(point)
            }
        }
        if (r.remaining() != 0) throw SshWireException()
        return out.toByteArray()
    }

    internal const val ED25519 = "ssh-ed25519"
    internal const val RSA = "ssh-rsa"
    internal const val ED25519_KEY_BYTES = 32
    private val SUPPORTED_TYPES = setOf(ED25519, RSA) + EcCurve.entries.map { it.sshType }
}

/**
 * Writes OpenSSH v1 private keys, the format `ssh-keygen` writes by default and the only one SSHJ 0.38 reads
 * for Ed25519 (its PKCS#8 reader does not know the Ed25519 OID 1.3.101.112). One writer for both platforms.
 */
object OpenSshPrivateKeyWriter {

    private const val PEM_LINE_LENGTH = 70

    /**
     * The unencrypted OpenSSH v1 PEM of the Ed25519 key of 32 byte [seed], with [comment] in the private
     * section (`nextsh-generated` when blank). The public key is computed from the seed. [seed] is not kept.
     */
    fun ed25519(seed: ByteArray, comment: String): String {
        require(seed.size == OpenSshKeyV1Reader.ED25519_KEY_BYTES)
        val a = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
        val publicBlob = SshWireWriter().string(OpenSshKeyV1Reader.ED25519).string(a).toByteArray()
        val checkInt = SecureRandom().nextInt().toLong() and 0xFFFFFFFFL
        val commentBytes = comment.ifBlank { "nextsh-generated" }.encodeToByteArray()
        // seed || A, the 64 byte OpenSSH Ed25519 private key, in a buffer of its own that is wiped.
        val privateKey = ByteArray(OpenSshKeyV1Reader.ED25519_KEY_BYTES * 2)
        seed.copyInto(privateKey)
        a.copyInto(privateKey, OpenSshKeyV1Reader.ED25519_KEY_BYTES)
        // Sized beforehand so the buffers never grow (a growing buffer leaves copies of the key behind it).
        val section = SshWireWriter(initialCapacity = SECTION_FIXED_BYTES + commentBytes.size + 8)
        val container = SshWireWriter(initialCapacity = CONTAINER_FIXED_BYTES + publicBlob.size + SECTION_FIXED_BYTES + commentBytes.size + 8)
        var sectionBytes = ByteArray(0)
        var containerBytes = ByteArray(0)
        try {
            section.uint32(checkInt)
                .uint32(checkInt)
                .string(OpenSshKeyV1Reader.ED25519)
                .string(a)
                .string(privateKey)
                .string(commentBytes)
            var pad = 1
            while (section.size() % 8 != 0) section.raw(byteArrayOf(pad++.toByte()))
            sectionBytes = section.toByteArray()
            containerBytes = container
                .raw("openssh-key-v1".encodeToByteArray() + byteArrayOf(0))
                .string("none")
                .string("none")
                .string(ByteArray(0))
                .uint32(1)
                .string(publicBlob)
                .string(sectionBytes)
                .toByteArray()
            val b64 = Base64.getEncoder().encodeToString(containerBytes)
            return buildString {
                append("-----BEGIN OPENSSH PRIVATE KEY-----\n")
                b64.chunked(PEM_LINE_LENGTH).forEach { append(it).append('\n') }
                append("-----END OPENSSH PRIVATE KEY-----\n")
            }
        } finally {
            privateKey.fill(0)
            sectionBytes.fill(0)
            containerBytes.fill(0)
            section.wipe()
            container.wipe()
        }
    }

    /** Check integers, type name, public key and private key fields of an Ed25519 section, without the comment. */
    private const val SECTION_FIXED_BYTES = 4 + 4 + (4 + 11) + (4 + 32) + (4 + 64) + 4

    /** Magic, cipher, KDF, KDF options, key count and the two length prefixes of the container. */
    private const val CONTAINER_FIXED_BYTES = 15 + (4 + 4) + (4 + 4) + 4 + 4 + 4 + 4
}
