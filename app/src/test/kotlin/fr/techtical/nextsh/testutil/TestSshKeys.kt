// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.testutil

import com.hierynomus.sshj.userauth.keyprovider.bcrypt.BCrypt
import fr.techtical.nextsh.shared.core.ssh.OpenSshPrivateKeyWriter
import net.schmizz.sshj.common.Buffer
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.EdECPrivateKey
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * An Ed25519 key generated at run time by the test: no key material is ever written in a source file.
 * [pem] is the OpenSSH v1 private key file, [line] its public key line (the key's own, built here
 * from the public half, independently of the code under test).
 */
internal class TestEd25519Key(
    val pem: String,
    val blobBase64: String,
    comment: String,
) {
    /** `ssh-ed25519 <blob> <comment>`. */
    val line: String = "ssh-ed25519 $blobBase64 $comment"

    /** `ssh-ed25519 <blob>`, what two lines of the same key share whatever their comment. */
    val blobKey: String = "ssh-ed25519 $blobBase64"
}

internal object TestSshKeys {

    private val random = SecureRandom()

    /** A fresh Ed25519 key, in a clear OpenSSH v1 file written by the shared writer. */
    fun ed25519(comment: String = "test@nextsh"): TestEd25519Key {
        val (seed, a) = freshSeedAndPublic()
        return TestEd25519Key(OpenSshPrivateKeyWriter.ed25519(seed, comment), blobOf(a), comment)
    }

    /**
     * A fresh Ed25519 key in an OpenSSH v1 file encrypted like ssh-keygen does it (aes256-ctr, bcrypt
     * of [rounds] rounds), written here with SSHJ's bcrypt and JCA AES-CTR.
     */
    fun encryptedEd25519(passphrase: String, rounds: Int = 16, comment: String = "test@nextsh"): TestEd25519Key {
        val (seed, a) = freshSeedAndPublic()
        val publicBlob = Buffer.PlainBuffer().putString("ssh-ed25519").putBytes(a).compactData

        val section = Buffer.PlainBuffer()
            .putUInt32(0x12345678L).putUInt32(0x12345678L)
            .putString("ssh-ed25519").putBytes(a).putBytes(seed + a)
            .putString(comment)
        var pad = 1
        while (section.available() % 16 != 0) section.putByte((pad++).toByte())

        val salt = ByteArray(16).also { random.nextBytes(it) }
        val keyIv = ByteArray(48)
        BCrypt().pbkdf(passphrase.toByteArray(Charsets.UTF_8), salt, rounds, keyIv)
        val aes = Cipher.getInstance("AES/CTR/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyIv.copyOfRange(0, 32), "AES"), IvParameterSpec(keyIv.copyOfRange(32, 48)))
        val encrypted = aes.doFinal(section.compactData)

        val container = Buffer.PlainBuffer()
            .putRawBytes("openssh-key-v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0))
            .putString("aes256-ctr")
            .putString("bcrypt")
            .putBytes(Buffer.PlainBuffer().putBytes(salt).putUInt32(rounds.toLong()).compactData)
            .putUInt32(1)
            .putBytes(publicBlob)
            .putBytes(encrypted)
            .compactData
        val pem = buildString {
            append("-----BEGIN OPENSSH PRIVATE KEY-----\n")
            Base64.getEncoder().encodeToString(container).chunked(70).forEach { append(it).append('\n') }
            append("-----END OPENSSH PRIVATE KEY-----\n")
        }
        return TestEd25519Key(pem, blobOf(a), comment)
    }

    /**
     * A clear OpenSSH v1 file whose clear public key (the envelope) is the one of ANOTHER key than the private
     * section: the SSH library reads it without a word, but it could never sign in.
     */
    fun inconsistentEd25519(): String {
        val (seed, a) = freshSeedAndPublic()
        val (_, otherA) = freshSeedAndPublic()
        val envelope = Buffer.PlainBuffer().putString("ssh-ed25519").putBytes(otherA).compactData
        val section = Buffer.PlainBuffer()
            .putUInt32(0x12345678L).putUInt32(0x12345678L)
            .putString("ssh-ed25519").putBytes(a).putBytes(seed + a)
            .putString("test")
        var pad = 1
        while (section.available() % 8 != 0) section.putByte((pad++).toByte())
        return armor(
            Buffer.PlainBuffer()
                .putRawBytes("openssh-key-v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0))
                .putString("none").putString("none").putBytes(ByteArray(0))
                .putUInt32(1).putBytes(envelope).putBytes(section.compactData)
                .compactData,
        )
    }

    /**
     * A forged OpenSSH v1 file that announces [rounds] bcrypt rounds (aes256-ctr, random section): opening it with
     * the SSH library would run that many rounds, with or without a passphrase. Nothing here runs them.
     */
    fun forgedBcryptRounds(rounds: Long): String {
        val (_, a) = freshSeedAndPublic()
        val envelope = Buffer.PlainBuffer().putString("ssh-ed25519").putBytes(a).compactData
        val salt = ByteArray(16).also { random.nextBytes(it) }
        return armor(
            Buffer.PlainBuffer()
                .putRawBytes("openssh-key-v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0))
                .putString("aes256-ctr").putString("bcrypt")
                .putBytes(Buffer.PlainBuffer().putBytes(salt).putUInt32(rounds).compactData)
                .putUInt32(1).putBytes(envelope).putBytes(ByteArray(96).also { random.nextBytes(it) })
                .compactData,
        )
    }

    /**
     * An `ENCRYPTED PRIVATE KEY` whose scheme is pbeWithSHAAnd3-KeyTripleDES-CBC (PKCS#12), outside the schemes the
     * shared deriver opens. The data is random: it is never decrypted. With [iterations] under the cost bound the
     * deriver says "unsupported encryption"; above it, "too costly".
     */
    fun encryptedPkcs8Pkcs12Scheme(iterations: Long = 2048): String {
        val scheme = tlv(0x06, intArrayOf(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x0C, 0x01, 0x03).let { b -> ByteArray(b.size) { b[it].toByte() } })
        val salt = ByteArray(8).also { random.nextBytes(it) }
        val data = ByteArray(64).also { random.nextBytes(it) }
        val params = seq(tlv(0x04, salt), tlv(0x02, java.math.BigInteger.valueOf(iterations).toByteArray()))
        return armorDer("ENCRYPTED PRIVATE KEY", seq(seq(scheme, params), tlv(0x04, data)))
    }

    private fun derLength(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
    }

    private fun tlv(tag: Int, body: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + derLength(body.size) + body

    private fun seq(vararg parts: ByteArray): ByteArray = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })

    private fun armorDer(label: String, der: ByteArray): String = buildString {
        append("-----BEGIN $label-----\n")
        Base64.getEncoder().encodeToString(der).chunked(64).forEach { append(it).append('\n') }
        append("-----END $label-----\n")
    }

    private fun armor(container: ByteArray): String = buildString {
        append("-----BEGIN OPENSSH PRIVATE KEY-----\n")
        Base64.getEncoder().encodeToString(container).chunked(70).forEach { append(it).append('\n') }
        append("-----END OPENSSH PRIVATE KEY-----\n")
    }

    private fun freshSeedAndPublic(): Pair<ByteArray, ByteArray> {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val seed = (pair.private as EdECPrivateKey).bytes.get()
        // X.509 encoding of an Ed25519 public key: a 12 byte header, then the 32 byte key.
        val a = pair.public.encoded.copyOfRange(pair.public.encoded.size - 32, pair.public.encoded.size)
        return seed to a
    }

    private fun blobOf(a: ByteArray): String =
        Base64.getEncoder().encodeToString(Buffer.PlainBuffer().putString("ssh-ed25519").putBytes(a).compactData)
}
