// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.hosts

import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
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
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Unit tests for the Android [HostImportViewModel]: covers the format
 * routing / detected-format exposure / secret-default-unchecked behaviour
 * shared with the Desktop counterpart, plus the review fix in
 * [HostImportViewModel.import]: a zero-selection import used to `return`
 * without wiping [ParsedHost] secrets, leaving plaintext credentials sitting
 * in memory instead of resetting like Desktop's `reset()`.
 *
 * [HostImportViewModel.parse] hops onto the real [Dispatchers.Default]
 * (hardcoded in production code, not injectable), so `advanceUntilIdle()`
 * alone cannot deterministically wait for it: [awaitUiState] below is a
 * small bounded real-time poll used instead (mirrors the Desktop
 * counterpart's `awaitState`, which has the same constraint for real
 * `Dispatchers.IO`). [NextShImporter] / [TermiusImporter] /
 * [KeePassXcImporter] / [ImportFormatDetector] are plain objects (hard to
 * mock cleanly), the routing tests below feed real, minimal content for
 * each format rather than mocking them, per the batch instructions.
 * [HostImportService] is not constructor-injectable on this ViewModel, so
 * the "import failure" path (covered on Desktop, where the service IS
 * injectable) is not duplicated here: see the batch report.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class HostImportViewModelTest {

    @MockK
    private lateinit var hostRepository: HostRepository

    @MockK
    private lateinit var vaultManager: VaultManager

    @MockK
    private lateinit var sshKeyRepository: SshKeyRepository

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var viewModel: HostImportViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = HostImportViewModel(
            hostRepository = hostRepository,
            vaultManager = vaultManager,
            sshKeyRepository = sshKeyRepository,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun nextshContent(hostname: String, extraFields: String = ""): String =
        """{ "format": "nextsh-hosts", "hosts": [ { "hostname": "$hostname"$extraFields } ] }"""

    /** Bounded real-time poll: see the class doc for why `advanceUntilIdle()` cannot be used here. */
    private fun awaitUiState(
        timeoutMs: Long = 5_000,
        predicate: (HostImportUiState) -> Boolean,
    ): HostImportUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val current = viewModel.uiState.value
            if (predicate(current)) return current
            check(System.currentTimeMillis() <= deadline) { "Timed out waiting for expected ui state; last state=$current" }
            Thread.sleep(5)
        }
    }

    // ── Routing / detected format exposed ───────────────────────────────────

    @Test
    fun `parse routes native NextSH content to NEXTSH and exposes the detected format`() {
        viewModel.parse(nextshContent("nextsh-routing.example"))

        val state = awaitUiState { it.hasPreview || it.error != null }

        assertEquals(HostImportDetectedFormat.NEXTSH, state.detectedFormat)
        assertEquals("nextsh-routing.example", state.rows.single().hostname)
    }

    @Test
    fun `parse routes a Termius signature export to TERMIUS and exposes the detected format`() {
        val content = """
            {
              "identities": [ { "id": "i1", "username": "root", "password": "s3cr3t" } ],
              "hosts": [ { "id": "h1", "label": "web-01", "address": "termius-routing.example", "identity": "i1" } ]
            }
        """.trimIndent()

        viewModel.parse(content)

        val state = awaitUiState { it.hasPreview || it.error != null }
        assertEquals(HostImportDetectedFormat.TERMIUS, state.detectedFormat)
        assertEquals("termius-routing.example", state.rows.single().hostname)
    }

    @Test
    fun `parse routes a KeePassXC CSV export to KEEPASSXC and exposes the detected format`() {
        val content = "Group,Title,Username,Password,URL\nHome,router,admin,admin123,keepassxc-routing.example:22\n"

        viewModel.parse(content)

        val state = awaitUiState { it.hasPreview || it.error != null }
        assertEquals(HostImportDetectedFormat.KEEPASSXC, state.detectedFormat)
        assertEquals("keepassxc-routing.example", state.rows.single().hostname)
    }

    // ── Secret-bearing rows start unchecked ─────────────────────────────────

    @Test
    fun `parse unchecks rows carrying a secret and leaves the others checked`() {
        val content = """
            {
              "format": "nextsh-hosts",
              "hosts": [
                { "hostname": "secret.example", "password": "hunter2" },
                { "hostname": "plain.example" }
              ]
            }
        """.trimIndent()

        viewModel.parse(content)

        val state = awaitUiState { it.hasPreview || it.error != null }
        val secretRow = state.rows.single { it.hostname == "secret.example" }
        val plainRow = state.rows.single { it.hostname == "plain.example" }
        assertTrue(secretRow.hasSecret)
        assertFalse(secretRow.selected)
        assertFalse(plainRow.hasSecret)
        assertTrue(plainRow.selected)
    }

    // ── File too large ───────────────────────────────────────────────────────

    @Test
    fun `reportFileTooLarge surfaces the TooLarge error without touching the repos`() {
        viewModel.reportFileTooLarge()

        assertEquals(HostImportError.TooLarge, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.hasPreview)
    }

    // ── Zero selection wipes and resets (review fix) ────────────────────────

    @Test
    fun `import with nothing selected wipes parsed hosts and resets to the empty state`() {
        viewModel.parse(nextshContent("unselected.example"))
        awaitUiState { it.hasPreview }
        viewModel.setAllSelected(false)

        // Zero-selection path is synchronous (clear() + return, no coroutine
        // launch), no wait needed after calling import().
        viewModel.import()

        assertEquals(HostImportUiState(), viewModel.uiState.value)
    }
}
