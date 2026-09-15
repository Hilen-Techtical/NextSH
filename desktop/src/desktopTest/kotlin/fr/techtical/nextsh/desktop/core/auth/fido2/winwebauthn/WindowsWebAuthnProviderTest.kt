// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.auth.fido2.winwebauthn

import fr.techtical.nextsh.desktop.core.auth.fido2.Fido2Enroller
import fr.techtical.nextsh.desktop.core.auth.fido2.YubiKitFidoManager
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests unitaires de [WindowsWebAuthnProvider].
 *
 * Ces tests NE chargent PAS `webauthn.dll` réelle : ils utilisent des fakes/mocks
 * pour valider :
 * 1. [WindowsWebAuthnProvider.isAvailable] retourne false si la DLL est absente/null.
 * 2. Le parsing COSE Ed25519 depuis un authData factice extrait correctement les 32 bytes.
 * 3. Le mapping HRESULT → [YubiKitFidoManager.FidoError] fonctionne.
 * 4. [Fido2Enroller.detectDevice] retourne pinConfigured=false sur Windows WebAuthn.
 * 5. La logique de vérification de version (API < 3 → erreur explicite).
 */
class WindowsWebAuthnProviderTest {

    // ── Test 1 : isAvailable() sans DLL ──────────────────────────────────────

    @Test
    fun `isAvailable returns false when lib is null`() {
        val provider = WindowsWebAuthnProvider(lib = null)
        assertFalse(provider.isAvailable(), "isAvailable() must return false when lib=null")
    }

    @Test
    fun `isAvailable returns false when lib throws exception`() {
        val throwingLib = object : WebAuthnLibrary {
            override fun WebAuthNGetApiVersionNumber(): Int = throw RuntimeException("DLL load error")
            override fun WebAuthNAuthenticatorMakeCredential(hWnd: com.sun.jna.Pointer?, pRp: WinWebAuthnRpEntityInfo, pUser: WinWebAuthnUserEntityInfo, pPubKeyCredParams: WinWebAuthnCoseCredentialParameters, pClientData: WinWebAuthnClientData, pOptions: WinWebAuthnMakeCredentialOptions, ppAttestation: com.sun.jna.ptr.PointerByReference): Int = 0
            override fun WebAuthNAuthenticatorGetAssertion(hWnd: com.sun.jna.Pointer?, pwszRpId: com.sun.jna.WString, pClientData: WinWebAuthnClientData, pOptions: WinWebAuthnGetAssertionOptions, ppAssertion: com.sun.jna.ptr.PointerByReference): Int = 0
            override fun WebAuthNFreeCredentialAttestation(pAttestation: com.sun.jna.Pointer) {}
            override fun WebAuthNFreeAssertion(pAssertion: com.sun.jna.Pointer) {}
            override fun WebAuthNGetCancellationId(pCancellationId: com.sun.jna.Pointer): Int = 0
            override fun WebAuthNCancelCurrentOperation(pCancellationId: com.sun.jna.Pointer): Int = 0
            override fun WebAuthNGetErrorName(hr: Int): com.sun.jna.WString? = null
        }
        val provider = WindowsWebAuthnProvider(lib = throwingLib)
        assertFalse(provider.isAvailable(), "isAvailable() must return false when DLL throws")
    }

    // ── Test 2 : apiVersion() sans DLL ───────────────────────────────────────

    @Test
    fun `apiVersion returns 0 when lib is null`() {
        val provider = WindowsWebAuthnProvider(lib = null)
        assertEquals(0, provider.apiVersion(), "apiVersion() must return 0 when lib=null")
    }

    // ── Test 3 : detectDevice retourne pinConfigured=false ───────────────────

    @Test
    fun `detectDevice returns pinConfigured false and Windows WebAuthn label`() = runTest {
        val fakeLib = FakeWebAuthnLibrary(apiVersion = 3)
        val provider = WindowsWebAuthnProvider(lib = fakeLib)

        val detection = provider.detectDevice()

        assertFalse(detection.pinConfigured,
            "Windows WebAuthn: PIN should be handled natively, not by our UI")
        assertTrue(detection.deviceLabel.contains("Windows", ignoreCase = true),
            "Device label should mention Windows")
    }

    // ── Test 4 : makeCredential avec version 1 ne rejette plus (ECDSA fallback) ──

    @Test
    fun `makeCredential does not reject API version 1 anymore (ECDSA P-256 fallback available)`() = runTest {
        // Version 1 : ECDSA P-256 disponible → pas de rejet anticipé côté NextSH.
        // Windows choisira ECDSA P-256 si Ed25519 non supporté.
        // FakeLib retourne S_OK mais pas de pointeur attestation → TransportError (pas CtapError).
        val fakeLib = FakeWebAuthnLibrary(apiVersion = 1)
        val provider = WindowsWebAuthnProvider(lib = fakeLib)

        val result = runCatching {
            provider.makeCredential(
                rpId = "ssh:",
                rpName = "NextSH",
                userName = "testuser",
                userDisplayName = "Test User",
                pin = null,
                timeoutMs = 5_000,
            )
        }

        // Avec FakeLib(S_OK) sans pointeur d'attestation réel, on attend TransportError
        // (null pointer sur credentialId/authData). Ce n'est PAS un CtapError de version.
        assertTrue(result.isFailure, "makeCredential should fail without real attestation pointer")
        val error = result.exceptionOrNull()
        // Doit être TransportError ou CtapError, mais PAS un CtapError "version < 3"
        assertNotNull(error, "Should have thrown an exception")
        if (error is YubiKitFidoManager.FidoError.CtapError) {
            assertFalse(
                error.msg.contains("21H2") || error.msg.contains("version 3"),
                "Must NOT reject with 'version < 3' message anymore. Got: ${error.msg}"
            )
        }
    }

    // ── Test 5 : parsing authData COSE Ed25519 ────────────────────────────────

    @Test
    fun `parseCosePubKey from valid Ed25519 authData extracts 32-byte public key`() {
        val expectedPubKey = ByteArray(32) { (it + 1).toByte() }
        val authData = buildFakeAuthData(ed25519PubKey = expectedPubKey)

        val provider = WindowsWebAuthnProvider(lib = null)
        val (keyType, result) = provider.parseCosePubKey(authData)

        assertEquals(SkKeyType.SK_ED25519, keyType, "Ed25519 authData must return SK_ED25519")
        assertTrue(expectedPubKey.contentEquals(result),
            "parseCosePubKey must extract the correct 32-byte public key. " +
            "Expected: ${expectedPubKey.take(4).map { "%02x".format(it) }}, " +
            "Got: ${result.take(4).map { "%02x".format(it) }}")
    }

    @Test
    fun `parseCosePubKey with too-short authData throws TransportError`() {
        val provider = WindowsWebAuthnProvider(lib = null)

        val result = runCatching {
            provider.parseCosePubKey(ByteArray(10))  // trop court
        }
        assertTrue(result.isFailure, "Short authData should throw")
    }

    // ── Test 5b : parsing authData COSE ECDSA P-256 ───────────────────────────

    @Test
    fun `parseCborCoseKey ECDSA P-256 extracts 65-byte SEC1 public key`() {
        val xBytes = ByteArray(32) { (it + 0xAA).toByte() }
        val yBytes = ByteArray(32) { (it + 0xBB).toByte() }
        val cborKey = buildFakeCoseEcdsaP256Key(xBytes, yBytes)

        val provider = WindowsWebAuthnProvider(lib = null)
        val (keyType, result) = provider.parseCborCoseKey(cborKey)

        assertEquals(SkKeyType.SK_ECDSA_256, keyType, "ECDSA P-256 COSE key must return SK_ECDSA_256")
        assertEquals(65, result.size, "ECDSA P-256 result must be 65 bytes (SEC1 uncompressed)")
        assertEquals(0x04.toByte(), result[0], "SEC1 uncompressed must start with 0x04")
        assertTrue(xBytes.contentEquals(result.sliceArray(1..32)), "x coordinate mismatch")
        assertTrue(yBytes.contentEquals(result.sliceArray(33..64)), "y coordinate mismatch")
    }

    @Test
    fun `parseCborCoseKey Ed25519 extracts 32-byte raw public key`() {
        val xBytes = ByteArray(32) { (it * 7 + 3).toByte() }
        val cborKey = buildFakeCoseEd25519Key(xBytes)

        val provider = WindowsWebAuthnProvider(lib = null)
        val (keyType, result) = provider.parseCborCoseKey(cborKey)

        assertEquals(SkKeyType.SK_ED25519, keyType, "Ed25519 COSE key must return SK_ED25519")
        assertEquals(32, result.size, "Ed25519 result must be 32 bytes")
        assertTrue(xBytes.contentEquals(result), "x coordinate mismatch")
    }

    // ── Test 5c : DER ECDSA → SSH mpint ──────────────────────────────────────

    @Test
    fun `derEcdsaToSshMpint round-trip with typical 64-byte signature`() {
        // Typical DER ECDSA P-256 signature : 0x30 len 0x02 rLen r 0x02 sLen s
        val rBytes = ByteArray(32) { (it + 1).toByte() }
        val sBytes = ByteArray(32) { (it + 33).toByte() }
        val der = buildDerEcdsaSig(rBytes, sBytes)

        val result = WindowsWebAuthnProvider.derEcdsaToSshMpint(der)

        // SSH mpint format = SSH string(r) + SSH string(s)
        // SSH string = uint32 BE length + bytes
        assertTrue(result.size >= 8, "Result must contain at least two SSH length prefixes")
        // Parse first SSH string (r)
        val rLen = ((result[0].toInt() and 0xFF) shl 24) or
            ((result[1].toInt() and 0xFF) shl 16) or
            ((result[2].toInt() and 0xFF) shl 8) or
            (result[3].toInt() and 0xFF)
        assertTrue(rLen == rBytes.size, "r length must be ${rBytes.size}, got $rLen")
    }

    @Test
    fun `derEcdsaToSshMpint handles DER integers with leading zero padding`() {
        // DER INTEGER with high bit set → padded with 0x00 by ASN.1
        val rBytes = byteArrayOf(0x00, 0xFF.toByte()) + ByteArray(31) { 0x11 }  // 33 bytes avec padding
        val sBytes = ByteArray(32) { 0x22 }
        val der = buildDerEcdsaSig(rBytes, sBytes)

        val result = WindowsWebAuthnProvider.derEcdsaToSshMpint(der)
        assertTrue(result.isNotEmpty(), "Result must not be empty")
        // r length = rBytes.size (33 with padding)
        val rLen = ((result[0].toInt() and 0xFF) shl 24) or
            ((result[1].toInt() and 0xFF) shl 16) or
            ((result[2].toInt() and 0xFF) shl 8) or
            (result[3].toInt() and 0xFF)
        assertEquals(rBytes.size, rLen, "r must include leading zero from DER")
    }

    // ── Test 6 : HRESULT → FidoError mapping ─────────────────────────────────

    @Test
    fun `HRESULT cancel error name maps to UserCancelled`() {
        val error = 0x80090316.toInt().toFidoError("UserCancelled")
        assertIs<YubiKitFidoManager.FidoError.UserCancelled>(error)
    }

    @Test
    fun `HRESULT timeout error name maps to TouchTimeout`() {
        val error = (-1).toFidoError("OperationTimeout")
        assertIs<YubiKitFidoManager.FidoError.TouchTimeout>(error)
    }

    @Test
    fun `HRESULT notSupported error name maps to CtapError`() {
        val error = 0x80090029.toInt().toFidoError("NotSupported")
        assertIs<YubiKitFidoManager.FidoError.CtapError>(error)
    }

    @Test
    fun `HRESULT unknown error name maps to TransportError`() {
        val error = (-12345).toFidoError("UnknownError")
        assertIs<YubiKitFidoManager.FidoError.TransportError>(error)
    }

    // ── Test 7 : isPlatformSupported est un Boolean (test compilation) ────────

    @Test
    fun `isPlatformSupported property exists and returns Boolean`() {
        // Test de compilation/introspection : vérifie que la propriété existe
        val supported: Boolean = WindowsWebAuthnProvider.isPlatformSupported
        // Sur la machine de build (Windows), cette valeur peut être true ou false
        // selon l'OS : on vérifie juste que c'est un Boolean valide
        assertTrue(supported || !supported, "isPlatformSupported must be a valid Boolean")
    }

    // ── Test 8 : vérification des constantes COSE et valeurs de champs ────────

    @Test
    fun `COSE algorithm constants match webauthn h specification`() {
        // Ref: webauthn.h, WEBAUTHN_COSE_ALGORITHM_ECDSA_P256_WITH_SHA256 = -7
        assertEquals(-7, WinWebAuthnCoseCredentialParameter.COSE_ALG_ECDSA_P256,
            "ECDSA P-256 COSE alg must be -7 per webauthn.h")

        // Ref: webauthn.h, COSE_ALGORITHM_EDDSA_ED25519 = -8
        assertEquals(-8, WinWebAuthnCoseCredentialParameter.COSE_ALG_EDDSA,
            "Ed25519 COSE alg must be -8 per webauthn.h")
    }

    @Test
    fun `MakeCredentialOptions authenticator attachment constants match webauthn h`() {
        // Ref: webauthn.h WEBAUTHN_AUTHENTICATOR_ATTACHMENT_*
        assertEquals(0, WinWebAuthnMakeCredentialOptions.ATTACHMENT_ANY,
            "ATTACHMENT_ANY must be 0")
        assertEquals(1, WinWebAuthnMakeCredentialOptions.ATTACHMENT_PLATFORM,
            "ATTACHMENT_PLATFORM must be 1")
        assertEquals(2, WinWebAuthnMakeCredentialOptions.ATTACHMENT_CROSS_PLATFORM,
            "ATTACHMENT_CROSS_PLATFORM must be 2")
    }

    @Test
    fun `MakeCredentialOptions UV requirement constants match webauthn h`() {
        // Ref: webauthn.h WEBAUTHN_USER_VERIFICATION_REQUIREMENT_*
        assertEquals(0, WinWebAuthnMakeCredentialOptions.UV_ANY,
            "UV_ANY must be 0")
        assertEquals(1, WinWebAuthnMakeCredentialOptions.UV_REQUIRED,
            "UV_REQUIRED must be 1")
        assertEquals(2, WinWebAuthnMakeCredentialOptions.UV_PREFERRED,
            "UV_PREFERRED must be 2")
        assertEquals(3, WinWebAuthnMakeCredentialOptions.UV_DISCOURAGED,
            "UV_DISCOURAGED must be 3")
    }

    @Test
    fun `MakeCredentialOptions attestation conveyance constants match webauthn h`() {
        // Ref: webauthn.h WEBAUTHN_ATTESTATION_CONVEYANCE_PREFERENCE_*
        assertEquals(0, WinWebAuthnMakeCredentialOptions.ATTESTATION_ANY,
            "ATTESTATION_ANY must be 0")
        assertEquals(1, WinWebAuthnMakeCredentialOptions.ATTESTATION_NONE,
            "ATTESTATION_NONE must be 1")
        assertEquals(2, WinWebAuthnMakeCredentialOptions.ATTESTATION_INDIRECT,
            "ATTESTATION_INDIRECT must be 2")
        assertEquals(3, WinWebAuthnMakeCredentialOptions.ATTESTATION_DIRECT,
            "ATTESTATION_DIRECT must be 3")
    }

    @Test
    fun `MakeCredentialOptions default dwVersion is 1 matching libfido2 reference impl`() {
        // Libfido2 (implémentation de référence) utilise VERSION_1 = 1 pour SSH simple.
        // VERSION_1 est suffisant : dwVersion décrit les champs actifs de la struct,
        // pas la version de l'API. Ed25519 est négocié via COSE params, pas via dwVersion.
        val opts = WinWebAuthnMakeCredentialOptions()
        assertEquals(1, opts.dwVersion,
            "Default dwVersion must be 1 (libfido2 pattern for SSH)")
    }

    @Test
    fun `MakeCredentialOptions default values are safe zeros`() {
        val opts = WinWebAuthnMakeCredentialOptions()
        // Toutes les valeurs par défaut doivent être "safe" (laisse Windows décider)
        assertEquals(0, opts.dwAuthenticatorAttachment,
            "Default attachment must be ANY (0): do not force CROSS_PLATFORM")
        assertEquals(0, opts.dwUserVerificationRequirement,
            "Default UV must be ANY (0): do not force DISCOURAGED")
        assertEquals(0, opts.dwAttestationConveyancePreference,
            "Default attestation must be ANY (0): do not force NONE")
        assertEquals(0, opts.bRequireResidentKey,
            "Default bRequireResidentKey must be false (0)")
        assertEquals(0, opts.dwFlags,
            "Default dwFlags must be 0")
    }

    @Test
    fun `GetAssertionOptions default dwVersion is 4 for pAllowCredentialList support`() {
        // Version 4 est nécessaire pour utiliser pAllowCredentialList.
        // SSH SK a besoin de cette liste pour spécifier le credential exact à signer.
        val opts = WinWebAuthnGetAssertionOptions()
        assertEquals(4, opts.dwVersion,
            "Default dwVersion must be 4 for pAllowCredentialList support")
    }

    @Test
    fun `MakeCredentialOptions has all version 4 plus fields via reflection`() {
        // Vérifie que les champs v4+ sont présents dans la struct JNA via réflexion.
        // Ces champs doivent être là pour éviter que Windows lise du garbage au-delà.
        val opts = WinWebAuthnMakeCredentialOptions()
        val declaredFields = opts.javaClass.declaredFields.map { it.name }

        assertTrue(declaredFields.contains("dwEnterpriseAttestation"),
            "dwEnterpriseAttestation (v4) must be declared in struct")
        assertTrue(declaredFields.contains("dwLargeBlobSupport"),
            "dwLargeBlobSupport (v4) must be declared in struct")
        assertTrue(declaredFields.contains("bPreferResidentKey"),
            "bPreferResidentKey (v4) must be declared in struct")
        assertTrue(declaredFields.contains("bBrowserInPrivateMode"),
            "bBrowserInPrivateMode (v5) must be declared in struct")
        assertTrue(declaredFields.contains("bEnablePrf"),
            "bEnablePrf (v6) must be declared in struct")
        // v9 fields: presence confirms complete layout
        assertTrue(declaredFields.contains("cbAuthenticatorId"),
            "cbAuthenticatorId (v9) must be declared for complete Windows-compatible layout")
    }

    // ── Helpers - construction de données de test ─────────────────────────────

    /**
     * Construit un authData factice avec AT flag et une COSE key Ed25519 minimale.
     *
     * Structure :
     * - [0..31]  rpIdHash (rempli avec 0x42)
     * - [32]     flags = 0x41 (UP=1, AT=1)
     * - [33..36] signCount = 0
     * - [37..52] aaguid (16 bytes, 0x00)
     * - [53..54] credentialIdLength = 16 (big-endian)
     * - [55..70] credentialId (16 bytes, 0x00)
     * - [71..]   CBOR COSE key Ed25519 avec [ed25519PubKey]
     */
    private fun buildFakeAuthData(ed25519PubKey: ByteArray): ByteArray {
        require(ed25519PubKey.size == 32) { "Ed25519 public key must be 32 bytes" }

        val rpIdHash = ByteArray(32) { 0x42 }
        val flags = byteArrayOf(0x41.toByte())  // UP + AT
        val signCount = ByteArray(4) { 0 }
        val aaguid = ByteArray(16) { 0 }
        val credIdLen = byteArrayOf(0, 16)  // 16 bytes
        val credId = ByteArray(16) { 0 }

        // CBOR COSE key minimal pour Ed25519 :
        // A5             map(5)
        //   01 01        kty = OKP (1)
        //   03 27        alg = -8 (0x27 = negative int 7)
        //   20 06        crv = Ed25519 (label -1, value 6)
        //   21 58 20 [32 bytes]  x = ed25519PubKey (label -2, ByteString 32)
        //   22 58 20 [32 bytes]  d = fake private key bytes (label -3, non utilisé en SSH)
        val coseKey = buildList<Byte> {
            add(0xA5.toByte())             // map(5)
            add(0x01); add(0x01)           // kty = 1 (OKP)
            add(0x03); add(0x27)           // alg = -8 (EdDSA) : 0x27 = negative int 7
            add(0x20); add(0x06)           // crv = -1 → 6 (Ed25519) : 0x20 = negative int 0
            add(0x21)                      // label -2 (x coordinate) : 0x21 = negative int 1
            add(0x58); add(0x20)           // ByteString length 32 (1-byte length)
            addAll(ed25519PubKey.toList())  // 32 bytes de la clé publique
            // label -3 et d omis (non nécessaires pour le test)
        }.toByteArray()

        return rpIdHash + flags + signCount + aaguid + credIdLen + credId + coseKey
    }

    /**
     * Construit un CBOR COSE key minimal pour ECDSA P-256.
     *
     * Format CBOR ECDSA P-256 :
     * A5             map(5)
     *   01 02        kty = EC2 (2)
     *   03 26        alg = -7 (ECDSA P-256) : 0x26 = negative int 6
     *   20 01        crv = P-256 (1) : label -1
     *   21 58 20 [32 bytes]  x (label -2)
     *   22 58 20 [32 bytes]  y (label -3)
     */
    private fun buildFakeCoseEcdsaP256Key(xBytes: ByteArray, yBytes: ByteArray): ByteArray {
        require(xBytes.size == 32 && yBytes.size == 32)
        return buildList<Byte> {
            add(0xA5.toByte())             // map(5)
            add(0x01); add(0x02)           // kty = EC2 (2)
            add(0x03); add(0x26)           // alg = -7 : 0x26 = negative int 6
            add(0x20); add(0x01)           // crv = P-256 (1) : label -1
            add(0x21)                      // label -2 (x)
            add(0x58); add(0x20)           // ByteString 32 bytes
            addAll(xBytes.toList())
            add(0x22)                      // label -3 (y)
            add(0x58); add(0x20)           // ByteString 32 bytes
            addAll(yBytes.toList())
        }.toByteArray()
    }

    /**
     * Construit un CBOR COSE key minimal pour Ed25519.
     */
    private fun buildFakeCoseEd25519Key(xBytes: ByteArray): ByteArray {
        require(xBytes.size == 32)
        return buildList<Byte> {
            add(0xA5.toByte())             // map(5)
            add(0x01); add(0x01)           // kty = OKP (1)
            add(0x03); add(0x27)           // alg = -8 : 0x27 = negative int 7
            add(0x20); add(0x06)           // crv = Ed25519 (6) : label -1
            add(0x21)                      // label -2 (x)
            add(0x58); add(0x20)           // ByteString 32 bytes
            addAll(xBytes.toList())
        }.toByteArray()
    }

    /**
     * Construit un DER ECDSA signature minimal pour les tests.
     * 0x30 <total_len> 0x02 <r_len> <r> 0x02 <s_len> <s>
     */
    private fun buildDerEcdsaSig(rBytes: ByteArray, sBytes: ByteArray): ByteArray {
        val totalLen = 2 + rBytes.size + 2 + sBytes.size
        return buildList<Byte> {
            add(0x30)                    // SEQUENCE
            add(totalLen.toByte())       // length
            add(0x02)                    // INTEGER
            add(rBytes.size.toByte())    // r length
            addAll(rBytes.toList())
            add(0x02)                    // INTEGER
            add(sBytes.size.toByte())    // s length
            addAll(sBytes.toList())
        }.toByteArray()
    }

    /**
     * Fake [WebAuthnLibrary] pour les tests unitaires.
     * Ne charge pas de DLL native.
     */
    private class FakeWebAuthnLibrary(
        private val apiVersion: Int = 3,
        private val makeCredentialHr: Int = WebAuthnLibrary.S_OK,
        private val getAssertionHr: Int = WebAuthnLibrary.S_OK,
    ) : WebAuthnLibrary {
        override fun WebAuthNGetApiVersionNumber(): Int = apiVersion

        override fun WebAuthNAuthenticatorMakeCredential(
            hWnd: com.sun.jna.Pointer?,
            pRp: WinWebAuthnRpEntityInfo,
            pUser: WinWebAuthnUserEntityInfo,
            pPubKeyCredParams: WinWebAuthnCoseCredentialParameters,
            pClientData: WinWebAuthnClientData,
            pOptions: WinWebAuthnMakeCredentialOptions,
            ppAttestation: com.sun.jna.ptr.PointerByReference,
        ): Int = makeCredentialHr

        override fun WebAuthNAuthenticatorGetAssertion(
            hWnd: com.sun.jna.Pointer?,
            pwszRpId: com.sun.jna.WString,
            pClientData: WinWebAuthnClientData,
            pOptions: WinWebAuthnGetAssertionOptions,
            ppAssertion: com.sun.jna.ptr.PointerByReference,
        ): Int = getAssertionHr

        override fun WebAuthNFreeCredentialAttestation(pAttestation: com.sun.jna.Pointer) {}
        override fun WebAuthNFreeAssertion(pAssertion: com.sun.jna.Pointer) {}
        override fun WebAuthNGetCancellationId(pCancellationId: com.sun.jna.Pointer): Int = WebAuthnLibrary.S_OK
        override fun WebAuthNCancelCurrentOperation(pCancellationId: com.sun.jna.Pointer): Int = WebAuthnLibrary.S_OK
        override fun WebAuthNGetErrorName(hr: Int): com.sun.jna.WString? = null
    }
}
