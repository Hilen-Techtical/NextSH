// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import io.mockk.every
import io.mockk.mockk
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Message
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.signature.SignatureECDSA
import net.schmizz.sshj.transport.Transport
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.AuthParams
import net.schmizz.sshj.userauth.UserAuthException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * [BiometricAuthMethod] at the packet level, the way `SkAuthMethodRequestTest` (`:shared`) does it
 * for the FIDO2 method: what the method writes on the transport is read back as a server reads it,
 * and the signature in it is verified with the public key by SSHJ's own `SignatureECDSA`.
 */
class BiometricAuthMethodTest {

    private val sessionId = ByteArray(32) { (0x40 + it).toByte() }
    private val written = mutableListOf<ByteArray>()

    private val transport = mockk<Transport>(relaxed = true).also {
        every { it.sessionID } returns sessionId
        every { it.write(any<SSHPacket>()) } answers {
            written += firstArg<SSHPacket>().compactData
            0L
        }
    }

    private val params = object : AuthParams {
        override fun getNextServiceName(): String = "ssh-connection"
        override fun getTransport(): Transport = this@BiometricAuthMethodTest.transport
        override fun getUsername(): String = "alice"
    }

    private fun p256(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private val pair = p256()

    /** A signer that signs like the Keystore would, with a software key, and keeps what it was given. */
    private class RecordingSigner(private val pair: KeyPair) {
        var received: ByteArray? = null
        var receivedCopy: ByteArray? = null
        var der: ByteArray? = null

        suspend fun sign(signingData: ByteArray): ByteArray {
            received = signingData
            receivedCopy = signingData.copyOf()
            return Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(signingData)
                sign()
            }.also { der = it }
        }
    }

    private fun method(signer: suspend (ByteArray) -> ByteArray?): BiometricAuthMethod =
        BiometricAuthMethod(pair.public, signer).also { it.init(params) }

    private fun isWiped(data: ByteArray) = data.all { it == 0.toByte() }

    private fun blobOfKey(): ByteArray = Buffer.PlainBuffer().putPublicKey(pair.public).compactData

    /** The fields of a USERAUTH_REQUEST publickey, as a server reads them. */
    private class Request(
        val username: String,
        val service: String,
        val method: String,
        val signed: Boolean,
        val algorithm: String,
        val keyBlob: ByteArray,
        val signatureField: ByteArray?,
        val remaining: Int,
    )

    private fun parse(packet: ByteArray): Request {
        val buffer = Buffer.PlainBuffer(packet)
        assertEquals(Message.USERAUTH_REQUEST.toByte(), buffer.readByte())
        val username = buffer.readString()
        val service = buffer.readString()
        val method = buffer.readString()
        val signed = buffer.readBoolean()
        val algorithm = buffer.readString()
        val keyBlob = buffer.readStringAsBytes()
        val signature = if (signed) buffer.readStringAsBytes() else null
        return Request(username, service, method, signed, algorithm, keyBlob, signature, buffer.available())
    }

    private fun expectedSigningData(): ByteArray = Buffer.PlainBuffer()
        .putString(sessionId)
        .putByte(Message.USERAUTH_REQUEST.toByte())
        .putString("alice")
        .putString("ssh-connection")
        .putString("publickey")
        .putBoolean(true)
        .putString("ecdsa-sha2-nistp256")
        .putString(blobOfKey())
        .compactData

    // ── The feeler ────────────────────────────────────────────────────────────

    @Test
    fun `the feeler is an unsigned publickey request for the ecdsa-sha2-nistp256 key`() {
        val method = method { null }

        method.request()

        val request = parse(written.single())
        assertEquals("alice", request.username)
        assertEquals("ssh-connection", request.service)
        assertEquals("publickey", request.method)
        assertFalse(request.signed)
        assertEquals("ecdsa-sha2-nistp256", request.algorithm)
        assertArrayEquals(blobOfKey(), request.keyBlob)
        assertEquals(0, request.remaining, "no signature field in a feeler")
    }

    @Test
    fun `the feeler never calls the signer, so no fingerprint is asked for a key the server may refuse`() {
        var calls = 0
        val method = method { calls++; null }

        method.request()

        assertEquals(0, calls)
    }

    @Test
    fun `the key blob is the one a server parses back into the same public key`() {
        val method = method { null }
        method.request()

        val parsed = Buffer.PlainBuffer(parse(written.single()).keyBlob).readPublicKey() as ECPublicKey

        // Same point on the same curve (the key classes differ by provider, so not equals()).
        assertEquals((pair.public as ECPublicKey).w, parsed.w)
        assertEquals((pair.public as ECPublicKey).params.order, parsed.params.order)
    }

    // ── The signed request ────────────────────────────────────────────────────

    @Test
    fun `PK_OK makes the signed request, which a server verifies with the public key`() {
        val signer = RecordingSigner(pair)
        val method = method(signer::sign)

        method.handle(Message.USERAUTH_60, SSHPacket())

        val request = parse(written.single())
        assertEquals("alice", request.username)
        assertEquals("ssh-connection", request.service)
        assertEquals("publickey", request.method)
        assertTrue(request.signed)
        assertEquals("ecdsa-sha2-nistp256", request.algorithm)
        assertArrayEquals(blobOfKey(), request.keyBlob)
        assertEquals(0, request.remaining, "nothing may follow the signature string")

        // The signature field is string "ecdsa-sha2-nistp256" + string (mpint r || mpint s).
        val field = Buffer.PlainBuffer(request.signatureField!!)
        assertEquals("ecdsa-sha2-nistp256", field.readString())
        val blob = Buffer.PlainBuffer(field.readStringAsBytes())
        assertTrue(blob.readMPInt().signum() > 0)
        assertTrue(blob.readMPInt().signum() > 0)
        assertEquals(0, blob.available())
        assertEquals(0, field.available())

        // And it verifies, over the data the signer was given, with SSHJ's verifier.
        val verifier = SignatureECDSA.Factory256().create().apply {
            initVerify(pair.public)
            update(signer.receivedCopy!!)
        }
        assertTrue(verifier.verify(request.signatureField))
    }

    @Test
    fun `the signer is given the RFC 4252 signing data`() {
        val signer = RecordingSigner(pair)
        val method = method(signer::sign)

        method.handle(Message.USERAUTH_60, SSHPacket())

        assertArrayEquals(expectedSigningData(), signer.receivedCopy)
    }

    @Test
    fun `the signature is not valid for another session`() {
        val signer = RecordingSigner(pair)
        val method = method(signer::sign)
        method.handle(Message.USERAUTH_60, SSHPacket())
        val request = parse(written.single())

        // A server that has another session id builds other data to check: the signature fails.
        val otherSession = Buffer.PlainBuffer()
            .putString(ByteArray(32) { 1 })
            .putByte(Message.USERAUTH_REQUEST.toByte())
            .putString("alice")
            .putString("ssh-connection")
            .putString("publickey")
            .putBoolean(true)
            .putString("ecdsa-sha2-nistp256")
            .putString(blobOfKey())
            .compactData
        val verifier = SignatureECDSA.Factory256().create().apply {
            initVerify(pair.public)
            update(otherSession)
        }
        assertFalse(verifier.verify(request.signatureField))
    }

    @Test
    fun `the signing data and the DER signature are wiped once the request is sent`() {
        val signer = RecordingSigner(pair)
        val method = method(signer::sign)

        method.handle(Message.USERAUTH_60, SSHPacket())

        assertEquals(1, written.size)
        assertNotNull(signer.received)
        assertTrue(isWiped(signer.received!!), "the signing data given to the signer must be zeroed")
        assertTrue(isWiped(signer.der!!), "the DER signature given back must be zeroed")
        assertFalse(isWiped(signer.receivedCopy!!), "the test's own copy was not empty to begin with")
    }

    @Test
    fun `the signing data and the DER signature are wiped when the request cannot be built`() {
        var data: ByteArray? = null
        var der: ByteArray? = null
        val method = method { signingData ->
            data = signingData
            // Not a DER signature at all.
            ByteArray(40) { 0x55 }.also { der = it }
        }

        assertThrows(UserAuthException::class.java) { method.handle(Message.USERAUTH_60, SSHPacket()) }

        assertTrue(isWiped(data!!))
        assertTrue(isWiped(der!!))
    }

    // ── What ends the authentication without a request ────────────────────────

    @Test
    fun `a signer that gives no signature fails the authentication and writes nothing`() {
        var data: ByteArray? = null
        val method = method { signingData -> data = signingData; null }

        assertThrows(UserAuthException::class.java) { method.handle(Message.USERAUTH_60, SSHPacket()) }

        assertTrue(written.isEmpty())
        assertTrue(isWiped(data!!))
    }

    @Test
    fun `a signer that throws fails the authentication as a UserAuthException with the cause, and writes nothing`() {
        val failure = IllegalStateException("keystore said no")
        val method = method { throw failure }

        val thrown = assertThrows(UserAuthException::class.java) { method.handle(Message.USERAUTH_60, SSHPacket()) }

        assertSame(failure, thrown.cause)
        assertTrue(written.isEmpty())
    }

    @Test
    fun `a UserAuthException from the signer is passed on as it is`() {
        val failure = UserAuthException("already the right type")
        val method = method { throw failure }

        val thrown = assertThrows(UserAuthException::class.java) { method.handle(Message.USERAUTH_60, SSHPacket()) }

        assertSame(failure, thrown)
    }

    @Test
    fun `a transport failure while writing is passed on as it is`() {
        val failure = TransportException("socket closed")
        every { transport.write(any<SSHPacket>()) } throws failure
        val method = method(RecordingSigner(pair)::sign)

        val thrown = assertThrows(TransportException::class.java) { method.handle(Message.USERAUTH_60, SSHPacket()) }

        assertSame(failure, thrown)
    }

    @Test
    fun `the method does not retry`() {
        assertFalse(method { null }.shouldRetry())
    }

    // ── The key it accepts ────────────────────────────────────────────────────

    @Test
    fun `a key that is not EC P-256 is refused at construction`() {
        val p384 = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        for (key in listOf(p384.public, rsa.public)) {
            assertThrows(IllegalArgumentException::class.java) { BiometricAuthMethod(key) { null } }
            assertFalse(isBiometricKeyAlgorithm(key))
        }
    }

    @Test
    fun `an EC P-256 key is accepted`() {
        assertTrue(isBiometricKeyAlgorithm(pair.public))
    }

    // ── One signature per method ──────────────────────────────────────────────

    @Test
    fun `a second PK_OK ends the authentication instead of asking for another fingerprint`() {
        var calls = 0
        val signer = RecordingSigner(pair)
        val method = method { signingData -> calls++; signer.sign(signingData) }
        method.handle(Message.USERAUTH_60, SSHPacket())
        assertEquals(1, written.size)

        assertThrows(UserAuthException::class.java) { method.handle(Message.USERAUTH_60, SSHPacket()) }

        assertEquals(1, calls, "the signer is asked once")
        assertEquals(1, written.size, "nothing more is written")
    }

    @Test
    fun `a first PK_OK whose signature failed is not given a second chance by a second PK_OK`() {
        var calls = 0
        val method = method { calls++; null }
        assertThrows(UserAuthException::class.java) { method.handle(Message.USERAUTH_60, SSHPacket()) }

        assertThrows(UserAuthException::class.java) { method.handle(Message.USERAUTH_60, SSHPacket()) }

        assertEquals(1, calls, "the user refused or gave up once; the server cannot ask again")
        assertTrue(written.isEmpty())
    }

    @Test
    fun `any packet but PK_OK never calls the signer`() {
        var calls = 0
        val method = method { calls++; null }

        for (message in listOf(
            Message.USERAUTH_FAILURE, Message.USERAUTH_SUCCESS, Message.USERAUTH_BANNER,
            Message.USERAUTH_REQUEST, Message.KEXINIT, Message.IGNORE,
        )) {
            assertThrows(UserAuthException::class.java, { method.handle(message, SSHPacket()) }, "$message")
        }

        assertEquals(0, calls)
        assertTrue(written.isEmpty())
    }

    // ── Failures of the signer ────────────────────────────────────────────────

    @Test
    fun `a failure of the signer is a signing exception, which is not the server's refusal`() {
        val cause = IllegalStateException("keystore said no")
        val method = method { throw cause }

        val thrown = assertThrows(UserAuthException::class.java) { method.handle(Message.USERAUTH_60, SSHPacket()) }

        assertTrue(thrown is BiometricSigningException, "${thrown.javaClass}")
        assertSame(cause, thrown.cause)
    }

    @Test
    fun `a signature that is not DER is a signing exception and writes nothing`() {
        val method = method { ByteArray(70) { 0x11 } }

        val thrown = assertThrows(UserAuthException::class.java) { method.handle(Message.USERAUTH_60, SSHPacket()) }

        assertTrue(thrown is BiometricSigningException, "${thrown.javaClass}")
        assertTrue(thrown.cause is IllegalArgumentException)
        assertTrue(written.isEmpty())
    }

    @Test
    fun `no signature at all is no signing exception, there is nothing wrong to report`() {
        val method = method { null }

        val thrown = assertThrows(UserAuthException::class.java) { method.handle(Message.USERAUTH_60, SSHPacket()) }

        assertFalse(thrown is BiometricSigningException, "the prompt's end says why, not the signer")
    }

    @Test
    fun `an interrupted signer gives the interrupt flag of the SSH thread back`() {
        Thread.interrupted() // a clean flag to begin with
        val method = method { throw InterruptedException("runBlocking woken by a disconnect") }

        try {
            val thrown = assertThrows(UserAuthException::class.java) { method.handle(Message.USERAUTH_60, SSHPacket()) }

            assertTrue(thrown is BiometricSigningException)
            assertTrue(thrown.cause is InterruptedException)
            assertTrue(Thread.currentThread().isInterrupted, "the flag is restored for SSHJ")
        } finally {
            Thread.interrupted() // never leak the flag into the next test
        }
    }

    @Test
    fun `an interrupt wrapped in another exception restores the flag too`() {
        Thread.interrupted()
        val method = method { throw IllegalStateException("wrapped", InterruptedException("deep")) }

        try {
            assertThrows(UserAuthException::class.java) { method.handle(Message.USERAUTH_60, SSHPacket()) }

            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `any other failure leaves the interrupt flag alone`() {
        Thread.interrupted()
        val method = method { throw IllegalStateException("keystore said no") }

        assertThrows(UserAuthException::class.java) { method.handle(Message.USERAUTH_60, SSHPacket()) }

        assertFalse(Thread.currentThread().isInterrupted)
    }

    // ── A key a server can read: golden vector and another provider ───────────

    /**
     * A public key as `ssh-keygen -t ecdsa -b 256` of OpenSSH writes it, generated once and kept
     * here: the blob the method announces must be the base64 of this line, byte for byte. The
     * private half was thrown away, this is public data.
     */
    private val goldenLine = "ecdsa-sha2-nistp256 " +
        "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBFRywPjiBQ+nN5vAHfaCV86+TdtXyEVe6qFqg0xgf1GmZH0ZBZtyp" +
        "8YBYoyE++gGzQOZO7awphvgT4h4WIXSVOg= golden-vector"

    /** The public key of [goldenLine] as an object of the BouncyCastle provider, not of the JDK's. */
    private fun goldenKey(): java.security.PublicKey {
        val blob = java.util.Base64.getDecoder().decode(goldenLine.split(" ")[1])
        val buffer = Buffer.PlainBuffer(blob)
        assertEquals("ecdsa-sha2-nistp256", buffer.readString())
        assertEquals("nistp256", buffer.readString())
        val point = buffer.readStringAsBytes()
        val spec = org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("secp256r1")
        return java.security.KeyFactory.getInstance("EC", org.bouncycastle.jce.provider.BouncyCastleProvider())
            .generatePublic(org.bouncycastle.jce.spec.ECPublicKeySpec(spec.curve.decodePoint(point), spec))
    }

    @Test
    fun `the public key blob is the one OpenSSH writes for the same key`() {
        val key = goldenKey()
        val method = BiometricAuthMethod(key) { null }.also { it.init(params) }

        method.request()

        val request = Buffer.PlainBuffer(written.single())
        request.readByte()
        request.readString() // user name
        request.readString() // service
        request.readString() // method
        assertFalse(request.readBoolean())
        assertEquals(goldenLine.split(" ")[0], request.readString())
        assertArrayEquals(java.util.Base64.getDecoder().decode(goldenLine.split(" ")[1]), request.readStringAsBytes())
    }

    @Test
    fun `the authorized_keys line of a Keystore key is the one OpenSSH writes`() {
        // What the app shows the user to put in authorized_keys must be the line the method announces.
        val line = SshKeyManager().formatKeystorePublicKeyOpenSsh(goldenKey(), "golden-vector")

        assertEquals(goldenLine, line)
    }

    @Test
    fun `a BouncyCastle key is read as EC P-256, as a key of Conscrypt is not the JDK's`() {
        val provider = org.bouncycastle.jce.provider.BouncyCastleProvider()
        val p256 = KeyPairGenerator.getInstance("EC", provider).apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val p384 = KeyPairGenerator.getInstance("EC", provider).apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        val p521 = KeyPairGenerator.getInstance("EC", provider).apply { initialize(ECGenParameterSpec("secp521r1")) }.generateKeyPair()

        assertTrue(isBiometricKeyAlgorithm(p256.public))
        assertFalse(isBiometricKeyAlgorithm(p384.public))
        assertFalse(isBiometricKeyAlgorithm(p521.public))
        assertThrows(IllegalArgumentException::class.java) { BiometricAuthMethod(p384.public) { null } }
    }

    @Test
    fun `a BouncyCastle key signs and verifies end to end like a JDK one`() {
        val provider = org.bouncycastle.jce.provider.BouncyCastleProvider()
        val bc = KeyPairGenerator.getInstance("EC", provider).apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val signer = RecordingSigner(bc)
        val method = BiometricAuthMethod(bc.public, signer::sign).also { it.init(params) }

        method.handle(Message.USERAUTH_60, SSHPacket())

        val request = Buffer.PlainBuffer(written.single())
        request.readByte()
        repeat(3) { request.readString() }
        assertTrue(request.readBoolean())
        assertEquals("ecdsa-sha2-nistp256", request.readString())
        request.readStringAsBytes() // key blob
        val field = request.readStringAsBytes()
        val verifier = SignatureECDSA.Factory256().create().apply {
            initVerify(bc.public)
            update(signer.receivedCopy!!)
        }
        assertTrue(verifier.verify(field))
    }
}
