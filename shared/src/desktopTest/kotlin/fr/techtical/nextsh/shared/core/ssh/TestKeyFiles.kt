// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.ssh

import com.hierynomus.sshj.userauth.keyprovider.bcrypt.BCrypt
import fr.techtical.nextsh.shared.core.backup.KnownHostPortable
import net.schmizz.sshj.common.Buffer
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.EdECPrivateKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Key files written by the tests from keys the JDK generates at run time: no key material is ever written in
 * a source file. The OpenSSH writer here is independent from the reader under test (SSHJ buffers, JCA AES-CTR,
 * SSHJ bcrypt); the expected public key comes from [KnownHostPortable.toPortable], another code path.
 */
internal object TestKeyFiles {

    private val random = SecureRandom()

    // ---- keys ----

    fun ed25519(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    fun rsa(bits: Int = 2048): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()

    fun ec(curve: EcCurve): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve.jcaName)) }.generateKeyPair()

    /** The 32 byte seed of a JDK Ed25519 private key. */
    fun seedOf(pair: KeyPair): ByteArray = (pair.private as EdECPrivateKey).bytes.get()

    /** The expected SSH public key blob of [key], computed by [KnownHostPortable] (not by the code under test). */
    fun blobOf(key: PublicKey): ByteArray =
        Base64.getDecoder().decode(KnownHostPortable.toPortable(key.algorithm, key.encoded)!!.publicKey)

    fun blobBase64Of(key: PublicKey): String = Base64.getEncoder().encodeToString(blobOf(key))

    /** The uncompressed point of an EC public key, X and Y padded to the field size. */
    fun pointOf(key: ECPublicKey): ByteArray {
        val size = (key.params.curve.field.fieldSize + 7) / 8
        return byteArrayOf(4) + fixed(key.w.affineX, size) + fixed(key.w.affineY, size)
    }

    fun fixed(value: BigInteger, size: Int): ByteArray {
        val raw = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(size - raw.size) + raw
    }

    // ---- OpenSSH v1 ----

    /** The private fields of [pair] in an OpenSSH v1 private section (type first). */
    fun opensshPrivateFields(pair: KeyPair, curve: EcCurve? = null): ByteArray {
        val b = Buffer.PlainBuffer()
        when (val key = pair.private) {
            is EdECPrivateKey -> {
                val seed = key.bytes.get()
                val a = blobOf(pair.public).copyOfRange(4 + "ssh-ed25519".length + 4, 4 + "ssh-ed25519".length + 4 + 32)
                b.putString("ssh-ed25519").putBytes(a).putBytes(seed + a)
            }
            is RSAPrivateCrtKey -> b.putString("ssh-rsa")
                .putMPInt(key.modulus).putMPInt(key.publicExponent).putMPInt(key.privateExponent)
                .putMPInt(key.crtCoefficient).putMPInt(key.primeP).putMPInt(key.primeQ)
            is ECPrivateKey -> {
                val c = curve!!
                b.putString(c.sshType).putString(c.sshCurve).putBytes(pointOf(pair.public as ECPublicKey)).putMPInt(key.s)
            }
            else -> error("unsupported test key")
        }
        return b.compactData
    }

    /**
     * An OpenSSH v1 private key file. [envelope] is the clear public key copy (by default the key's own).
     * With [passphrase], the section is encrypted with aes256-ctr and bcrypt of [rounds] rounds, exactly as
     * ssh-keygen does. [kdfRoundsField] forces the rounds written in the file without running them.
     */
    fun openssh(
        privateFields: ByteArray,
        envelope: ByteArray,
        passphrase: String? = null,
        rounds: Int = 16,
        kdfRoundsField: Long? = null,
        checkInts: Pair<Long, Long> = 0x12345678L to 0x12345678L,
        cipherName: String? = null,
        comment: String = "test@nextsh",
        kdfName: String? = null,
    ): String {
        val blockSize = if (passphrase == null && kdfRoundsField == null) 8 else 16
        val section = Buffer.PlainBuffer()
            .putUInt32(checkInts.first).putUInt32(checkInts.second)
            .putRawBytes(privateFields)
            .putString(comment)
        var pad = 1
        while (section.available() % blockSize != 0) section.putByte((pad++).toByte())
        var sectionBytes = section.compactData

        val kdfOptions: ByteArray
        val cipher: String
        val kdf: String
        if (passphrase == null && kdfRoundsField == null) {
            cipher = cipherName ?: "none"
            kdf = "none"
            kdfOptions = ByteArray(0)
        } else {
            cipher = cipherName ?: "aes256-ctr"
            kdf = kdfName ?: "bcrypt"
            val salt = ByteArray(16).also { random.nextBytes(it) }
            kdfOptions = Buffer.PlainBuffer().putBytes(salt).putUInt32(kdfRoundsField ?: rounds.toLong()).compactData
            if (passphrase != null && kdfRoundsField == null) {
                val keyIv = ByteArray(48)
                BCrypt().pbkdf(passphrase.toByteArray(Charsets.UTF_8), salt, rounds, keyIv)
                val aes = Cipher.getInstance("AES/CTR/NoPadding")
                aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyIv.copyOfRange(0, 32), "AES"), IvParameterSpec(keyIv.copyOfRange(32, 48)))
                sectionBytes = aes.doFinal(sectionBytes)
            }
        }
        val container = Buffer.PlainBuffer()
            .putRawBytes("openssh-key-v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0))
            .putString(cipher)
            .putString(kdf)
            .putBytes(kdfOptions)
            .putUInt32(1)
            .putBytes(envelope)
            .putBytes(sectionBytes)
            .compactData
        return armor("OPENSSH PRIVATE KEY", container, lineLength = 70)
    }

    /** OpenSSH v1 file of [pair] with its own public key as envelope. */
    fun openssh(pair: KeyPair, curve: EcCurve? = null, passphrase: String? = null): String =
        openssh(opensshPrivateFields(pair, curve), blobOf(pair.public), passphrase)

    // ---- PEM and DER ----

    fun armor(label: String, der: ByteArray, lineLength: Int = 64): String = buildString {
        append("-----BEGIN $label-----\n")
        Base64.getEncoder().encodeToString(der).chunked(lineLength).forEach { append(it).append('\n') }
        append("-----END $label-----\n")
    }

    fun pkcs8(pair: KeyPair): String = armor("PRIVATE KEY", pair.private.encoded)

    /** PKCS#1 `RSAPrivateKey` DER. */
    fun pkcs1Der(key: RSAPrivateCrtKey): ByteArray = seq(
        int(BigInteger.ZERO), int(key.modulus), int(key.publicExponent), int(key.privateExponent),
        int(key.primeP), int(key.primeQ), int(key.primeExponentP), int(key.primeExponentQ),
        int(key.crtCoefficient),
    )

    /** SEC1 `ECPrivateKey` DER, with the public key [embeddedPoint] when not null. */
    fun sec1Der(key: ECPrivateKey, curve: EcCurve, embeddedPoint: ByteArray?): ByteArray {
        val size = (key.params.curve.field.fieldSize + 7) / 8
        val parts = mutableListOf(
            int(BigInteger.ONE),
            tlv(0x04, fixed(key.s, size)),
            tlv(0xA0, curveOid(curve)),
        )
        if (embeddedPoint != null) parts += tlv(0xA1, tlv(0x03, byteArrayOf(0) + embeddedPoint))
        return seq(*parts.toTypedArray())
    }

    private fun curveOid(curve: EcCurve): ByteArray = when (curve) {
        EcCurve.P256 -> oid(0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07)
        EcCurve.P384 -> oid(0x2B, 0x81, 0x04, 0x00, 0x22)
        EcCurve.P521 -> oid(0x2B, 0x81, 0x04, 0x00, 0x23)
    }

    /** OID of AES-256-CBC, the scheme OpenSSL writes. */
    val AES256_CBC_OID: ByteArray = oid(0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x01, 0x2A)

    /** OID of AES-256-GCM, a PBES2 scheme the deriver does not open. */
    val AES256_GCM_OID: ByteArray = oid(0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x01, 0x2E)

    /** OID of HMAC-SHA256, the PBKDF2 PRF OpenSSL writes. */
    val HMAC_SHA256_OID: ByteArray = oid(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x02, 0x09)

    /** OID of HMAC-MD5, a PBKDF2 PRF the deriver does not accept. */
    val HMAC_MD5_OID: ByteArray = oid(0x2B, 0x06, 0x01, 0x05, 0x05, 0x08, 0x01, 0x01)

    /** OID of HMAC-SHA3-256, a PBKDF2 PRF BouncyCastle runs but the deriver does not accept. */
    val HMAC_SHA3_256_OID: ByteArray = oid(0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x0E)

    /** OID of pbeWithMD5AndDES-CBC, a PBES1 scheme (PKCS#5 v1.5). */
    val PBE_MD5_DES_OID: ByteArray = oid(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x05, 0x03)

    /** OID of pbeWithSHAAnd3-KeyTripleDES-CBC, a PKCS#12 scheme. */
    val PBE_SHA_3DES_PKCS12_OID: ByteArray = oid(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x0C, 0x01, 0x03)

    /**
     * An `ENCRYPTED PRIVATE KEY` whose algorithm is [scheme] with the parameters `SEQUENCE { salt, iterations }`
     * of PBES1 (`PBEParameter`) and PKCS#12 (`PKCS12PBEParams`). The data is random: it is never decrypted.
     */
    fun encryptedPkcs8Legacy(scheme: ByteArray, iterations: BigInteger): String {
        val salt = ByteArray(8).also { random.nextBytes(it) }
        val data = ByteArray(64).also { random.nextBytes(it) }
        val algorithm = seq(scheme, seq(tlv(0x04, salt), int(iterations)))
        return armor("ENCRYPTED PRIVATE KEY", seq(algorithm, tlv(0x04, data)))
    }

    /**
     * PKCS#8 `EncryptedPrivateKeyInfo` with PBES2 (PBKDF2 HMAC-SHA256, AES-256-CBC), as OpenSSL writes it.
     * With [declaredIterations] the structure announces that count while the data is encrypted with
     * [iterations] (to test the bound without paying for it). [scheme] and [prf] only change what the
     * structure announces (the data stays AES-256-CBC with HMAC-SHA256).
     */
    fun encryptedPkcs8(
        der: ByteArray,
        passphrase: CharArray,
        iterations: Int = 2048,
        declaredIterations: Int = iterations,
        scheme: ByteArray = AES256_CBC_OID,
        prf: ByteArray = HMAC_SHA256_OID,
    ): String {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(16).also { random.nextBytes(it) }
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(passphrase, salt, iterations, 256)).encoded
        val aes = Cipher.getInstance("AES/CBC/PKCS5Padding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = aes.doFinal(der)
        val pbkdf2 = seq(
            oid(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x05, 0x0C),
            seq(tlv(0x04, salt), int(BigInteger.valueOf(declaredIterations.toLong())), seq(prf, byteArrayOf(0x05, 0x00))),
        )
        val encryptionScheme = seq(scheme, tlv(0x04, iv))
        val pbes2 = seq(oid(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x05, 0x0D), seq(pbkdf2, encryptionScheme))
        return armor("ENCRYPTED PRIVATE KEY", seq(pbes2, tlv(0x04, encrypted)))
    }

    /**
     * OpenSSL traditional encrypted PEM (`Proc-Type: 4,ENCRYPTED`, AES-128-CBC, MD5 key derivation).
     * [dekName] only changes the cipher name the header announces.
     */
    fun traditionalEncrypted(label: String, der: ByteArray, passphrase: String, dekName: String = "AES-128-CBC"): String {
        val iv = ByteArray(16).also { random.nextBytes(it) }
        val key = MessageDigest.getInstance("MD5").digest(passphrase.toByteArray(Charsets.US_ASCII) + iv.copyOfRange(0, 8))
        val aes = Cipher.getInstance("AES/CBC/PKCS5Padding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = aes.doFinal(der)
        val hexIv = iv.joinToString("") { "%02X".format(it) }
        return buildString {
            append("-----BEGIN $label-----\n")
            append("Proc-Type: 4,ENCRYPTED\n")
            append("DEK-Info: $dekName,$hexIv\n\n")
            Base64.getEncoder().encodeToString(encrypted).chunked(64).forEach { append(it).append('\n') }
            append("-----END $label-----\n")
        }
    }

    fun tlv(tag: Int, content: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + length(content.size) + content

    fun seq(vararg parts: ByteArray): ByteArray = tlv(0x30, parts.fold(ByteArray(0)) { acc, p -> acc + p })

    fun int(value: BigInteger): ByteArray = tlv(0x02, value.toByteArray())

    private fun oid(vararg bytes: Int): ByteArray = tlv(0x06, ByteArray(bytes.size) { bytes[it].toByte() })

    private fun length(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n ushr 8).toByte(), n.toByte())
    }

    // ---- SSH wire bytes for forged files ----

    fun u32(v: Long): ByteArray = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    fun str(bytes: ByteArray): ByteArray = u32(bytes.size.toLong()) + bytes

    fun str(text: String): ByteArray = str(text.toByteArray(Charsets.US_ASCII))
}
