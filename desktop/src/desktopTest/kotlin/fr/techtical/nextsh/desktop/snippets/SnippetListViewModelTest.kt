// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.snippets

import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.Snippet
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SnippetRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SnippetListViewModelTest {

    private lateinit var testScope: CoroutineScope

    @AfterTest
    fun tearDown() {
        if (::testScope.isInitialized) testScope.cancel()
    }

    private fun makeSnippet(
        id: String = "s1",
        label: String = "List files",
        command: String = "ls -la",
        category: String? = null,
        hostId: String? = null,
    ) = Snippet(
        id = id,
        label = label,
        command = command,
        category = category,
        hostId = hostId,
    )

    private class FakeSnippetRepository(
        snippets: List<Snippet> = emptyList(),
    ) : SnippetRepository {
        private val _snippets = MutableStateFlow(snippets)
        private val _categories = MutableStateFlow(snippets.mapNotNull { it.category }.distinct())

        override fun observeAll(): Flow<List<Snippet>> = _snippets
        override fun observeForHost(hostId: String): Flow<List<Snippet>> = _snippets.asStateFlow()
        override fun observeCategories(): Flow<List<String>> = _categories

        fun snapshot(): List<Snippet> = _snippets.value

        override suspend fun getById(id: String): Snippet? = _snippets.value.find { it.id == id }

        override suspend fun save(snippet: Snippet) {
            _snippets.value = _snippets.value + snippet
            refreshCategories()
        }

        override suspend fun update(snippet: Snippet) {
            _snippets.value = _snippets.value.map { if (it.id == snippet.id) snippet else it }
            refreshCategories()
        }

        override suspend fun delete(id: String) {
            _snippets.value = _snippets.value.filterNot { it.id == id }
            refreshCategories()
        }

        override suspend fun hardDelete(id: String) = delete(id)
        override suspend fun getAllSyncEntries(): List<SyncEntry<Snippet>> = emptyList()
        override suspend fun upsertSyncEntry(entry: SyncEntry<Snippet>) = Unit

        private fun refreshCategories() {
            _categories.value = _snippets.value.mapNotNull { it.category }.distinct()
        }
    }

    private class FakeHostRepository : HostRepository {
        private val empty = MutableStateFlow<List<Host>>(emptyList())
        private val emptyGroups = MutableStateFlow<List<String>>(emptyList())
        override fun observeAll(): StateFlow<List<Host>> = empty.asStateFlow()
        override fun observeByGroup(group: String): StateFlow<List<Host>> = empty.asStateFlow()
        override fun observeGroups(): StateFlow<List<String>> = emptyGroups.asStateFlow()
        override fun observeFavorites(): StateFlow<List<Host>> = empty.asStateFlow()
        override suspend fun getById(id: String): Host? = null
        override suspend fun save(host: Host) = Unit
        override suspend fun update(host: Host) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun updateLastConnected(id: String) = Unit
        override suspend fun setFavorite(id: String, isFavorite: Boolean) = Unit
        override suspend fun getAllSyncEntries(): List<SyncEntry<Host>> = emptyList()
        override suspend fun upsertSyncEntry(entry: SyncEntry<Host>) = Unit
        override suspend fun hardDelete(id: String) = Unit
    }

    private fun TestEnv(
        repo: FakeSnippetRepository,
    ): SnippetListViewModel {
        testScope = CoroutineScope(UnconfinedTestDispatcher())
        return SnippetListViewModel(
            snippetRepo = repo,
            hostRepo = FakeHostRepository(),
            scope = testScope,
        )
    }

    @Test
    fun `init reflects repo observeAll`() = runTest {
        val repo = FakeSnippetRepository(listOf(makeSnippet("s1", "Test1"), makeSnippet("s2", "Test2")))
        val vm = TestEnv(repo)

        assertEquals(2, vm.uiState.value.snippets.size)
        assertEquals("Test1", vm.uiState.value.snippets[0].label)
    }

    @Test
    fun `filterByCategory keeps only matching snippets`() = runTest {
        val repo = FakeSnippetRepository(
            listOf(
                makeSnippet("s1", "Nginx conf", category = "nginx"),
                makeSnippet("s2", "Apache conf", category = "apache"),
                makeSnippet("s3", "Nginx test", category = "nginx"),
            ),
        )
        val vm = TestEnv(repo)

        vm.filterByCategory("nginx")

        val filtered = vm.uiState.value.snippets
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.category == "nginx" })
    }

    @Test
    fun `filterByCategory null restores full list`() = runTest {
        val repo = FakeSnippetRepository(
            listOf(
                makeSnippet("s1", category = "nginx"),
                makeSnippet("s2", category = "apache"),
            ),
        )
        val vm = TestEnv(repo)

        vm.filterByCategory("nginx")
        assertEquals(1, vm.uiState.value.snippets.size)

        vm.filterByCategory(null)
        assertEquals(2, vm.uiState.value.snippets.size)
    }

    @Test
    fun `save with blank label produces error and skips repo`() = runTest {
        val repo = FakeSnippetRepository()
        val vm = TestEnv(repo)

        vm.save(label = "", command = "ls", category = null, hostId = null, existingId = null)

        assertEquals("snippets_error_label_empty", vm.uiState.value.error)
        assertEquals(0, repo.snapshot().size)
    }

    @Test
    fun `save with blank command produces error and skips repo`() = runTest {
        val repo = FakeSnippetRepository()
        val vm = TestEnv(repo)

        vm.save(label = "Valid", command = "   ", category = null, hostId = null, existingId = null)

        assertEquals("snippets_error_command_empty", vm.uiState.value.error)
        assertEquals(0, repo.snapshot().size)
    }

    @Test
    fun `save valid new snippet calls repo save and exits editing`() = runTest {
        val repo = FakeSnippetRepository()
        val vm = TestEnv(repo)

        vm.save(
            label = "My command",
            command = "ls -la",
            category = "utils",
            hostId = null,
            existingId = null,
        )

        assertEquals(1, repo.snapshot().size)
        assertEquals("My command", repo.snapshot()[0].label)
        assertEquals("utils", repo.snapshot()[0].category)
        assertFalse(vm.uiState.value.isEditing)
        assertNull(vm.uiState.value.editingSnippet)
        assertEquals("snippets_message_saved", vm.uiState.value.successMessage)
    }

    @Test
    fun `save existing snippet calls repo update`() = runTest {
        val repo = FakeSnippetRepository(listOf(makeSnippet("s1", "Original", "ls")))
        val vm = TestEnv(repo)

        vm.save(
            label = "Updated",
            command = "pwd",
            category = "new-cat",
            hostId = null,
            existingId = "s1",
        )

        val updated = repo.snapshot().find { it.id == "s1" }
        assertNotNull(updated)
        assertEquals("Updated", updated.label)
        assertEquals("pwd", updated.command)
        assertEquals("new-cat", updated.category)
        assertFalse(vm.uiState.value.isEditing)
        assertEquals("snippets_message_saved", vm.uiState.value.successMessage)
    }

    @Test
    fun `delete removes from repo and surfaces success`() = runTest {
        val repo = FakeSnippetRepository(listOf(makeSnippet("s1")))
        val vm = TestEnv(repo)

        vm.delete("s1")

        assertEquals(0, repo.snapshot().size)
        assertEquals("snippets_message_deleted", vm.uiState.value.successMessage)
    }

    @Test
    fun `clearMessage resets error and successMessage`() = runTest {
        val repo = FakeSnippetRepository()
        val vm = TestEnv(repo)

        vm.save(label = "", command = "ls", category = null, hostId = null, existingId = null)
        assertNotNull(vm.uiState.value.error)

        vm.clearMessage()

        assertNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.successMessage)
    }
}
