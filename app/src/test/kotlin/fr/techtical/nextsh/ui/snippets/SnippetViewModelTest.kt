// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.snippets

import android.content.Context
import fr.techtical.nextsh.R
import fr.techtical.nextsh.testutil.StringResourceFile
import fr.techtical.nextsh.ui.SourceGuardSupport
import fr.techtical.nextsh.shared.core.backup.BackupFieldProblem
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
import org.junit.jupiter.api.Assertions.assertNotEquals
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
            every { getString(any()) } answers { "res:" + firstArg<Int>() }
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
    fun `save refuses what a restore of the backup would refuse and stores nothing`() = runTest {
        val invisible = "a" + 0x200B.toChar() + "b"
        val control = "a" + 0x1B.toChar() + "b"
        val tooLongLabel = "x".repeat(257)
        val tooLongCommand = "x".repeat(64 * 1024 + 1)
        val cases = listOf(
            Triple(invisible, "ls", null),
            Triple("ok", control, null),
            Triple("ok", "ls", invisible),
            Triple(tooLongLabel, "ls", null),
            Triple("ok", tooLongCommand, null),
            Triple("ok", "ls", "y".repeat(257)),
        )
        cases.forEach { (label, command, category) ->
            viewModel.clearMessage()
            viewModel.save(label = label, command = command, category = category, hostId = null)
            assertNotNull(viewModel.uiState.value.error)
        }

        coVerify(exactly = 0) { snippetRepository.save(any()) }
        coVerify(exactly = 0) { snippetRepository.update(any()) }
    }

    @Test
    fun `a command may hold a newline and a tab`() = runTest {
        coEvery { snippetRepository.save(any()) } returns Unit

        viewModel.save(label = "multi", command = "echo a\n\techo b", category = null, hostId = null)

        advanceUntilIdle()
        coVerify(exactly = 1) { snippetRepository.save(any()) }
    }

    @Test
    fun `field problems follow the shared rules and name the limit`() {
        assertEquals(BackupFieldProblem.EMPTY, snippetLabelFieldProblem("  "))
        assertEquals(BackupFieldProblem.EMPTY, snippetCommandFieldProblem(""))
        assertNull(snippetCategoryFieldProblem("   "))
        assertNull(snippetCategoryFieldProblem(null))
        assertEquals(BackupFieldProblem.EMPTY, themeNameFieldProblem(" "))
        assertEquals(BackupFieldProblem.TOO_LONG, themeNameFieldProblem("n".repeat(257)))
        assertEquals(BackupFieldProblem.INVISIBLE_FORMAT, themeNameFieldProblem("t" + 0x202E.toChar()))
        assertEquals(BackupFieldProblem.CONTROL_CHARACTER, snippetLabelFieldProblem("a" + 0x07.toChar()))
        assertNull(themeNameFieldProblem("  Mon theme  "))
        assertEquals(R.string.field_problem_too_long_command, backupFieldProblemRes(BackupFieldProblem.TOO_LONG, isCommand = true))
        assertEquals(R.string.field_problem_too_long, backupFieldProblemRes(BackupFieldProblem.TOO_LONG))
        // Every problem has its own sentence.
        assertEquals(4, BackupFieldProblem.entries.map { backupFieldProblemRes(it) }.toSet().size)
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
        assertEquals("res:" + R.string.snippet_save_failed, state.error)
        assertFalse(state.error!!.contains("Database error"))
    }

    @Test
    fun `delete calls repository delete and sets success message`() = runTest {
        coEvery { snippetRepository.delete("id-1") } returns Unit

        viewModel.delete("id-1")

        advanceUntilIdle()
        coVerify(exactly = 1) { snippetRepository.delete("id-1") }

        val state = viewModel.uiState.value
        assertNotNull(state.successMessage)
        assertEquals("res:" + R.string.snippet_deleted, state.successMessage)
    }

    @Test
    fun `delete with error sets error message`() = runTest {
        coEvery { snippetRepository.delete("id-1") } throws Exception("Delete failed")

        viewModel.delete("id-1")

        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertNotNull(state.error)
        assertEquals("res:" + R.string.snippet_delete_failed, state.error)
        assertFalse(state.error!!.contains("Delete failed"))
    }

    @Test
    fun `clearMessage resets error and successMessage`() = runTest {
        viewModel.clearMessage()

        val state = viewModel.uiState.value
        assertNull(state.error)
        assertNull(state.successMessage)
    }

    @Test
    fun `a failure never shows the raw message of the exception`() = runTest {
        coEvery { snippetRepository.save(any()) } throws Exception("UNIQUE constraint failed: snippets.id")
        viewModel.save(label = "Valid", command = "cmd", category = null, hostId = null)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.error!!.contains("UNIQUE"))
    }

    @Test
    fun `a full storage gets its own sentence for a save and for a delete`() {
        val full = android.database.sqlite.SQLiteFullException("database or disk is full")
        assertEquals(R.string.snippet_save_failed_storage_full, snippetFailureRes(full, isDelete = false))
        assertEquals(R.string.snippet_delete_failed_storage_full, snippetFailureRes(full, isDelete = true))
        assertEquals(R.string.snippet_save_failed, snippetFailureRes(Exception("x"), isDelete = false))
        assertEquals(R.string.snippet_delete_failed, snippetFailureRes(Exception("x"), isDelete = true))
    }

    @Test
    fun `the success messages come from the resources`() = runTest {
        coEvery { snippetRepository.save(any()) } returns Unit
        coEvery { snippetRepository.update(any()) } returns Unit
        viewModel.save(label = "A", command = "c", category = null, hostId = null)
        advanceUntilIdle()
        assertEquals("res:" + R.string.snippet_saved_added, viewModel.uiState.value.successMessage)
        viewModel.save(label = "A", command = "c", category = null, hostId = null, existingId = "id")
        advanceUntilIdle()
        assertEquals("res:" + R.string.snippet_saved_updated, viewModel.uiState.value.successMessage)
    }

    @Test
    fun `the snippet texts exist in French and English, with a plural and no dash`() {
        val names = listOf(
            "snippet_saved_added", "snippet_saved_updated", "snippet_deleted", "snippet_save_failed",
            "snippet_save_failed_storage_full", "snippet_delete_failed", "snippet_delete_failed_storage_full",
        )
        val frFile = StringResourceFile("values/strings.xml")
        val enFile = StringResourceFile("values-en/strings.xml")
        names.forEach { n ->
            val a = frFile.strings.getValue(n)
            val b = enFile.strings.getValue(n)
            assertNotEquals(a, b, n)
            listOf(a, b).forEach { t -> assertFalse(t.any { it.code == 0x2014 || it.code == 0x2013 }, n) }
        }
        listOf(frFile, enFile).forEach { file ->
            assertEquals(setOf("one", "other"), file.plurals.getValue("snippet_list_count").keys)
        }
    }

    @Test
    fun `the screen writes the count with the plural and no text of its own`() {
        val code = SourceGuardSupport
            .locate("src/main/kotlin/fr/techtical/nextsh/ui/snippets/SnippetListScreen.kt").readText()
        assertTrue(code.contains("R.plurals.snippet_list_count"))
        assertFalse(code.contains("\" snippets\""), "the count is written by hand again")
    }

    @Test
    fun `the view model holds no French text of its own`() {
        val code = SourceGuardSupport
            .locate("src/main/kotlin/fr/techtical/nextsh/ui/snippets/SnippetViewModel.kt").readText()
        assertFalse(code.contains("localizedMessage"))
        assertFalse(Regex("""successMessage = "|error = """").containsMatchIn(code))
    }
}
