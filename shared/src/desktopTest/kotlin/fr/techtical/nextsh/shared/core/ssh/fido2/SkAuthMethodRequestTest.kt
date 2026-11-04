// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.ssh.fido2

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Message
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.transport.Transport
import net.schmizz.sshj.userauth.AuthParams
import net.schmizz.sshj.userauth.UserAuthException
import java.lang.reflect.Proxy
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifie, au niveau de [SkAuthMethod], le contenu exact des requetes USERAUTH_REQUEST
 * ecrites sur le transport : le champ signature doit etre UNE string SSH dont le contenu est
 * le blob PROTOCOL.u2f (`string type || string sig || byte flags || uint32 counter`), sans
 * second niveau de prefixe de longueur.
 *
 * Le transport SSHJ est un proxy dynamique qui ne retient que `write` et `getSessionID`.
 */
class SkAuthMethodRequestTest {

    private val sessionId = ByteArray(32) { (0x40 + it).toByte() }
    private val written = mutableListOf<ByteArray>()

    private val transport: Transport = Proxy.newProxyInstance(
        Transport::class.java.classLoader,
        arrayOf(Transport::class.java),
    ) { _, method, args ->
        when (method.name) {
            "write" -> {
                written += (args[0] as SSHPacket).compactData
                0L
            }
            "getSessionID" -> sessionId
            "toString" -> "FakeTransport"
            "hashCode" -> 1
            "equals" -> false
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        }
    } as Transport

    private val authParams = object : AuthParams {
        override fun getNextServiceName(): String = "ssh-connection"
        override fun getTransport(): Transport = this@SkAuthMethodRequestTest.transport
        override fun getUsername(): String = "alice"
    }

    @BeforeTest
    fun reset() {
        written.clear()
    }

    private fun ed25519Key() = SkSshPublicKey(SkKeyType.SK_ED25519, ByteArray(32) { it.toByte() }, "ssh:")

    private fun ecdsaKey(): SkSshPublicKey {
        val point = byteArrayOf(0x04) + ByteArray(32) { (it + 1).toByte() } + ByteArray(32) { (it + 101).toByte() }
        return SkSshPublicKey(SkKeyType.SK_ECDSA_256, point, "ssh:")
    }

    /** Champs d'une USERAUTH_REQUEST publickey signee, tels que lus par un serveur. */
    private class SignedRequest(
        val username: String,
        val service: String,
        val method: String,
        val signed: Boolean,
        val algorithm: String,
        val publicKeyBlob: ByteArray,
        val signatureField: ByteArray,
        val remaining: Int,
    )

    private fun parseSignedRequest(packet: ByteArray): SignedRequest {
        val buf = Buffer.PlainBuffer(packet)
        assertEquals(Message.USERAUTH_REQUEST.toByte(), buf.readByte())
        val username = buf.readString()
        val service = buf.readString()
        val method = buf.readString()
        val signed = buf.readBoolean()
        val algorithm = buf.readString()
        val blob = buf.readStringAsBytes()
        val signature = buf.readStringAsBytes()
        return SignedRequest(username, service, method, signed, algorithm, blob, signature, buf.available())
    }

    private fun runSignedExchange(
        key: SkSshPublicKey,
        signature: SkSshSignature?,
        onSigningData: (ByteArray) -> Unit = {},
    ) {
        val method = SkAuthMethod(key) { signingData ->
            onSigningData(signingData.copyOf())
            signature
        }
        method.init(authParams)
        method.handle(Message.USERAUTH_60, SSHPacket())
    }

    @Test
    fun `ed25519 signed request carries the PROTOCOL u2f blob as a single string`() {
        val key = ed25519Key()
        val raw = ByteArray(64) { (0xA0 + it).toByte() }
        val sig = SkSshSignature.create(SkKeyType.SK_ED25519, raw.copyOf(), 0x05, 77u)
        val expectedBlob = SkSshSignature.create(SkKeyType.SK_ED25519, raw.copyOf(), 0x05, 77u).encodeSignatureBlob()

        runSignedExchange(key, sig)

        assertEquals(1, written.size)
        val req = parseSignedRequest(written[0])
        assertEquals("alice", req.username)
        assertEquals("ssh-connection", req.service)
        assertEquals("publickey", req.method)
        assertTrue(req.signed)
        assertEquals("sk-ssh-ed25519@openssh.com", req.algorithm)
        assertContentEquals(key.toBlob(), req.publicKeyBlob)
        assertEquals(0, req.remaining, "nothing may follow the signature string")

        // Le contenu de la string signature est exactement le blob, sans prefixe en plus.
        assertContentEquals(expectedBlob, req.signatureField)
        val inner = Buffer.PlainBuffer(req.signatureField)
        assertEquals("sk-ssh-ed25519@openssh.com", inner.readString())
        assertContentEquals(raw, inner.readStringAsBytes())
        assertEquals(0x05.toByte(), inner.readByte())
        assertEquals(77L, inner.readUInt32())
        assertEquals(0, inner.available())
    }

    @Test
    fun `ecdsa signed request carries mpint r and s inside the blob`() {
        val key = ecdsaKey()
        val der = byteArrayOf(0x30, 0x0a, 0x02, 0x03, 0x00, 0x80.toByte(), 0x01, 0x02, 0x03, 0x00, 0xff.toByte(), 0x02)
        val sig = SkSshSignature.create(SkKeyType.SK_ECDSA_256, der, SkSshSignature.FLAG_USER_PRESENT, 9u)

        runSignedExchange(key, sig)

        val req = parseSignedRequest(written.single())
        assertEquals("sk-ecdsa-sha2-nistp256@openssh.com", req.algorithm)
        assertEquals(0, req.remaining)
        val inner = Buffer.PlainBuffer(req.signatureField)
        assertEquals("sk-ecdsa-sha2-nistp256@openssh.com", inner.readString())
        val rs = Buffer.PlainBuffer(inner.readStringAsBytes())
        assertContentEquals(byteArrayOf(0x00, 0x80.toByte(), 0x01), rs.readStringAsBytes())
        assertContentEquals(byteArrayOf(0x00, 0xff.toByte(), 0x02), rs.readStringAsBytes())
        assertEquals(0, rs.available())
        assertEquals(SkSshSignature.FLAG_USER_PRESENT, inner.readByte())
        assertEquals(9L, inner.readUInt32())
        assertEquals(0, inner.available())
    }

    @Test
    fun `challenge handler receives the RFC 4252 signing data`() {
        val key = ed25519Key()
        var captured: ByteArray? = null
        val sig = SkSshSignature.create(SkKeyType.SK_ED25519, ByteArray(64), SkSshSignature.FLAG_USER_PRESENT, 1u)

        runSignedExchange(key, sig) { captured = it }

        val buf = Buffer.PlainBuffer(captured!!)
        assertContentEquals(sessionId, buf.readStringAsBytes())
        assertEquals(Message.USERAUTH_REQUEST.toByte(), buf.readByte())
        assertEquals("alice", buf.readString())
        assertEquals("ssh-connection", buf.readString())
        assertEquals("publickey", buf.readString())
        assertTrue(buf.readBoolean())
        assertEquals("sk-ssh-ed25519@openssh.com", buf.readString())
        assertContentEquals(key.toBlob(), buf.readStringAsBytes())
        assertEquals(0, buf.available())
    }

    @Test
    fun `feeler request is unsigned and has no signature field`() {
        val key = ed25519Key()
        val method = SkAuthMethod(key) { null }
        method.init(authParams)

        method.request()

        val buf = Buffer.PlainBuffer(written.single())
        assertEquals(Message.USERAUTH_REQUEST.toByte(), buf.readByte())
        assertEquals("alice", buf.readString())
        assertEquals("ssh-connection", buf.readString())
        assertEquals("publickey", buf.readString())
        assertEquals(false, buf.readBoolean())
        assertEquals("sk-ssh-ed25519@openssh.com", buf.readString())
        assertContentEquals(key.toBlob(), buf.readStringAsBytes())
        assertEquals(0, buf.available())
    }

    @Test
    fun `malformed ecdsa signature writes nothing and fails the authentication`() {
        val key = ecdsaKey()
        val sig = SkSshSignature.create(SkKeyType.SK_ECDSA_256, ByteArray(64) { 1 }, SkSshSignature.FLAG_USER_PRESENT, 1u)

        val ex = assertFailsWith<UserAuthException> { runSignedExchange(key, sig) }

        assertTrue(ex.cause is IllegalArgumentException)
        assertTrue(written.isEmpty(), "no request may reach the transport")
    }

    @Test
    fun `cancelled challenge fails the authentication without writing`() {
        val key = ed25519Key()

        assertFailsWith<UserAuthException> { runSignedExchange(key, null) }

        assertTrue(written.isEmpty())
    }

    @Test
    fun `signature is wiped once the request is sent`() {
        val key = ed25519Key()
        val raw = ByteArray(64) { 0x55 }
        val sig = SkSshSignature.create(SkKeyType.SK_ED25519, raw, SkSshSignature.FLAG_USER_PRESENT, 1u)

        runSignedExchange(key, sig)

        assertEquals(1, written.size)
        assertTrue(raw.all { it == 0.toByte() }, "raw signature must be wiped by SkAuthMethod")
    }
}
