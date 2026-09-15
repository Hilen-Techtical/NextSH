// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.auth

import com.yubico.yubikit.core.fido.CtapException
import com.yubico.yubikit.fido.ctap.ClientPin
import com.yubico.yubikit.fido.ctap.Ctap2Session
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocolV1
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocolV2
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialDescriptor
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for the CTAP2 PIN flow in YubiKitAuthenticator.
 *
 * These tests exercise [YubiKitAuthenticator.performPinAssertion] and
 * [YubiKitAuthenticator.parseAssertionData] directly, bypassing device discovery
 * (which requires hardware and cannot be unit-tested).
 *
 * The full end-to-end flow (device detection → CTAP2 session open → assertion) is covered
 * by instrumented tests on a real or emulated YubiKey.
 *
 * Note: ClientPin and PinUvAuthProtocol* are mocked via mockkConstructor. Ctap2Session is
 * mocked with mockk(relaxed = true): MockK creates proxy instances without calling the
 * real constructor (which requires I/O).
 */
class YubiKitAuthenticatorPinTest {

    private lateinit var authenticator: YubiKitAuthenticator
    private lateinit var mockCtap: Ctap2Session
    private lateinit var mockInfoData: Ctap2Session.InfoData
    private lateinit var mockAssertionData: Ctap2Session.AssertionData
    private val rpId = "ssh:"
    private val clientDataHash = ByteArray(32) { it.toByte() }
    private val credentialId = ByteArray(16) { 0xAB.toByte() }
    // Minimal valid authenticatorData: rpIdHash(32) + flags(1) + counter(4)
    private val validAuthData = ByteArray(37).also { it[32] = 0x01 }

    @BeforeEach
    fun setUp() {
        authenticator = YubiKitAuthenticator()
        mockInfoData = mockk(relaxed = true)
        mockCtap = mockk(relaxed = true)
        mockAssertionData = mockk(relaxed = true)

        // Default: key advertises protocol V2
        every { mockCtap.cachedInfo } returns mockInfoData
        every { mockInfoData.pinUvAuthProtocols } returns listOf(2, 1)

        // Default assertion data for success cases
        every { mockAssertionData.authenticatorData } returns validAuthData.copyOf()
        every { mockAssertionData.signature } returns ByteArray(64) { 0x42 }
        every { mockAssertionData.getCredentialId(any()) } returns credentialId
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ── Test 1 ───────────────────────────────────────────────────────────────────

    @Test
    fun `parseAssertionData extracts flags counter and signature correctly`() {
        val authData = ByteArray(37)
        authData[32] = 0x05.toByte()          // flags
        authData[33] = 0x00; authData[34] = 0x00; authData[35] = 0x00; authData[36] = 0x07 // counter = 7
        val sig = ByteArray(64) { 0xBE.toByte() }

        val mockData = mockk<Ctap2Session.AssertionData>()
        every { mockData.authenticatorData } returns authData
        every { mockData.signature } returns sig
        every { mockData.getCredentialId(any()) } returns credentialId

        val result = authenticator.parseAssertionData(mockData, emptyList(), listOf(credentialId))

        assertInstanceOf(HwKeyResult.Success::class.java, result)
        val success = result as HwKeyResult.Success
        assertEquals(0x05.toByte(), success.flags, "flags mismatch")
        assertEquals(7u, success.counter, "counter mismatch")
        assertTrue(success.signature.contentEquals(sig), "signature mismatch")
    }

    // ── Test 2 ───────────────────────────────────────────────────────────────────

    @Test
    fun `performPinAssertion returns UserCancelled when pinProvider returns null`() = runTest {
        // First call to getPinRetries succeeds
        mockkConstructor(PinUvAuthProtocolV2::class)
        mockkConstructor(ClientPin::class)

        every { anyConstructed<PinUvAuthProtocolV2>().version } returns 2
        every { anyConstructed<PinUvAuthProtocolV2>().authenticate(any(), any()) } returns ByteArray(16)

        val mockRetries = mockk<ClientPin.PinRetries>()
        every { mockRetries.count } returns 5
        every { anyConstructed<ClientPin>().getPinRetries() } returns mockRetries
        every { anyConstructed<ClientPin>().getPinToken(any(), any(), any()) } returns ByteArray(32)

        val pinProvider: suspend (PinPrompt) -> CharArray? = { null }

        val result = authenticator.performPinAssertion(
            ctap = mockCtap,
            rpId = rpId,
            clientDataHash = clientDataHash,
            credentialMaps = null,
            options = mapOf("up" to true),
            pinProvider = pinProvider,
            credentialDescriptors = emptyList(),
            allowedCredentials = listOf(credentialId),
        )

        assertInstanceOf(HwKeyResult.UserCancelled::class.java, result)
    }

    // ── Test 3 ───────────────────────────────────────────────────────────────────

    @Test
    fun `performPinAssertion returns Error when pinProvider is null`() = runTest {
        val result = authenticator.performPinAssertion(
            ctap = mockCtap,
            rpId = rpId,
            clientDataHash = clientDataHash,
            credentialMaps = null,
            options = mapOf("up" to true),
            pinProvider = null,
            credentialDescriptors = emptyList(),
            allowedCredentials = listOf(credentialId),
        )

        assertInstanceOf(HwKeyResult.Error::class.java, result)
    }

    // ── Test 4 ───────────────────────────────────────────────────────────────────

    @Test
    fun `performPinAssertion returns Success when PIN is correct on first try`() = runTest {
        mockkConstructor(PinUvAuthProtocolV2::class)
        mockkConstructor(ClientPin::class)

        val pinToken = ByteArray(32) { 0x11 }
        val pinUvAuthParam = ByteArray(16) { 0x22 }

        every { anyConstructed<PinUvAuthProtocolV2>().version } returns 2
        every { anyConstructed<PinUvAuthProtocolV2>().authenticate(any(), any()) } returns pinUvAuthParam

        val mockRetries = mockk<ClientPin.PinRetries>()
        every { mockRetries.count } returns 5
        every { anyConstructed<ClientPin>().getPinRetries() } returns mockRetries
        every { anyConstructed<ClientPin>().getPinToken(any(), any(), any()) } returns pinToken

        every {
            mockCtap.getAssertions(rpId, clientDataHash, null, null, any(), pinUvAuthParam, 2, null)
        } returns listOf(mockAssertionData)

        val pinProvider: suspend (PinPrompt) -> CharArray? = { "1234".toCharArray() }

        val result = authenticator.performPinAssertion(
            ctap = mockCtap,
            rpId = rpId,
            clientDataHash = clientDataHash,
            credentialMaps = null,
            options = mapOf("up" to true),
            pinProvider = pinProvider,
            credentialDescriptors = emptyList(),
            allowedCredentials = listOf(credentialId),
        )

        assertInstanceOf(HwKeyResult.Success::class.java, result)
    }

    // ── Test 5 ───────────────────────────────────────────────────────────────────

    @Test
    fun `performPinAssertion returns PinBlocked when key responds ERR_PIN_BLOCKED`() = runTest {
        mockkConstructor(PinUvAuthProtocolV2::class)
        mockkConstructor(ClientPin::class)

        every { anyConstructed<PinUvAuthProtocolV2>().version } returns 2
        every { anyConstructed<PinUvAuthProtocolV2>().authenticate(any(), any()) } returns ByteArray(16)

        val mockRetries = mockk<ClientPin.PinRetries>()
        every { mockRetries.count } returns 1
        every { anyConstructed<ClientPin>().getPinRetries() } returns mockRetries
        // Simulate the key rejecting the PIN attempt with ERR_PIN_BLOCKED (0x32)
        every { anyConstructed<ClientPin>().getPinToken(any(), any(), any()) } throws
            CtapException(CtapException.ERR_PIN_BLOCKED)

        val pinProvider: suspend (PinPrompt) -> CharArray? = { "wrongpin".toCharArray() }

        val result = authenticator.performPinAssertion(
            ctap = mockCtap,
            rpId = rpId,
            clientDataHash = clientDataHash,
            credentialMaps = null,
            options = mapOf("up" to true),
            pinProvider = pinProvider,
            credentialDescriptors = emptyList(),
            allowedCredentials = listOf(credentialId),
        )

        assertInstanceOf(HwKeyResult.PinBlocked::class.java, result)
    }

    // ── Test 6 ───────────────────────────────────────────────────────────────────

    @Test
    fun `performPinAssertion succeeds on second attempt after wrong PIN`() = runTest {
        mockkConstructor(PinUvAuthProtocolV2::class)
        mockkConstructor(ClientPin::class)

        val pinUvAuthParam = ByteArray(16) { 0x33 }

        every { anyConstructed<PinUvAuthProtocolV2>().version } returns 2
        every { anyConstructed<PinUvAuthProtocolV2>().authenticate(any(), any()) } returns pinUvAuthParam

        val mockRetries = mockk<ClientPin.PinRetries>()
        every { mockRetries.count } returns 5
        every { anyConstructed<ClientPin>().getPinRetries() } returns mockRetries

        // First PIN attempt → ERR_PIN_INVALID, second → success
        var tokenCall = 0
        every { anyConstructed<ClientPin>().getPinToken(any(), any(), any()) } answers {
            if (tokenCall++ == 0) throw CtapException(CtapException.ERR_PIN_INVALID)
            ByteArray(32) { 0x11 }
        }

        every {
            mockCtap.getAssertions(rpId, clientDataHash, null, null, any(), pinUvAuthParam, 2, null)
        } returns listOf(mockAssertionData)

        var pinCall = 0
        val pinProvider: suspend (PinPrompt) -> CharArray? = {
            if (pinCall++ == 0) "wrong".toCharArray() else "correct".toCharArray()
        }

        val result = authenticator.performPinAssertion(
            ctap = mockCtap,
            rpId = rpId,
            clientDataHash = clientDataHash,
            credentialMaps = null,
            options = mapOf("up" to true),
            pinProvider = pinProvider,
            credentialDescriptors = emptyList(),
            allowedCredentials = listOf(credentialId),
        )

        assertInstanceOf(HwKeyResult.Success::class.java, result)
    }
}
