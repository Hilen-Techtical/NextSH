// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.snippets

import android.content.Context
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.Snippet
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SnippetRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class SnippetViewModelTest {

    @MockK
    private lateinit var snippetRepository: SnippetRepository

    @MockK
    private lateinit var hostRepository: HostRepository

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var viewModel: SnippetViewModel
    private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { snippetRepository.observeAll() } returns flowOf(emptyList())
        every { snippetRepository.observeCategories() } returns flowOf(emptyList())
        every { hostRepository.observeAll() } returns flowOf(emptyList())

        // Create a relaxed mock for context that returns default values for any method
        context = mockk(relaxed = true) {
            // Mock getString to return a non-empty string
            every { getString(any()) } returns "Error message"
        }

        viewModel = SnippetViewModel(
            context = context,
            snippetRepository = snippetRepository,
            hostRepository = hostRepository,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has empty snippets list`() = runTest {
        val state = viewModel.uiState.value

        assertEquals(emptyList<Snippet>(), state.snippets)
        assertEquals(emptyList<String>(), state.categories)
        assertNull(state.selectedCategory)
        assertFalse(state.isEditing)
        assertNull(state.editingSnippet)
        assertNull(state.error)
        assertNull(state.successMessage)
    }

    @Test
    fun `filterByCategory updates selectedCategory and filters snippets`() = runTest {
        val snippets = listOf(
            Snippet(id = "1", label = "Snippet 1", command = "cmd1", category = "shell"),
            Snippet(id = "2", label = "Snippet 2", command = "cmd2", category = "git"),
            Snippet(id = "3", label = "Snippet 3", command = "cmd3", category = "shell"),
        )

        every { snippetRepository.observeAll() } returns flowOf(snippets)

        viewModel = SnippetViewModel(
            context = context,
            snippetRepository = snippetRepository,
            hostRepository = hostRepository,
        )

        viewModel.filterByCategory("shell")

        val state = viewModel.uiState.value
        assertEquals("shell", state.selectedCategory)
        assertEquals(2, state.snippets.size)
        assertTrue(state.snippets.all { it.category == "shell" })
    }

    @Test
    fun `filterByCategory with null shows all snippets`() = runTest {
        val snippets = listOf(
            Snippet(id = "1", label = "Snippet 1", command = "cmd1", category = "shell"),
            Snippet(id = "2", label = "Snippet 2", command = "cmd2", category = "git"),
        )

        every { snippetRepository.observeAll() } returns flowOf(snippets)

        viewModel = SnippetViewModel(
            context = context,
            snippetRepository = snippetRepository,
            hostRepository = hostRepository,
        )

        viewModel.filterByCategory(null)

        val state = viewModel.uiState.value
        assertNull(state.selectedCategory)
        assertEquals(2, state.snippets.size)
    }

    @Test
    fun `startEditing sets isEditing true and editingSnippet`() = runTest {
        val snippet = Snippet(id = "1", label = "Test", command = "test cmd")

        viewModel.startEditing(snippet)

        val state = viewModel.uiState.value
        assertTrue(state.isEditing)
        assertEquals(snippet, state.editingSnippet)
    }

    @Test
    fun `startEditing without argument creates new snippet editing mode`() = runTest {
        viewModel.startEditing()

        val state = viewModel.uiState.value
        assertTrue(state.isEditing)
        assertNull(state.editingSnippet)
    }

    @Test
    fun `cancelEditing resets editing state`() = runTest {
        viewModel.startEditing(Snippet(id = "1", label = "Test", command = "cmd"))
        viewModel.cancelEditing()

        val state = viewModel.uiState.value
        assertFalse(state.isEditing)
        assertNull(state.editingSnippet)
    }

    @Test
    fun `save with blank label sets error`() = runTest {
        viewModel.save(
            label = "   ",
            command = "valid command",
            category = null,
            hostId = null,
        )

        val state = viewModel.uiState.value
        assertNotNull(state.error)
        assertTrue(state.error!!.isNotEmpty())
    }

    @Test
    fun `save with blank command sets error`() = runTest {
        viewModel.save(
            label = "Valid Label",
            command = "   ",
            category = null,
            hostId = null,
        )

        val state = viewModel.uiState.value
        assertNotNull(state.error)
        assertTrue(state.error!!.isNotEmpty())
    }

    @Test
    fun `save new snippet calls repository save and sets success message`() = runTest {
        coEvery { snippetRepository.save(any()) } returns Unit

        viewModel.save(
            label = "My Snippet",
            command = "echo hello",
            category = "shell",
            hostId = null,
        )

        advanceUntilIdle()
        coVerify(exactly = 1) { snippetRepository.save(any()) }

        val state = viewModel.uiState.value
        assertNotNull(state.successMessage)
        assertTrue(state.successMessage!!.isNotEmpty())
        assertFalse(state.isEditing)
        assertNull(state.error)
    }

    @Test
    fun `save existing snippet calls repository update and sets success message`() = runTest {
        coEvery { snippetRepository.update(any()) } returns Unit

        viewModel.save(
            label = "Updated Snippet",
            command = "updated cmd",
            category = "git",
            hostId = null,
            existingId = "existing-id",
        )

        advanceUntilIdle()
        coVerify(exactly = 1) { snippetRepository.update(any()) }

        val state = viewModel.uiState.value
        assertNotNull(state.successMessage)
        assertTrue(state.successMessage!!.isNotEmpty())
        assertFalse(state.isEditing)
        assertNull(state.error)
    }

    @Test
    fun `save trims whitespace from label and command`() = runTest {
        coEvery { snippetRepository.save(any()) } returns Unit

        viewModel.save(
            label = "  Test Label  ",
            command = "  test command  ",
            category = "  test category  ",
            hostId = null,
        )

        coVerify { snippetRepository.save(match { snippet ->
            snippet.label == "Test Label" &&
            snippet.command == "test command" &&
            snippet.category == "test category"
        }) }
    }

    @Test
    fun `save with save error sets error message`() = runTest {
        coEvery { snippetRepository.save(any()) } throws Exception("Database error")

        viewModel.save(
            label = "Valid",
            command = "valid cmd",
            category = null,
            hostId = null,
        )

        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("sauvegarde"))
    }

    @Test
    fun `delete calls repository delete and sets success message`() = runTest {
        coEvery { snippetRepository.delete("id-1") } returns Unit

        viewModel.delete("id-1")

        advanceUntilIdle()
        coVerify(exactly = 1) { snippetRepository.delete("id-1") }

        val state = viewModel.uiState.value
        assertNotNull(state.successMessage)
        assertTrue(state.successMessage!!.contains("supprimé"))
    }

    @Test
    fun `delete with error sets error message`() = runTest {
        coEvery { snippetRepository.delete("id-1") } throws Exception("Delete failed")

        viewModel.delete("id-1")

        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("suppression"))
    }

    @Test
    fun `clearMessage resets error and successMessage`() = runTest {
        viewModel.clearMessage()

        val state = viewModel.uiState.value
        assertNull(state.error)
        assertNull(state.successMessage)
    }
}
