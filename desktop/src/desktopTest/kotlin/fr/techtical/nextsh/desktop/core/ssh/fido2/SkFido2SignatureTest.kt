// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh.fido2

import fr.techtical.nextsh.desktop.core.auth.fido2.Fido2Signer
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshPublicKey
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshSignature
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.Buffer
import java.security.MessageDigest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Tests unitaires pour [SkFido2Signature] avec un [Fido2Signer] fake in-memory.
 *
 * Vérifie le format de la signature wire SK :
 *   string raw_signature (64 bytes ed25519)
 *   byte   flags
 *   uint32 counter (big-endian)
 *
 * Les tests n'exigent pas de YubiKey physique : le signer fake produit des
 * assertions déterministes.
 */
class SkFido2SignatureTest {

    // ── Fake Fido2Signer ──────────────────────────────────────────────────────

    /**
     * Signer de test : renvoie une assertion déterministe.
     * authData : 37 bytes, dont flags=0x01 (UP) à [32] et counter=42 à [33..36].
     * signature : 64 bytes, chacun = index % 256.
     */
    private class FakeFido2Signer(
        private val flags: Byte = 0x01, // FLAG_USER_PRESENT
        private val counter: UInt = 42u,
    ) : Fido2Signer {

        /** Stocke le dernier clientDataHash reçu pour vérification. */
        var lastClientDataHash: ByteArray? = null
            private set

        val fakeAuthData: ByteArray = ByteArray(37).also { buf ->
            // rpIdHash : bytes [0..31] = 0x00 (test)
            buf[32] = flags
            // counter big-endian à [33..36]
            val c = counter.toLong()
            buf[33] = ((c shr 24) and 0xFF).toByte()
            buf[34] = ((c shr 16) and 0xFF).toByte()
            buf[35] = ((c shr 8) and 0xFF).toByte()
            buf[36] = (c and 0xFF).toByte()
        }

        val fakeSignature: ByteArray = ByteArray(64) { i -> (i % 256).toByte() }

        override suspend fun signChallenge(
            rpId: String,
            clientDataHash: ByteArray,
            credentialId: ByteArray,
        ): Fido2Signer.Assertion {
            lastClientDataHash = clientDataHash.copyOf()
            return Fido2Signer.Assertion(
                authData = fakeAuthData.copyOf(),
                signature = fakeSignature.copyOf(),
            )
        }
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private val keyData = ByteArray(32) { it.toByte() }
    private val credentialId = ByteArray(16) { (it + 100).toByte() }
    private val skPublicKey = SkSshPublicKey(SkKeyType.SK_ED25519, keyData, "ssh:")

    private lateinit var fakeSigner: FakeFido2Signer
    private lateinit var fakePrivateKey: SkFido2PrivateKey
    private lateinit var skFido2Sig: SkFido2Signature

    @BeforeTest
    fun setup() {
        fakeSigner = FakeFido2Signer()
        fakePrivateKey = SkFido2PrivateKey(
            credentialId = credentialId,
            publicKey = skPublicKey,
            fido2Signer = fakeSigner,
        )
        skFido2Sig = SkFido2Signature(keyType = SkKeyType.SK_ED25519)
        skFido2Sig.initSign(fakePrivateKey)
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * Cas nominal : update + sign produit l'inner blob correct.
     *
     * Le format attendu est :
     *   string raw_signature (4 bytes length + 64 bytes)
     *   byte   flags (0x01)
     *   uint32 counter (0x0000002A = 42)
     */
    @Test
    fun `sign returns correct inner blob format`() {
        val challenge = "SSH-challenge-data".toByteArray()
        skFido2Sig.update(challenge)

        val inner = skFido2Sig.sign()

        // Decode the inner blob
        val buf = Buffer.PlainBuffer(inner)
        val rawSig = buf.readStringAsBytes()
        val flags = buf.readByte()
        val counter = buf.readUInt32()

        assertContentEquals(fakeSigner.fakeSignature, rawSig, "raw_signature mismatch")
        assertEquals(0x01.toByte(), flags, "flags should be FLAG_USER_PRESENT")
        assertEquals(42L, counter.toLong(), "counter should be 42")
    }

    /**
     * Vérifie que le clientDataHash transmis au signer = SHA-256 du challenge accumulé.
     */
    @Test
    fun `sign passes SHA256 of accumulated buffer as clientDataHash`() {
        val challenge = "SSH-test-challenge".toByteArray()
        skFido2Sig.update(challenge)
        skFido2Sig.sign()

        val expected = MessageDigest.getInstance("SHA-256").digest(challenge)
        val actual = fakeSigner.lastClientDataHash

        assertNotNull(actual, "lastClientDataHash should not be null after sign()")
        assertContentEquals(expected, actual, "clientDataHash should be SHA-256 of challenge")
    }

    /**
     * Vérifie le comportement avec multi-update (plusieurs appels à update).
     * Le hash doit couvrir la concaténation de tous les chunks.
     */
    @Test
    fun `sign with multi-update accumulates all bytes`() {
        val part1 = "part-one".toByteArray()
        val part2 = "part-two".toByteArray()
        skFido2Sig.update(part1)
        skFido2Sig.update(part2)
        skFido2Sig.sign()

        val combined = part1 + part2
        val expected = MessageDigest.getInstance("SHA-256").digest(combined)
        val actual = fakeSigner.lastClientDataHash

        assertNotNull(actual)
        assertContentEquals(expected, actual, "multi-update hash should cover concatenated bytes")
    }

    /**
     * Vérifie que le SignatureName est correct pour le type SK_ED25519.
     */
    @Test
    fun `getSignatureName returns correct algorithm name`() {
        assertEquals(
            "sk-ssh-ed25519@openssh.com",
            skFido2Sig.getSignatureName(),
            "SignatureName mismatch",
        )
    }

    /**
     * Vérifie que encode() encapsule correctement dans le format outer SSH :
     *   string key_type ("sk-ssh-ed25519@openssh.com")
     *   string inner_blob
     */
    @Test
    fun `encode wraps inner blob with correct key_type header`() {
        val challenge = "encode-test".toByteArray()
        skFido2Sig.update(challenge)
        val inner = skFido2Sig.sign()

        val encoded = skFido2Sig.encode(inner)

        val buf = Buffer.PlainBuffer(encoded)
        val keyTypeParsed = buf.readString()
        val innerParsed = buf.readStringAsBytes()

        assertEquals("sk-ssh-ed25519@openssh.com", keyTypeParsed, "key_type in encoded blob")
        assertContentEquals(inner, innerParsed, "inner blob mismatch after encode")
    }

    /**
     * Vérifie que initVerify lève UnsupportedOperationException (côté client uniquement).
     */
    @Test
    fun `initVerify throws UnsupportedOperationException`() {
        assertFailsWith<UnsupportedOperationException> {
            skFido2Sig.initVerify(skPublicKey.toBlob().let {
                // Fake PublicKey factice : l'exception doit être levée avant toute utilisation
                object : java.security.PublicKey {
                    override fun getAlgorithm(): String = "FIDO2"
                    override fun getFormat(): String = "NONE"
                    override fun getEncoded(): ByteArray? = null
                }
            })
        }
    }
}
