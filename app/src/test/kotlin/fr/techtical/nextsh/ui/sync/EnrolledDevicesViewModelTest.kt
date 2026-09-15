// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sync

import fr.techtical.nextsh.shared.core.sync.EnrolledDevice
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceRepository
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceSecretStore
import fr.techtical.nextsh.shared.core.sync.Platform
import fr.techtical.nextsh.shared.core.sync.SyncProtocol
import fr.techtical.nextsh.shared.core.sync.SyncResult
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class EnrolledDevicesViewModelTest {

    @MockK
    private lateinit var enrolledDeviceRepository: EnrolledDeviceRepository

    @MockK
    private lateinit var secretStore: EnrolledDeviceSecretStore

    @MockK
    private lateinit var syncProtocol: SyncProtocol

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var viewModel: EnrolledDevicesViewModel

    private val testDevice = EnrolledDevice(
        deviceId = "device-abc-123",
        deviceName = "Pixel 8 Thibaud",
        platform = Platform.ANDROID,
        publicKeyFingerprint = "fp-ecdh-test",
        tlsCertFingerprint = null,
        lastSyncAt = null,
        enrolledAt = 1_000_000L,
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { enrolledDeviceRepository.observeAll() } returns flowOf(emptyList())
        viewModel = EnrolledDevicesViewModel(
            enrolledDeviceRepository = enrolledDeviceRepository,
            secretStore = secretStore,
            syncProtocol = syncProtocol,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * revoke must call secretStore.delete before enrolledDeviceRepository.delete
     * and emit a Revoked event with the device name.
     */
    @Test
    fun `revoke deletes secret then device and emits Revoked event`() = runTest(testDispatcher) {
        coJustRun { secretStore.delete(testDevice.deviceId) }
        coJustRun { enrolledDeviceRepository.delete(testDevice.deviceId) }

        val collectedEvents = mutableListOf<EnrolledDevicesViewModel.Event>()
        // Collect events in background before calling revoke
        val collector = launch {
            viewModel.events.collect { collectedEvents.add(it) }
        }

        viewModel.revoke(testDevice)

        // With UnconfinedTestDispatcher, the revoke launch runs immediately
        coVerifyOrder {
            secretStore.delete(testDevice.deviceId)
            enrolledDeviceRepository.delete(testDevice.deviceId)
        }

        assertEquals(1, collectedEvents.size)
        assertTrue(collectedEvents[0] is EnrolledDevicesViewModel.Event.Revoked)
        assertEquals(
            testDevice.deviceName,
            (collectedEvents[0] as EnrolledDevicesViewModel.Event.Revoked).deviceName,
        )

        collector.cancel()
    }

    /**
     * revoke must emit an Error event when enrolledDeviceRepository.delete throws.
     */
    @Test
    fun `revoke emits Error event when delete fails`() = runTest(testDispatcher) {
        coJustRun { secretStore.delete(testDevice.deviceId) }
        coEvery { enrolledDeviceRepository.delete(testDevice.deviceId) } throws RuntimeException("DB error")

        val collectedEvents = mutableListOf<EnrolledDevicesViewModel.Event>()
        val collector = launch {
            viewModel.events.collect { collectedEvents.add(it) }
        }

        viewModel.revoke(testDevice)

        assertEquals(1, collectedEvents.size)
        assertTrue(collectedEvents[0] is EnrolledDevicesViewModel.Event.Error)

        collector.cancel()
    }

    // ── searchOnNetwork: manual C4 discovery trigger ────────────────────────

    @Test
    fun `searchOnNetwork emits Started then Found when fullSync succeeds`() = runTest(testDispatcher) {
        coEvery { syncProtocol.fullSync(testDevice) } returns SyncResult.Success(
            pushedCount = 0,
            pulledCount = 0,
            conflicts = emptyList(),
            syncedAt = 0L,
        )

        val collectedEvents = mutableListOf<EnrolledDevicesViewModel.Event>()
        val collector = launch {
            viewModel.events.collect { collectedEvents.add(it) }
        }

        viewModel.searchOnNetwork(testDevice)

        assertEquals(2, collectedEvents.size)
        assertTrue(collectedEvents[0] is EnrolledDevicesViewModel.Event.SearchStarted)
        assertTrue(collectedEvents[1] is EnrolledDevicesViewModel.Event.SearchFound)
        assertEquals(null, viewModel.searchingDeviceId.value)

        collector.cancel()
    }

    @Test
    fun `searchOnNetwork emits Started then NotFound when fullSync does not succeed`() = runTest(testDispatcher) {
        coEvery { syncProtocol.fullSync(testDevice) } returns SyncResult.DeviceUnreachable

        val collectedEvents = mutableListOf<EnrolledDevicesViewModel.Event>()
        val collector = launch {
            viewModel.events.collect { collectedEvents.add(it) }
        }

        viewModel.searchOnNetwork(testDevice)

        assertEquals(2, collectedEvents.size)
        assertTrue(collectedEvents[0] is EnrolledDevicesViewModel.Event.SearchStarted)
        assertTrue(collectedEvents[1] is EnrolledDevicesViewModel.Event.SearchNotFound)

        collector.cancel()
    }

    @Test
    fun `searchOnNetwork emits NotFound when fullSync throws`() = runTest(testDispatcher) {
        coEvery { syncProtocol.fullSync(testDevice) } throws RuntimeException("boom")

        val collectedEvents = mutableListOf<EnrolledDevicesViewModel.Event>()
        val collector = launch {
            viewModel.events.collect { collectedEvents.add(it) }
        }

        viewModel.searchOnNetwork(testDevice)

        assertEquals(2, collectedEvents.size)
        assertTrue(collectedEvents[1] is EnrolledDevicesViewModel.Event.SearchNotFound)
        assertEquals(null, viewModel.searchingDeviceId.value)

        collector.cancel()
    }

    @Test
    fun `searchOnNetwork ignores a second tap landing before the first coroutine starts`() = runTest {
        // A StandardTestDispatcher (not the UnconfinedTestDispatcher the other tests use) is
        // what makes this a real regression test: launch{} bodies are queued instead of running
        // eagerly, reproducing two taps that land in the same main-thread dispatch. Claiming the
        // search slot inside the coroutine (rather than atomically before launching) let both
        // taps through, firing two full syncs and two LAN discovery bursts.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        every { enrolledDeviceRepository.observeAll() } returns flowOf(emptyList())
        val vm = EnrolledDevicesViewModel(
            enrolledDeviceRepository = enrolledDeviceRepository,
            secretStore = secretStore,
            syncProtocol = syncProtocol,
        )
        coEvery { syncProtocol.fullSync(testDevice) } coAnswers {
            delay(1_000)
            SyncResult.Success(pushedCount = 0, pulledCount = 0, conflicts = emptyList(), syncedAt = 0L)
        }

        vm.searchOnNetwork(testDevice)
        vm.searchOnNetwork(testDevice)
        advanceUntilIdle()

        coVerify(exactly = 1) { syncProtocol.fullSync(testDevice) }
        assertEquals(null, vm.searchingDeviceId.value, "the slot must be released once the search ends")
    }

    @Test
    fun `searchOnNetwork can be retried once the previous search finished`() = runTest(testDispatcher) {
        coEvery { syncProtocol.fullSync(testDevice) } returns SyncResult.DeviceUnreachable

        viewModel.searchOnNetwork(testDevice)
        viewModel.searchOnNetwork(testDevice)

        // The guard is single-flight, not once-only: the slot is released in a finally block.
        coVerify(exactly = 2) { syncProtocol.fullSync(testDevice) }
    }
}
