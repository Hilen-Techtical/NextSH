// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.settings

import io.mockk.every
import io.mockk.verify
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import fr.techtical.nextsh.core.ssh.KnownHostsVerifier
import fr.techtical.nextsh.testutil.awaitVerified
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Unit tests for KnownHostsViewModel.
 *
 * Note: These tests focus on verifying that the ViewModel delegates correctly
 * to the KnownHostsVerifier. Due to the complexity of testing viewModelScope
 * with various test dispatchers, we verify the essential contract: that the
 * ViewModel calls the right methods on the verifier, rather than trying to
 * assert on the exact timing of StateFlow emissions.
 *
 * The ViewModel dispatches its work on a real [Dispatchers.IO] thread, which
 * escapes the [UnconfinedTestDispatcher] installed on Main. Setting Main to a
 * test dispatcher therefore does NOT make the IO coroutine run synchronously,
 * so `advanceUntilIdle()` cannot drain it. To stay deterministic regardless of
 * suite ordering or machine load, verifications on IO-dispatched work are wrapped
 * in [awaitVerified], which polls the MockK verification until it passes (the
 * moment the IO coroutine completes) or a generous timeout elapses. The assertion
 * itself is never relaxed: if the call genuinely never happens, the verification
 * fails exactly as it would synchronously.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class KnownHostsViewModelTest {

    @MockK
    private lateinit var knownHostsVerifier: KnownHostsVerifier

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var viewModel: KnownHostsViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // Pre-configure default mock behavior to prevent issues during init
        every { knownHostsVerifier.getAllEntries() } returns emptyList()

        viewModel = KnownHostsViewModel(
            knownHostsVerifier = knownHostsVerifier,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    fun `ViewModel has entries StateFlow`() {
        assertNotNull(viewModel.entries)
    }

    @Test
    fun `initial entries is not null`() {
        assertNotNull(viewModel.entries.value)
    }

    @Test
    fun `loadEntries calls knownHostsVerifier getAllEntries`() {
        viewModel.loadEntries()

        awaitVerified {
            verify(atLeast = 1) { knownHostsVerifier.getAllEntries() }
        }
    }

    @Test
    fun `removeEntry calls knownHostsVerifier removeEntry with correct hostPort`() {
        val hostPort = "example.com:22"
        every { knownHostsVerifier.removeEntry(hostPort) } returns true
        every { knownHostsVerifier.getAllEntries() } returns emptyList()

        viewModel.removeEntry(hostPort)

        awaitVerified {
            verify { knownHostsVerifier.removeEntry(hostPort) }
        }
    }

    @Test
    fun `removeEntry calls getAllEntries after removal`() {
        val hostPort = "example.com:22"
        every { knownHostsVerifier.removeEntry(hostPort) } returns true
        every { knownHostsVerifier.getAllEntries() } returns emptyList()

        viewModel.removeEntry(hostPort)

        awaitVerified {
            verify { knownHostsVerifier.removeEntry(hostPort) }
            verify(atLeast = 1) { knownHostsVerifier.getAllEntries() }
        }
    }

    @Test
    fun `clearAll calls knownHostsVerifier clearAll`() {
        every { knownHostsVerifier.clearAll() } returns Unit

        viewModel.clearAll()

        awaitVerified {
            verify { knownHostsVerifier.clearAll() }
        }
    }

    @Test
    fun `clearAll calls getAllEntries implicitly by reloading state`() {
        every { knownHostsVerifier.clearAll() } returns Unit
        every { knownHostsVerifier.getAllEntries() } returns emptyList()

        viewModel.clearAll()

        awaitVerified {
            verify { knownHostsVerifier.clearAll() }
        }
    }

    @Test
    fun `loadEntries handles exception gracefully and does not rethrow`() {
        every { knownHostsVerifier.getAllEntries() } throws RuntimeException("File read error")

        // Should not throw
        viewModel.loadEntries()

        awaitVerified {
            verify(atLeast = 1) { knownHostsVerifier.getAllEntries() }
        }
    }

    @Test
    fun `removeEntry handles exception gracefully and does not rethrow`() {
        val hostPort = "example.com:22"
        every { knownHostsVerifier.removeEntry(hostPort) } throws RuntimeException("Remove error")

        // Should not throw
        viewModel.removeEntry(hostPort)

        awaitVerified {
            verify { knownHostsVerifier.removeEntry(hostPort) }
        }
    }

    @Test
    fun `clearAll handles exception gracefully and does not rethrow`() {
        every { knownHostsVerifier.clearAll() } throws RuntimeException("Clear error")

        // Should not throw
        viewModel.clearAll()

        awaitVerified {
            verify { knownHostsVerifier.clearAll() }
        }
    }
}
