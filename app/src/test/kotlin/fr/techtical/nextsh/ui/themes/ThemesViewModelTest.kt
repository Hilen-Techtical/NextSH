// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.themes

import fr.techtical.nextsh.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.domain.repository.CustomTerminalThemeRepository
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class ThemesViewModelTest {

    @MockK
    private lateinit var customThemeRepository: CustomTerminalThemeRepository

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var viewModel: ThemesViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { customThemeRepository.observeAll() } returns MutableStateFlow(emptyList())
        viewModel = ThemesViewModel(customThemeRepository = customThemeRepository)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun makeTheme(id: String, name: String = "Thème $id") = CustomTerminalTheme(
        id = id,
        name = name,
        background = 0xFF1E1E1E.toInt(),
        foreground = 0xFFD4D4D4.toInt(),
        cursor = 0xFFAEAFAD.toInt(),
        selectionBg = 0x44264F78,
        ansi = List(16) { 0xFF000000.toInt() },
    )

    // ── customThemes flow ────────────────────────────────────────────────────

    @Test
    fun `customThemes emits empty list on init`() = runTest {
        val result = viewModel.customThemes.first()
        assertTrue(result.isEmpty(), "La liste de thèmes doit être vide au départ")
    }

    @Test
    fun `customThemes reflects repository emissions`() = runTest {
        val flow = MutableStateFlow<List<CustomTerminalTheme>>(emptyList())
        every { customThemeRepository.observeAll() } returns flow
        viewModel = ThemesViewModel(customThemeRepository = customThemeRepository)
        // WhileSubscribed : un collecteur actif est requis pour que stateIn
        // s'abonne au flow du repository.
        viewModel.customThemes.launchIn(backgroundScope)

        val theme = makeTheme("t1")
        flow.value = listOf(theme)

        advanceUntilIdle()
        val result = viewModel.customThemes.value
        assertTrue(result.size == 1 && result[0].id == "t1", "Le flow doit refléter les émissions du repository")
    }

    // ── saveCustomTheme ──────────────────────────────────────────────────────

    @Test
    fun `saveCustomTheme delegates to repository save`() = runTest {
        val theme = makeTheme("t1")
        coJustRun { customThemeRepository.save(theme) }

        viewModel.saveCustomTheme(theme)

        advanceUntilIdle()
        coVerify(exactly = 1) { customThemeRepository.save(theme) }
    }

    // ── deleteCustomTheme ────────────────────────────────────────────────────

    @Test
    fun `deleteCustomTheme delegates to repository delete`() = runTest {
        coJustRun { customThemeRepository.delete("t1") }

        viewModel.deleteCustomTheme("t1")

        advanceUntilIdle()
        coVerify(exactly = 1) { customThemeRepository.delete("t1") }
    }
}
