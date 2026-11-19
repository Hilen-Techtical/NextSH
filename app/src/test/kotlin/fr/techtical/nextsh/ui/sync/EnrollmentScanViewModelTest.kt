// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sync

import android.net.Uri
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.lock.AppLock
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.EcdhHandshake
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceRepository
import fr.techtical.nextsh.shared.domain.vault.VaultAuthExpiredException
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.testutil.RecordingTree
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import timber.log.Timber
import java.util.Base64

/**
 * What the enrollment screen is told when something goes wrong: a reason of ours, never the message
 * of an exception (the client puts the JDK text there, in English, with the address that was tried).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EnrollmentScanViewModelTest {

    private val vaultManager = mockk<VaultManager>()
    private val repository = mockk<EnrolledDeviceRepository>()
    private val client = mockk<EnrollmentClient>(relaxed = true)

    /** The real lock on a clock that never moves; it starts locked, like the app. */
    private val appLock = AppLock { 0L }

    private val serverKeys = EcdhHandshake.generateEphemeralKeypair()
    private val serverFingerprint = EcdhHandshake.fingerprint(serverKeys.publicKey)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkStatic(Uri::class)
        mockkObject(DeviceIdentity)
        every { DeviceIdentity.deviceId() } returns "android-1"
        every { DeviceIdentity.deviceName() } returns "Android"
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(DeviceIdentity)
        unmockkStatic(Uri::class)
        Dispatchers.resetMain()
    }

    /** The QR the camera reads: the parser asks the platform `Uri`, which a unit test has to fake. */
    private fun scannedQr(fingerprint: String = serverFingerprint) {
        val uri = mockk<Uri>()
        every { uri.scheme } returns "nextsh"
        every { uri.host } returns "enroll"
        every { uri.getQueryParameter("addr") } returns "$HOST:$PORT"
        every { uri.getQueryParameter("fp") } returns fingerprint
        every { Uri.parse(any()) } returns uri
    }

    private fun hello(publicKey: ByteArray = serverKeys.publicKey) =
        ServerHello(Base64.getEncoder().encodeToString(publicKey), "Bureau-PC", "tls-fingerprint")

    private fun viewModel() = EnrollmentScanViewModel(vaultManager, repository, client, appLock).also { it.retry() }

    private fun failure(reason: EnrollmentClient.Reason) =
        Result.failure<Nothing>(EnrollmentClient.EnrollmentException(reason, "HTTP 410 from $HOST:$PORT"))

    /** The view model after a scan whose first request succeeded: ready to be confirmed. */
    private fun confirmable(): EnrollmentScanViewModel {
        scannedQr()
        coEvery { client.fetchHello(HOST, PORT) } returns Result.success(hello())
        val vm = viewModel()
        vm.onQrScanned("nextsh://enroll")
        assertTrue(vm.state.value is ScannedEnrollment.FingerprintConfirm, "${vm.state.value}")
        return vm
    }

    // ── The QR and the first request ──────────────────────────────────────────

    @Test
    fun `a QR that is not an enrollment QR is told as such`() {
        every { Uri.parse(any()) } returns null
        val vm = viewModel()

        vm.onQrScanned("https://example.org")

        assertEquals(ScannedEnrollment.Error(EnrollmentError.InvalidQr), vm.state.value)
    }

    @Test
    fun `each way the first request fails has its own reason`() {
        val expected = mapOf(
            EnrollmentClient.Reason.TIMEOUT to EnrollmentError.ConnectTimeout,
            EnrollmentClient.Reason.NETWORK to EnrollmentError.Unreachable(HOST, PORT),
            EnrollmentClient.Reason.HTTP_4XX to EnrollmentError.Refused,
            EnrollmentClient.Reason.HTTP_5XX to EnrollmentError.ServerFailure,
            EnrollmentClient.Reason.INVALID_RESPONSE to EnrollmentError.InvalidResponse,
        )
        for ((reason, error) in expected) {
            scannedQr()
            coEvery { client.fetchHello(HOST, PORT) } returns failure(reason)
            val vm = viewModel()

            vm.onQrScanned("nextsh://enroll")

            assertEquals(ScannedEnrollment.Error(error), vm.state.value, "$reason")
        }
    }

    @Test
    fun `a failure the client did not name is a network error, and what it said is not kept`() {
        scannedQr()
        coEvery { client.fetchHello(HOST, PORT) } returns Result.failure(IllegalStateException("boom at 10.0.0.5"))
        val vm = viewModel()

        vm.onQrScanned("nextsh://enroll")

        assertEquals(ScannedEnrollment.Error(EnrollmentError.Network), vm.state.value)
        assertFalse("${vm.state.value}".contains("boom"))
    }

    @Test
    fun `a public key that is not Base64 is an invalid response`() {
        scannedQr()
        coEvery { client.fetchHello(HOST, PORT) } returns
            Result.success(ServerHello("not base64 !!!", "Bureau-PC", "tls-fingerprint"))
        val vm = viewModel()

        vm.onQrScanned("nextsh://enroll")

        assertEquals(ScannedEnrollment.Error(EnrollmentError.InvalidResponse), vm.state.value)
    }

    // ── The enrollment itself ─────────────────────────────────────────────────

    @Test
    fun `each way the submission fails has its own reason`() {
        val expected = mapOf(
            EnrollmentClient.Reason.TIMEOUT to EnrollmentError.SubmitTimeout,
            EnrollmentClient.Reason.NETWORK to EnrollmentError.ConnectionLost,
            EnrollmentClient.Reason.HTTP_4XX to EnrollmentError.Refused,
            EnrollmentClient.Reason.HTTP_5XX to EnrollmentError.ServerFailure,
            EnrollmentClient.Reason.INVALID_RESPONSE to EnrollmentError.InvalidResponse,
        )
        for ((reason, error) in expected) {
            val vm = confirmable()
            coEvery { client.submitEnroll(HOST, PORT, any()) } returns failure(reason)

            vm.confirmFingerprint()

            assertEquals(ScannedEnrollment.Error(error), vm.state.value, "$reason")
        }
    }

    @Test
    fun `a submission failure the client did not name is a network error, and what it said is not kept`() {
        val vm = confirmable()
        coEvery { client.submitEnroll(HOST, PORT, any()) } returns Result.failure(IllegalStateException("boom at 10.0.0.5"))

        vm.confirmFingerprint()

        assertEquals(ScannedEnrollment.Error(EnrollmentError.Network), vm.state.value)
        assertFalse("${vm.state.value}".contains("boom"))
    }

    @Test
    fun `a vault whose authentication ran out says it is locked`() {
        val vm = confirmable()
        coEvery { client.submitEnroll(HOST, PORT, any()) } returns Result.success(EnrollAck("desktop-1", "Bureau-PC"))
        coEvery { vaultManager.storePassword(any(), any()) } throws VaultAuthExpiredException()

        vm.confirmFingerprint()

        assertEquals(ScannedEnrollment.Error(EnrollmentError.VaultLocked), vm.state.value)
    }

    @Test
    fun `a vault whose authentication ran out locks the app, the lock screen covers the scanner`() {
        appLock.unlock()
        val vm = confirmable()
        coEvery { client.submitEnroll(HOST, PORT, any()) } returns Result.success(EnrollAck("desktop-1", "Bureau-PC"))
        coEvery { vaultManager.storePassword(any(), any()) } throws VaultAuthExpiredException()

        vm.confirmFingerprint()

        assertTrue(appLock.isLocked())
    }

    @Test
    fun `an enrollment that fails for another reason leaves the app unlocked`() {
        appLock.unlock()
        val vm = confirmable()
        coEvery { client.submitEnroll(HOST, PORT, any()) } returns Result.success(EnrollAck("desktop-1", "Bureau-PC"))
        coEvery { vaultManager.storePassword(any(), any()) } just Runs
        coEvery { repository.save(any()) } throws IllegalStateException("disk full")

        vm.confirmFingerprint()

        assertFalse(appLock.isLocked())
    }

    @Test
    fun `a device that cannot be saved is told as such, without what the database said`() {
        val vm = confirmable()
        coEvery { client.submitEnroll(HOST, PORT, any()) } returns Result.success(EnrollAck("desktop-1", "Bureau-PC"))
        coEvery { vaultManager.storePassword(any(), any()) } just Runs
        coEvery { repository.save(any()) } throws IllegalStateException("disk full: /data/user/0/fr.techtical.nextsh/databases/app.db")

        vm.confirmFingerprint()

        assertEquals(ScannedEnrollment.Error(EnrollmentError.NotSaved), vm.state.value)
        assertFalse("${vm.state.value}".contains("/data/"))
    }

    @Test
    fun `an enrollment that goes through is a success naming the Desktop`() {
        val vm = confirmable()
        coEvery { client.submitEnroll(HOST, PORT, any()) } returns Result.success(EnrollAck("desktop-1", "Bureau-PC"))
        coEvery { vaultManager.storePassword(any(), any()) } just Runs
        coEvery { repository.save(any()) } just Runs

        vm.confirmFingerprint()

        assertEquals(ScannedEnrollment.Success("Bureau-PC"), vm.state.value)
    }

    // ── What the log says ─────────────────────────────────────────────────────

    @Test
    fun `the log tells the failures of the client apart by their reason, without what the exception said`() {
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            for (reason in EnrollmentClient.Reason.entries) {
                scannedQr()
                coEvery { client.fetchHello(HOST, PORT) } returns failure(reason)
                viewModel().onQrScanned("nextsh://enroll")
            }
            for (reason in EnrollmentClient.Reason.entries) {
                val vm = confirmable()
                coEvery { client.submitEnroll(HOST, PORT, any()) } returns failure(reason)
                vm.confirmFingerprint()
            }
        } finally {
            Timber.uproot(tree)
        }

        val failed = tree.entries.map { it.message }.filter { it.contains("failed (") }
        for (reason in EnrollmentClient.Reason.entries) {
            assertEquals(2, failed.count { it.contains("reason=${reason.name}") }, "$reason: $failed")
        }
        failed.forEach { line ->
            assertFalse(line.contains(HOST) || line.contains("410"), "what the exception said stays out: $line")
        }
        assertTrue(tree.entries.all { it.throwable == null })
    }

    @Test
    fun `a failure that is not the one of the client is logged by its class only`() {
        assertEquals("IllegalStateException", requestFailureLog(IllegalStateException("boom at 10.0.0.5")))
        assertEquals(
            "EnrollmentException, reason=TIMEOUT",
            requestFailureLog(EnrollmentClient.EnrollmentException(EnrollmentClient.Reason.TIMEOUT, "Request timed out")),
        )
    }

    // ── What the screen shows ─────────────────────────────────────────────────

    @Test
    fun `every reason has its own sentence, and only an unreachable Desktop has arguments`() {
        val reasons = listOf(
            EnrollmentError.InvalidQr,
            EnrollmentError.InvalidResponse,
            EnrollmentError.ConnectTimeout,
            EnrollmentError.Unreachable(HOST, PORT),
            EnrollmentError.Refused,
            EnrollmentError.ServerFailure,
            EnrollmentError.SubmitTimeout,
            EnrollmentError.ConnectionLost,
            EnrollmentError.Network,
            EnrollmentError.VaultLocked,
            EnrollmentError.NotSaved,
        )

        val sentences = reasons.map { enrollmentErrorMessageRes(it) }

        assertEquals(sentences.size, sentences.toSet().size, "two reasons share a sentence: $sentences")
        reasons.forEach { reason ->
            val args = enrollmentErrorMessageArgs(reason)
            if (reason is EnrollmentError.Unreachable) {
                assertEquals(listOf<Any>(HOST, PORT), args.toList())
            } else {
                assertTrue(args.isEmpty(), "$reason")
            }
        }
        assertEquals(R.string.enrollment_error_unreachable, enrollmentErrorMessageRes(EnrollmentError.Unreachable(HOST, PORT)))
    }

    private companion object {
        const val HOST = "192.168.1.20"
        const val PORT = 47731
    }
}
