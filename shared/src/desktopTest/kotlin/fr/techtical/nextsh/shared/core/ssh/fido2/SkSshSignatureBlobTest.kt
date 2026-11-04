// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.ssh.fido2

import net.schmizz.sshj.common.Buffer
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests de [SkSshSignature.encodeSignatureBlob] : le blob doit etre exactement celui que
 * decrit PROTOCOL.u2f d'OpenSSH :
 *   string key_type || string signature || byte flags || uint32 counter
 * avec, pour sk-ecdsa, `signature = mpint r || mpint s` (conversion depuis le DER de l'authenticator).
 *
 * Les attendus sont construits a la main (octets litteraux) sans passer par le code teste.
 * L'interoperabilite reelle avec `ssh-keygen` est couverte par [SkSshSignatureOpenSshInteropTest].
 */
class SkSshSignatureBlobTest {

    // ---- helpers independants du code de production ----

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        require(clean.length % 2 == 0)
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun cat(vararg parts: ByteArray): ByteArray = parts.fold(ByteArray(0)) { acc, b -> acc + b }

    private fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun str(b: ByteArray) = u32(b.size.toLong()) + b

    private val ecdsaName = "sk-ecdsa-sha2-nistp256@openssh.com"
    private val ed25519Name = "sk-ssh-ed25519@openssh.com"

    private fun blobOf(keyType: SkKeyType, raw: ByteArray, flags: Byte = 0x01, counter: UInt = 1u): ByteArray {
        val sig = SkSshSignature.create(keyType, raw, flags, counter)
        return sig.encodeSignatureBlob()
    }

    /** DER minimal d'un INTEGER positif a partir d'un BigInteger (toByteArray() = complement a deux minimal). */
    private fun derInt(v: BigInteger): ByteArray {
        val c = v.toByteArray()
        return byteArrayOf(0x02, c.size.toByte()) + c
    }

    private fun derSig(r: BigInteger, s: BigInteger): ByteArray {
        val body = derInt(r) + derInt(s)
        require(body.size < 0x80)
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    // ---- noms de types (garde-fou sur les constantes) ----

    @Test
    fun `key type names are the OpenSSH sk names`() {
        assertEquals(ecdsaName, SkKeyType.SK_ECDSA_256.sshName)
        assertEquals(ed25519Name, SkKeyType.SK_ED25519.sshName)
    }

    // ---- layout exact, Ed25519 ----

    @Test
    fun `ed25519 blob is string type, string 64 bytes, flags, counter`() {
        val raw = ByteArray(64) { it.toByte() }
        val blob = blobOf(SkKeyType.SK_ED25519, raw.copyOf(), flags = 0x01, counter = 42u)

        val expected = cat(
            hex("0000001a"), ed25519Name.toByteArray(Charsets.US_ASCII), // string "sk-ssh-ed25519@openssh.com"
            hex("00000040"), raw,                                         // string signature (64 bytes)
            hex("01"),                                                    // flags
            hex("0000002a"),                                              // counter = 42
        )
        assertEquals(4 + 26 + 4 + 64 + 1 + 4, blob.size)
        assertContentEquals(expected, blob)
    }

    @Test
    fun `ed25519 blob has no extra length prefix around the key type and signature`() {
        val raw = ByteArray(64) { (it + 1).toByte() }
        val blob = blobOf(SkKeyType.SK_ED25519, raw.copyOf(), flags = 0x05, counter = 0xFFFFFFFFu)

        val buf = Buffer.PlainBuffer(blob)
        assertEquals(ed25519Name, buf.readString())
        assertContentEquals(raw, buf.readStringAsBytes())
        assertEquals(0x05.toByte(), buf.readByte())
        assertEquals(0xFFFFFFFFL, buf.readUInt32())
        assertEquals(0, buf.available(), "nothing may follow the counter")
    }

    @Test
    fun `counter is written as unsigned big endian even with the high bit set`() {
        val raw = ByteArray(64)
        val blob = blobOf(SkKeyType.SK_ED25519, raw, counter = 0x80000001u)
        assertContentEquals(hex("80000001"), blob.copyOfRange(blob.size - 4, blob.size))
    }

    // ---- layout exact, ECDSA ----

    @Test
    fun `ecdsa blob with short r and s`() {
        // SEQUENCE { INTEGER 5, INTEGER 7 }
        val der = hex("30 06 02 01 05 02 01 07")
        val blob = blobOf(SkKeyType.SK_ECDSA_256, der, flags = 0x05, counter = 0x01020304u)

        val expected = cat(
            hex("00000022"), ecdsaName.toByteArray(Charsets.US_ASCII), // string "sk-ecdsa-sha2-nistp256@openssh.com"
            hex("0000000a"),                                            // string signature (10 bytes)
            hex("00000001 05"),                                         //   mpint r = 5
            hex("00000001 07"),                                         //   mpint s = 7
            hex("05"),                                                  // flags
            hex("01020304"),                                            // counter
        )
        assertEquals(34, ecdsaName.length)
        assertContentEquals(expected, blob)
    }

    @Test
    fun `ecdsa mpint keeps the 0x00 pad when the high bit is set`() {
        // r = 0x8001, s = 0xFF02 : DER pads each with 0x00, so does the mpint
        val der = hex("30 0a 02 03 00 80 01 02 03 00 ff 02")
        val blob = blobOf(SkKeyType.SK_ECDSA_256, der)

        val sigField = hex("00000003 008001 00000003 00ff02")
        val expected = cat(
            hex("00000022"), ecdsaName.toByteArray(Charsets.US_ASCII),
            u32(sigField.size.toLong()), sigField,
            hex("01"), hex("00000001"),
        )
        assertContentEquals(expected, blob)
    }

    @Test
    fun `ecdsa mpint has no leading zero when DER stripped a zero byte`() {
        // r: 31 significant bytes (the top byte of the 32-byte scalar was 0x00, DER drops it).
        // s: 32 bytes with the high bit set, so DER and mpint both carry one 0x00 pad.
        val r = ByteArray(31) { (0x10 + it).toByte() }               // first byte 0x10, high bit clear
        val s = byteArrayOf(0x00) + ByteArray(32) { (0x80 + it).toByte() } // pad + 32 bytes starting 0x80
        val der = cat(hex("30"), byteArrayOf((2 + r.size + 2 + s.size).toByte()), hex("02 1f"), r, hex("02 21"), s)
        val blob = blobOf(SkKeyType.SK_ECDSA_256, der)

        val sigField = cat(u32(31), r, u32(33), s)
        val expected = cat(
            hex("00000022"), ecdsaName.toByteArray(Charsets.US_ASCII),
            u32(sigField.size.toLong()), sigField,
            hex("01"), hex("00000001"),
        )
        assertContentEquals(expected, blob)
        // r is 31 bytes and must not be re-padded, s is 33 bytes with exactly one leading 0x00.
        val buf = Buffer.PlainBuffer(blob)
        buf.readString()
        val field = Buffer.PlainBuffer(buf.readStringAsBytes())
        assertEquals(31, field.readStringAsBytes().size)
        val sOut = field.readStringAsBytes()
        assertEquals(33, sOut.size)
        assertEquals(0.toByte(), sOut[0])
    }

    @Test
    fun `ecdsa one byte value with high bit needs a pad and 0x7f does not`() {
        // r = 0x80, s = 0x7f
        val der = hex("30 07 02 02 00 80 02 01 7f")
        val blob = blobOf(SkKeyType.SK_ECDSA_256, der)
        val buf = Buffer.PlainBuffer(blob)
        buf.readString()
        val field = Buffer.PlainBuffer(buf.readStringAsBytes())
        assertContentEquals(hex("00 80"), field.readStringAsBytes())
        assertContentEquals(hex("7f"), field.readStringAsBytes())
        assertEquals(0, field.available())
    }

    // ---- equivalence cryptographique avec un vrai ECDSA ----

    @Test
    fun `converted ecdsa signatures still verify as the same r and s`() {
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val kp = kpg.generateKeyPair()
        var sawPaddedR = false
        var sawPaddedS = false
        repeat(300) { i ->
            val message = "message $i".toByteArray()
            val der = Signature.getInstance("SHA256withECDSA").apply {
                initSign(kp.private)
                update(message)
            }.sign()

            val blob = blobOf(SkKeyType.SK_ECDSA_256, der.copyOf())
            val buf = Buffer.PlainBuffer(blob)
            assertEquals(ecdsaName, buf.readString())
            val field = Buffer.PlainBuffer(buf.readStringAsBytes())
            val rBytes = field.readStringAsBytes()
            val sBytes = field.readStringAsBytes()
            assertEquals(0, field.available(), "only r and s inside the signature string")

            // mpint canonique : positif, pas de zero superflu
            for (m in listOf(rBytes, sBytes)) {
                assertTrue(m.isNotEmpty() && m.size <= 33)
                if (m[0] == 0.toByte()) {
                    assertTrue(m.size > 1 && (m[1].toInt() and 0x80) != 0, "superfluous leading zero in mpint")
                } else {
                    assertTrue((m[0].toInt() and 0x80) == 0, "mpint must be positive")
                }
            }
            sawPaddedR = sawPaddedR || rBytes[0] == 0.toByte()
            sawPaddedS = sawPaddedS || sBytes[0] == 0.toByte()

            // On reconstruit un DER depuis r et s lus dans le mpint : il doit verifier avec la cle publique.
            val rebuilt = derSig(BigInteger(rBytes), BigInteger(sBytes))
            val ok = Signature.getInstance("SHA256withECDSA").apply {
                initVerify(kp.public)
                update(message)
            }.verify(rebuilt)
            assertTrue(ok, "signature $i does not verify after DER -> mpint conversion")
            assertEquals(5, buf.available(), "flags + counter follow the signature string")
        }
        assertTrue(sawPaddedR && sawPaddedS, "300 random signatures should include high-bit r and s")
    }

    // ---- DER malforme rejete ----

    private fun assertRejected(label: String, der: ByteArray) {
        val ex = assertFailsWith<IllegalArgumentException>("$label must be rejected") {
            blobOf(SkKeyType.SK_ECDSA_256, der)
        }
        assertTrue(
            ex.message.orEmpty().startsWith("Malformed ECDSA DER signature"),
            "$label: unexpected message '${ex.message}'",
        )
    }

    @Test
    fun `malformed ecdsa DER is rejected`() {
        val magnitude33 = ByteArray(33) { 0x01 }
        val magnitude33Padded = byteArrayOf(0x00, 0x80.toByte()) + ByteArray(32) { 0x01 }

        val cases = mapOf(
            "empty" to ByteArray(0),
            "junk 64 bytes" to ByteArray(64) { 1 },
            "wrong sequence tag" to hex("31 06 02 01 05 02 01 07"),
            "sequence length too long" to hex("30 07 02 01 05 02 01 07"),
            "sequence length too short" to hex("30 05 02 01 05 02 01 07"),
            "trailing byte after the sequence" to hex("30 06 02 01 05 02 01 07 00"),
            "data after s inside the sequence" to hex("30 09 02 01 05 02 01 07 05 00 00"),
            "missing s" to hex("30 03 02 01 05"),
            "only the tag" to hex("30"),
            "truncated long length" to hex("30 81"),
            "wrong integer tag" to hex("30 06 04 01 05 02 01 07"),
            "empty integer" to hex("30 05 02 00 02 01 07"),
            "integer overruns the input" to hex("30 06 02 05 05 02 01 07"),
            "negative r" to hex("30 06 02 01 80 02 01 07"),
            "negative s" to hex("30 06 02 01 05 02 01 ff"),
            "zero r" to hex("30 06 02 01 00 02 01 07"),
            "zero s" to hex("30 06 02 01 05 02 01 00"),
            "zero r made of several zero bytes" to hex("30 07 02 02 00 00 02 01 07"),
            "zero s made of several zero bytes" to hex("30 08 02 01 05 02 03 00 00 00"),
            "negative r behind a 0xff first byte" to hex("30 07 02 02 ff 05 02 01 07"),
            "r longer than 256 bits behind redundant zeros" to
                (byteArrayOf(0x30, (2 + 35 + 3).toByte(), 0x02, 35, 0x00, 0x00) + magnitude33 + hex("02 01 07")),
            "indefinite length" to hex("30 80 02 01 05 02 01 07 00 00"),
            "non-minimal long length form" to hex("30 81 06 02 01 05 02 01 07"),
            "r longer than 256 bits" to
                (byteArrayOf(0x30, (2 + 33 + 3).toByte(), 0x02, 33) + magnitude33 + hex("02 01 07")),
            "r longer than 256 bits with pad" to
                (byteArrayOf(0x30, (2 + 34 + 3).toByte(), 0x02, 34) + magnitude33Padded + hex("02 01 07")),
        )
        for ((label, der) in cases) assertRejected(label, der)
    }

    // ---- zeros redondants en tete d'un INTEGER : acceptes et normalises ----

    /** Extrait (r, s) de la string signature d'un blob ECDSA. */
    private fun rsOf(blob: ByteArray): Pair<ByteArray, ByteArray> {
        val buf = Buffer.PlainBuffer(blob)
        buf.readString()
        val field = Buffer.PlainBuffer(buf.readStringAsBytes())
        val r = field.readStringAsBytes()
        val s = field.readStringAsBytes()
        assertEquals(0, field.available())
        return r to s
    }

    @Test
    fun `redundant leading zeros in DER integers are accepted and normalized`() {
        // r = 5 written as 00 05, s = 7 : mpint r = 05
        val (r1, s1) = rsOf(blobOf(SkKeyType.SK_ECDSA_256, hex("30 07 02 02 00 05 02 01 07")))
        assertContentEquals(hex("05"), r1)
        assertContentEquals(hex("07"), s1)

        // s = 7 written as 00 00 07
        val (r2, s2) = rsOf(blobOf(SkKeyType.SK_ECDSA_256, hex("30 08 02 01 05 02 03 00 00 07")))
        assertContentEquals(hex("05"), r2)
        assertContentEquals(hex("07"), s2)

        // r = 0x80 written as 00 00 80 : exactly one 0x00 pad remains
        val (r3, s3) = rsOf(blobOf(SkKeyType.SK_ECDSA_256, hex("30 08 02 03 00 00 80 02 01 07")))
        assertContentEquals(hex("00 80"), r3)
        assertContentEquals(hex("07"), s3)

        // r = 32 bytes without the high bit, preceded by three redundant zeros
        val magnitude = ByteArray(32) { (0x7f - it).toByte() }
        val padded = hex("00 00 00") + magnitude
        val der = byteArrayOf(0x30, (2 + padded.size + 3).toByte(), 0x02, padded.size.toByte()) + padded + hex("02 01 07")
        val (r4, _) = rsOf(blobOf(SkKeyType.SK_ECDSA_256, der))
        assertContentEquals(magnitude, r4)
    }

    @Test
    fun `a normalized signature has the same bytes as the canonical one`() {
        val canonical = blobOf(SkKeyType.SK_ECDSA_256, hex("30 07 02 02 00 80 02 01 7f"))
        val sloppy = blobOf(SkKeyType.SK_ECDSA_256, hex("30 0b 02 04 00 00 00 80 02 03 00 00 7f"))
        assertContentEquals(canonical, sloppy)
    }

    @Test
    fun `largest valid ecdsa scalars are accepted`() {
        // r = 2^256 - 1 (32 bytes 0xFF, DER pad) and s = 1
        val r = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)
        val der = derSig(r, BigInteger.ONE)
        val blob = blobOf(SkKeyType.SK_ECDSA_256, der)
        val buf = Buffer.PlainBuffer(blob)
        buf.readString()
        val field = Buffer.PlainBuffer(buf.readStringAsBytes())
        val rOut = field.readStringAsBytes()
        assertEquals(33, rOut.size)
        assertEquals(r, BigInteger(rOut))
    }

    // ---- Ed25519 : longueur stricte ----

    @Test
    fun `ed25519 rejects anything but 64 bytes`() {
        for (size in listOf(0, 1, 32, 63, 65, 71, 72)) {
            assertFailsWith<IllegalArgumentException>("size $size must be rejected") {
                blobOf(SkKeyType.SK_ED25519, ByteArray(size) { 1 })
            }
        }
    }

    // ---- hygiene ----

    @Test
    fun `encodeSignatureBlob does not alter or wipe the raw signature`() {
        val raw = ByteArray(64) { (it + 7).toByte() }
        val sig = SkSshSignature.create(SkKeyType.SK_ED25519, raw, SkSshSignature.FLAG_USER_PRESENT, 3u)

        val first = sig.encodeSignatureBlob()
        val second = sig.encodeSignatureBlob()

        assertContentEquals(first, second)
        assertContentEquals(ByteArray(64) { (it + 7).toByte() }, raw)
        // Le blob est une copie : le wiper ne touche pas la signature brute.
        first.fill(0)
        assertContentEquals(ByteArray(64) { (it + 7).toByte() }, raw)

        sig.close()
        assertTrue(raw.all { it == 0.toByte() }, "close() still wipes the raw signature")
    }
}
