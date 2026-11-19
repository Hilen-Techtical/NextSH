// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.signature.SignatureECDSA
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.random.Random

/**
 * The DER signature a JCA / Keystore ECDSA produces becomes the SSH blob `mpint r || mpint s`.
 * The round trips are checked against a software EC P-256 key and two verifiers that share nothing
 * with the code under test (SSHJ's own `SignatureECDSA`, and the JCA fed with a DER rebuilt by
 * BouncyCastle), the malformed inputs against the exact exception, and a fuzz run against any
 * other.
 */
class EcdsaSignatureTest {

    private fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun derSignature(pair: KeyPair, message: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(message)
            sign()
        }

    /** The two integers of an SSH blob, read the way a server reads them. */
    private fun readBlob(blob: ByteArray): Pair<BigInteger, BigInteger> {
        val buffer = Buffer.PlainBuffer(blob)
        val r = buffer.readMPInt()
        val s = buffer.readMPInt()
        assertEquals(0, buffer.available(), "nothing may follow mpint s")
        return r to s
    }

    /** A DER signature rebuilt from r and s by BouncyCastle, for the JCA to verify. */
    private fun derOf(r: BigInteger, s: BigInteger): ByteArray = DERSequence(arrayOf(ASN1Integer(r), ASN1Integer(s))).encoded

    private fun hex(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

    // ── Round trips with a real key ───────────────────────────────────────────

    @Test
    fun `a converted signature verifies with the JCA once rebuilt as DER`() {
        repeat(50) {
            val pair = keyPair()
            val message = Random.nextBytes(64)
            val blob = ecdsaDerToSshSignature(derSignature(pair, message))

            val (r, s) = readBlob(blob)
            val verifier = Signature.getInstance("SHA256withECDSA").apply {
                initVerify(pair.public)
                update(message)
            }
            assertTrue(verifier.verify(derOf(r, s)))
        }
    }

    @Test
    fun `a converted signature verifies with SSHJ as the server would check it`() {
        repeat(50) {
            val pair = keyPair()
            val message = Random.nextBytes(100)
            val blob = ecdsaDerToSshSignature(derSignature(pair, message))
            // What the server reads: string "ecdsa-sha2-nistp256", string blob.
            val onTheWire = Buffer.PlainBuffer().putString("ecdsa-sha2-nistp256").putString(blob).compactData

            val verifier = SignatureECDSA.Factory256().create().apply {
                initVerify(pair.public)
                update(message)
            }
            assertTrue(verifier.verify(onTheWire))
        }
    }

    @Test
    fun `a converted signature does not verify for another message`() {
        val pair = keyPair()
        val blob = ecdsaDerToSshSignature(derSignature(pair, "one".toByteArray()))
        val onTheWire = Buffer.PlainBuffer().putString("ecdsa-sha2-nistp256").putString(blob).compactData

        val verifier = SignatureECDSA.Factory256().create().apply {
            initVerify(pair.public)
            update("another".toByteArray())
        }
        assertFalse(verifier.verify(onTheWire))
    }

    @Test
    fun `r and s come back as the numbers the signer made, whatever their length`() {
        // Half of the values have the high bit set (33 bytes once written as an mpint), the others
        // not (32): both shapes must come out right, and positive.
        val lengths = mutableSetOf<Int>()
        repeat(200) {
            val pair = keyPair()
            val der = derSignature(pair, Random.nextBytes(16))
            val (r, s) = readBlob(ecdsaDerToSshSignature(der))
            lengths += r.toByteArray().size
            lengths += s.toByteArray().size
            assertTrue(r.signum() > 0 && s.signum() > 0)
        }
        // 32 (high bit clear), 33 (high bit set, leading zero) at least.
        assertTrue(lengths.containsAll(listOf(32, 33)), "$lengths")
    }

    // ── The encoding, byte for byte ───────────────────────────────────────────

    @Test
    fun `a value with the high bit set gets the leading zero of an mpint, one without does not`() {
        val r = ByteArray(32) { 0x80.toByte() }
        val s = ByteArray(32) { 0x7F }
        val der = hex(0x30, 0x45, 0x02, 0x21, 0x00) + r + hex(0x02, 0x20) + s

        val blob = ecdsaDerToSshSignature(der)

        val expected = hex(0, 0, 0, 33, 0x00) + r + hex(0, 0, 0, 32) + s
        assertArrayEquals(expected, blob)
    }

    @Test
    fun `short values are written with their own length`() {
        val der = hex(0x30, 0x06, 0x02, 0x01, 0x05, 0x02, 0x01, 0x06)

        assertArrayEquals(hex(0, 0, 0, 1, 0x05, 0, 0, 0, 1, 0x06), ecdsaDerToSshSignature(der))
    }

    @Test
    fun `a redundant leading zero is dropped and the mpint stays canonical`() {
        // r = 5 written as 00 05 (harmless padding), s = 0x7F as 00 7F.
        val der = hex(0x30, 0x08, 0x02, 0x02, 0x00, 0x05, 0x02, 0x02, 0x00, 0x7F)

        assertArrayEquals(hex(0, 0, 0, 1, 0x05, 0, 0, 0, 1, 0x7F), ecdsaDerToSshSignature(der))
    }

    @Test
    fun `a sequence whose length needs the long form is read, as for P-521`() {
        val r = ByteArray(66) { (it + 1).toByte() }.also { it[0] = 0x01 }
        val s = ByteArray(66) { (it + 2).toByte() }.also { it[0] = 0x02 }
        val content = hex(0x02, 66) + r + hex(0x02, 66) + s // 136 bytes
        val der = hex(0x30, 0x81, content.size) + content

        val blob = ecdsaDerToSshSignature(der)

        assertArrayEquals(hex(0, 0, 0, 66) + r + hex(0, 0, 0, 66) + s, blob)
    }

    // ── Malformed input ───────────────────────────────────────────────────────

    private fun assertRejected(der: ByteArray, what: String) {
        assertThrows(IllegalArgumentException::class.java, { ecdsaDerToSshSignature(der) }, what)
    }

    private val valid = hex(0x30, 0x06, 0x02, 0x01, 0x05, 0x02, 0x01, 0x06)

    @Test
    fun `an empty input is rejected`() {
        assertRejected(ByteArray(0), "empty")
    }

    @Test
    fun `a wrong tag is rejected`() {
        assertRejected(valid.copyOf().also { it[0] = 0x31 }, "set instead of sequence")
        assertRejected(valid.copyOf().also { it[2] = 0x03 }, "bit string instead of the first integer")
        assertRejected(valid.copyOf().also { it[5] = 0x04 }, "octet string instead of the second integer")
    }

    @Test
    fun `every truncation of a valid signature is rejected`() {
        for (size in 0 until valid.size) {
            assertRejected(valid.copyOf(size), "cut at $size")
        }
        val real = derSignature(keyPair(), "x".toByteArray())
        for (size in 0 until real.size) {
            assertRejected(real.copyOf(size), "real signature cut at $size")
        }
    }

    @Test
    fun `a length that claims more than there is is rejected, at every level`() {
        assertRejected(valid.copyOf().also { it[1] = 0x7F }, "sequence longer than the data")
        assertRejected(valid.copyOf().also { it[1] = 0x05 }, "sequence shorter than the data")
        assertRejected(valid.copyOf().also { it[3] = 0x7F }, "first integer longer than the sequence")
        assertRejected(valid.copyOf().also { it[6] = 0x7F }, "second integer longer than the sequence")
        assertRejected(hex(0x30, 0x81, 0xFF, 0x02, 0x01, 0x05), "long-form length far past the data")
        assertRejected(hex(0x30, 0x07, 0x02, 0x81, 0xFF, 0x05, 0x02, 0x01, 0x06), "long-form integer length past the sequence")
    }

    @Test
    fun `a length that overflows is rejected, not wrapped around`() {
        assertRejected(hex(0x30, 0x84, 0xFF, 0xFF, 0xFF, 0xFF, 0x02, 0x01, 0x05), "4-byte length")
        assertRejected(hex(0x30, 0x0A, 0x02, 0x84, 0x7F, 0xFF, 0xFF, 0xFF, 0x05, 0x02, 0x01, 0x06), "huge integer length")
        assertRejected(hex(0x30, 0x80), "indefinite length")
    }

    @Test
    fun `a length written in a longer form than needed is rejected`() {
        assertRejected(hex(0x30, 0x81, 0x06, 0x02, 0x01, 0x05, 0x02, 0x01, 0x06), "0x81 for a length below 128")
    }

    @Test
    fun `trailing data is rejected`() {
        assertRejected(valid + hex(0x00), "a byte after the sequence")
        assertRejected(hex(0x30, 0x09, 0x02, 0x01, 0x05, 0x02, 0x01, 0x06, 0x00, 0x00, 0x00), "bytes inside the sequence after s")
    }

    @Test
    fun `a missing integer is rejected`() {
        assertRejected(hex(0x30, 0x03, 0x02, 0x01, 0x05), "no s")
        assertRejected(hex(0x30, 0x00), "empty sequence")
    }

    @Test
    fun `empty, zero and negative integers are rejected`() {
        assertRejected(hex(0x30, 0x05, 0x02, 0x00, 0x02, 0x01, 0x06), "empty r")
        assertRejected(hex(0x30, 0x06, 0x02, 0x01, 0x00, 0x02, 0x01, 0x06), "r = 0")
        assertRejected(hex(0x30, 0x06, 0x02, 0x01, 0x05, 0x02, 0x01, 0x00), "s = 0")
        assertRejected(hex(0x30, 0x07, 0x02, 0x02, 0x00, 0x00, 0x02, 0x01, 0x06), "r = 0 written on two bytes")
        assertRejected(hex(0x30, 0x06, 0x02, 0x01, 0x80, 0x02, 0x01, 0x06), "negative r")
        assertRejected(hex(0x30, 0x06, 0x02, 0x01, 0x05, 0x02, 0x01, 0xFF), "negative s")
    }

    @Test
    fun `random garbage never fails with anything but an IllegalArgumentException`() {
        val random = Random(1234)
        repeat(5_000) {
            val garbage = random.nextBytes(random.nextInt(0, 80))
            // Half of them start like a signature, to get past the first checks.
            if (it % 2 == 0 && garbage.isNotEmpty()) garbage[0] = 0x30
            runCatching { ecdsaDerToSshSignature(garbage) }.onFailure { failure ->
                assertTrue(failure is IllegalArgumentException, "${failure.javaClass}")
            }
        }
    }

    @Test
    fun `a valid signature with any one byte changed is rejected or converted, never a crash`() {
        val real = derSignature(keyPair(), "x".toByteArray())
        for (index in real.indices) {
            for (value in listOf(0x00, 0x01, 0x30, 0x7F, 0x80, 0x81, 0xFF)) {
                val mutated = real.copyOf().also { it[index] = value.toByte() }
                runCatching { ecdsaDerToSshSignature(mutated) }.onFailure { failure ->
                    assertTrue(failure is IllegalArgumentException, "byte $index -> $value: ${failure.javaClass}")
                }
            }
        }
    }

    @Test
    fun `the exception never carries the bytes it was given`() {
        val secretLooking = hex(0x30, 0x06, 0x02, 0x01, 0x80, 0x02, 0x01, 0xFF)

        val failure = assertThrows(IllegalArgumentException::class.java) { ecdsaDerToSshSignature(secretLooking) }

        assertFalse(failure.message.orEmpty().contains("80"))
        assertFalse(failure.message.orEmpty().contains("255"))
    }
}
