// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.repository

import fr.techtical.nextsh.data.db.dao.SnippetDao
import fr.techtical.nextsh.data.db.entity.SnippetEntity
import fr.techtical.nextsh.domain.model.Snippet
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class SnippetRepositoryImplTest {

    @MockK
    private lateinit var snippetDao: SnippetDao

    private lateinit var repository: SnippetRepositoryImpl

    @BeforeEach
    fun setUp() {
        repository = SnippetRepositoryImpl(snippetDao)
    }

    @Test
    fun `observeAll maps entities to domain snippets`() = runTest {
        val entities = listOf(
            SnippetEntity(
                id = "1",
                label = "Snippet 1",
                command = "cmd1",
                category = "shell",
                hostId = null,
                createdAt = 1704067200000L,
            ),
            SnippetEntity(
                id = "2",
                label = "Snippet 2",
                command = "cmd2",
                category = null,
                hostId = "host-1",
                createdAt = 1704153600000L,
            ),
        )

        every { snippetDao.observeAll() } returns flowOf(entities)

        val result = repository.observeAll().toList()

        assertEquals(1, result.size)
        assertEquals(2, result[0].size)
        assertEquals("1", result[0][0].id)
        assertEquals("Snippet 1", result[0][0].label)
        assertEquals("shell", result[0][0].category)
        assertEquals("host-1", result[0][1].hostId)
    }

    @Test
    fun `observeAll with empty list returns empty flow`() = runTest {
        every { snippetDao.observeAll() } returns flowOf(emptyList())

        val result = repository.observeAll().toList()

        assertEquals(1, result.size)
        assertEquals(0, result[0].size)
    }

    @Test
    fun `observeForHost maps entities to domain snippets`() = runTest {
        val hostId = "host-123"
        val entities = listOf(
            SnippetEntity(
                id = "1",
                label = "Host Snippet",
                command = "host-cmd",
                category = "specific",
                hostId = hostId,
                createdAt = 1704067200000L,
            ),
        )

        every { snippetDao.observeForHost(hostId) } returns flowOf(entities)

        val result = repository.observeForHost(hostId).toList()

        assertEquals(1, result.size)
        assertEquals(1, result[0].size)
        assertEquals(hostId, result[0][0].hostId)
    }

    @Test
    fun `observeCategories returns distinct categories`() = runTest {
        val categories = listOf("shell", "git", "docker")

        every { snippetDao.observeCategories() } returns flowOf(categories)

        val result = repository.observeCategories().toList()

        assertEquals(1, result.size)
        assertEquals(3, result[0].size)
        assertEquals(listOf("shell", "git", "docker"), result[0])
    }

    @Test
    fun `getById returns mapped snippet when found`() = runTest {
        val entity = SnippetEntity(
            id = "found-id",
            label = "Found",
            command = "found-cmd",
            category = "test",
            hostId = "host-x",
            createdAt = 1704067200000L,
        )

        coEvery { snippetDao.getById("found-id") } returns entity

        val result = repository.getById("found-id")

        assertEquals("found-id", result?.id)
        assertEquals("Found", result?.label)
        assertEquals("found-cmd", result?.command)
        assertEquals("test", result?.category)
        assertEquals("host-x", result?.hostId)
    }

    @Test
    fun `getById returns null when not found`() = runTest {
        coEvery { snippetDao.getById("not-found") } returns null

        val result = repository.getById("not-found")

        assertNull(result)
    }

    @Test
    fun `save converts domain to entity and calls dao insert`() = runTest {
        val snippet = Snippet(
            id = "new-id",
            label = "New Snippet",
            command = "new-cmd",
            category = "new-cat",
            hostId = null,
            createdAt = 1704067200000L,
        )

        coEvery { snippetDao.insert(any()) } returns Unit

        repository.save(snippet)

        coVerify(exactly = 1) {
            snippetDao.insert(match { entity ->
                entity.id == "new-id" &&
                entity.label == "New Snippet" &&
                entity.command == "new-cmd" &&
                entity.category == "new-cat" &&
                entity.hostId == null
            })
        }
    }

    @Test
    fun `update converts domain to entity and calls dao update`() = runTest {
        val snippet = Snippet(
            id = "update-id",
            label = "Updated",
            command = "updated-cmd",
            category = null,
            hostId = "host-update",
            createdAt = 1704153600000L,
        )

        coEvery { snippetDao.getById("update-id") } returns null
        coEvery { snippetDao.update(any()) } returns Unit

        repository.update(snippet)

        coVerify(exactly = 1) {
            snippetDao.update(match { entity ->
                entity.id == "update-id" &&
                entity.label == "Updated" &&
                entity.command == "updated-cmd" &&
                entity.category == null &&
                entity.hostId == "host-update"
            })
        }
    }

    @Test
    fun `delete soft-deletes the snippet via dao`() = runTest {
        coEvery { snippetDao.getById("delete-id") } returns null
        coEvery { snippetDao.softDelete(any(), any(), any(), any()) } returns Unit

        repository.delete("delete-id")

        coVerify(exactly = 1) { snippetDao.softDelete("delete-id", any(), any(), any()) }
    }
}
