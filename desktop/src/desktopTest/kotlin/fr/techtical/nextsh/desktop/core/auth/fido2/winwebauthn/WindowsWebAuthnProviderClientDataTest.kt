// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.auth.fido2.winwebauthn

import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import fr.techtical.nextsh.desktop.core.auth.fido2.Fido2Signer
import fr.techtical.nextsh.desktop.core.auth.fido2.YubiKitFidoManager
import kotlinx.coroutines.test.runTest
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Tests du client data envoyé à `webauthn.dll` pour un GetAssertion SSH (contrat :
 * [Fido2Signer.signChallenge]). L'appel natif est remplacé par un fake qui capture le client
 * data tel que Windows le lirait. Le hachage par webauthn.dll et la signature de
 * l'authenticator restent à valider sur Windows avec une vraie clé.
 */
class WindowsWebAuthnProviderClientDataTest {

    private val credentialId = ByteArray(16) { (it + 50).toByte() }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    // ── buildSshClientData (fonction pure isolée) ───────────────────────────

    @Test
    fun `buildSshClientData places the raw challenge in pbClientDataJSON unchanged`() {
        val challenge = ByteArray(113) { (it * 7 + 3).toByte() }

        val built = WindowsWebAuthnProvider.buildSshClientData(challenge)
        val struct = built.struct.reloadedFromNative()

        assertEquals(challenge.size, struct.cbClientDataJSON)
        assertContentEquals(challenge, struct.nativeBytes(), "native bytes must be the raw challenge")
    }

    @Test
    fun `buildSshClientData does not pre-hash the challenge`() {
        val challenge = "ssh-signing-buffer".toByteArray()

        val built = WindowsWebAuthnProvider.buildSshClientData(challenge)
        val native = built.struct.reloadedFromNative().nativeBytes()

        assertFalse(native.contentEquals(sha256(challenge)), "pbClientDataJSON must not hold SHA-256(challenge)")
    }

    @Test
    fun `buildSshClientData asks Windows for SHA-256 and version 1`() {
        val built = WindowsWebAuthnProvider.buildSshClientData(byteArrayOf(1, 2, 3))
        val struct = built.struct.reloadedFromNative()

        assertEquals(1, struct.dwVersion)
        assertEquals("SHA-256", struct.pwszHashAlgId.toString())
    }

    @Test
    fun `buildSshClientData copies the challenge so later caller mutation does not leak in`() {
        val challenge = ByteArray(20) { it.toByte() }
        val snapshot = challenge.copyOf()

        val built = WindowsWebAuthnProvider.buildSshClientData(challenge)
        challenge.fill(0)

        assertContentEquals(snapshot, built.struct.reloadedFromNative().nativeBytes())
    }

    @Test
    fun `buildSshClientData rejects an empty challenge`() {
        assertFailsWith<IllegalArgumentException> {
            WindowsWebAuthnProvider.buildSshClientData(ByteArray(0))
        }
    }

    // ── Chemin complet signChallenge -> appel natif (fake) ──────────────────

    /**
     * Fake [WebAuthnLibrary] qui capture, au moment de GetAssertion, le client data relu
     * depuis la mémoire native et l'algorithme de hachage demandé, puis échoue.
     */
    private class CapturingWebAuthnLibrary : WebAuthnLibrary {
        var getAssertionCalls = 0
        var capturedClientDataBytes: ByteArray? = null
        var capturedHashAlg: String? = null

        override fun WebAuthNGetApiVersionNumber(): Int = 3

        override fun WebAuthNAuthenticatorMakeCredential(
            hWnd: Pointer?,
            pRp: WinWebAuthnRpEntityInfo,
            pUser: WinWebAuthnUserEntityInfo,
            pPubKeyCredParams: WinWebAuthnCoseCredentialParameters,
            pClientData: WinWebAuthnClientData,
            pOptions: WinWebAuthnMakeCredentialOptions,
            ppAttestation: PointerByReference,
        ): Int = WebAuthnLibrary.S_OK

        override fun WebAuthNAuthenticatorGetAssertion(
            hWnd: Pointer?,
            pwszRpId: WString,
            pClientData: WinWebAuthnClientData,
            pOptions: WinWebAuthnGetAssertionOptions,
            ppAssertion: PointerByReference,
        ): Int {
            getAssertionCalls++
            val seenByWindows = pClientData.reloadedFromNative()
            capturedClientDataBytes = seenByWindows.pbClientDataJSON
                ?.getByteArray(0, seenByWindows.cbClientDataJSON)
            capturedHashAlg = seenByWindows.pwszHashAlgId?.toString()
            return -1 // échec générique : on ne teste que l'entrée
        }

        override fun WebAuthNFreeCredentialAttestation(pAttestation: Pointer) {}
        override fun WebAuthNFreeAssertion(pAssertion: Pointer) {}
        override fun WebAuthNGetCancellationId(pCancellationId: Pointer): Int = WebAuthnLibrary.S_OK
        override fun WebAuthNCancelCurrentOperation(pCancellationId: Pointer): Int = WebAuthnLibrary.S_OK
        override fun WebAuthNGetErrorName(hr: Int): WString? = null
    }

    @Test
    fun `signChallenge hands the raw challenge to webauthn dll with SHA-256 hash alg, exactly once`() = runTest {
        val lib = CapturingWebAuthnLibrary()
        val signer: Fido2Signer = WindowsWebAuthnProvider(lib = lib)
        val challenge = ByteArray(97) { (it * 11 + 5).toByte() }

        assertFailsWith<YubiKitFidoManager.FidoError> {
            signer.signChallenge(rpId = "ssh:", challenge = challenge, credentialId = credentialId)
        }

        assertEquals(1, lib.getAssertionCalls, "GetAssertion must be invoked exactly once")
        val captured = assertNotNull(lib.capturedClientDataBytes)
        assertContentEquals(challenge, captured, "webauthn.dll must receive the raw challenge to hash itself")
        assertFalse(captured.contentEquals(sha256(challenge)), "challenge must not be pre-hashed")
        assertEquals("SHA-256", lib.capturedHashAlg)
    }

    @Test
    fun `signChallenge with requireUv variant also forwards the raw challenge`() = runTest {
        val lib = CapturingWebAuthnLibrary()
        val provider = WindowsWebAuthnProvider(lib = lib)
        val challenge = "another-signing-buffer".toByteArray()

        assertFailsWith<YubiKitFidoManager.FidoError> {
            provider.signChallenge(
                rpId = "ssh:",
                challenge = challenge,
                credentialId = credentialId,
                requireUv = true,
                timeoutMs = 5_000,
            )
        }

        assertContentEquals(challenge, assertNotNull(lib.capturedClientDataBytes))
    }
}

/**
 * Efface les champs Kotlin puis les relit depuis la mémoire native de la struct : vérifie le
 * layout JNA (ce que Windows lirait), pas seulement les valeurs assignées côté JVM.
 */
private fun WinWebAuthnClientData.reloadedFromNative(): WinWebAuthnClientData {
    dwVersion = 0
    cbClientDataJSON = -1
    pbClientDataJSON = null
    pwszHashAlgId = null
    read()
    return this
}

private fun WinWebAuthnClientData.nativeBytes(): ByteArray {
    val ptr = assertNotNull(pbClientDataJSON, "pbClientDataJSON must be set")
    return ptr.getByteArray(0, cbClientDataJSON)
}
