// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.db.entity

import fr.techtical.nextsh.domain.model.Snippet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SnippetEntityTest {

    @Test
    fun `toDomain converts entity to snippet correctly`() {
        val entity = SnippetEntity(
            id = "snippet-1",
            label = "Test Label",
            command = "test command",
            category = "shell",
            hostId = "host-123",
            createdAt = 1704067200000L,
        )

        val snippet = entity.toDomain()

        assertEquals("snippet-1", snippet.id)
        assertEquals("Test Label", snippet.label)
        assertEquals("test command", snippet.command)
        assertEquals("shell", snippet.category)
        assertEquals("host-123", snippet.hostId)
        assertEquals(1704067200000L, snippet.createdAt)
    }

    @Test
    fun `toDomain preserves null category`() {
        val entity = SnippetEntity(
            id = "snippet-1",
            label = "Test",
            command = "cmd",
            category = null,
            hostId = "host-123",
            createdAt = 1704067200000L,
        )

        val snippet = entity.toDomain()

        assertNull(snippet.category)
    }

    @Test
    fun `toDomain preserves null hostId`() {
        val entity = SnippetEntity(
            id = "snippet-1",
            label = "Test",
            command = "cmd",
            category = "shell",
            hostId = null,
            createdAt = 1704067200000L,
        )

        val snippet = entity.toDomain()

        assertNull(snippet.hostId)
    }

    @Test
    fun `fromDomain converts snippet to entity correctly`() {
        val snippet = Snippet(
            id = "snippet-2",
            label = "My Snippet",
            command = "echo test",
            category = "bash",
            hostId = "host-456",
            createdAt = 1704153600000L,
        )

        val entity = SnippetEntity.fromDomain(snippet)

        assertEquals("snippet-2", entity.id)
        assertEquals("My Snippet", entity.label)
        assertEquals("echo test", entity.command)
        assertEquals("bash", entity.category)
        assertEquals("host-456", entity.hostId)
        assertEquals(1704153600000L, entity.createdAt)
    }

    @Test
    fun `fromDomain preserves null category`() {
        val snippet = Snippet(
            id = "snippet-1",
            label = "Test",
            command = "cmd",
            category = null,
            hostId = null,
            createdAt = 1704067200000L,
        )

        val entity = SnippetEntity.fromDomain(snippet)

        assertNull(entity.category)
    }

    @Test
    fun `fromDomain preserves null hostId`() {
        val snippet = Snippet(
            id = "snippet-1",
            label = "Test",
            command = "cmd",
            category = "git",
            hostId = null,
            createdAt = 1704067200000L,
        )

        val entity = SnippetEntity.fromDomain(snippet)

        assertNull(entity.hostId)
    }

    @Test
    fun `round trip preserves all fields - full data`() {
        val originalSnippet = Snippet(
            id = "snippet-3",
            label = "Git Snippet",
            command = "git status && git diff",
            category = "version-control",
            hostId = "prod-server",
            createdAt = 1704240000000L,
        )

        val entity = SnippetEntity.fromDomain(originalSnippet)
        val restoredSnippet = entity.toDomain()

        assertEquals(originalSnippet, restoredSnippet)
    }

    @Test
    fun `round trip preserves all fields - minimal data`() {
        val originalSnippet = Snippet(
            id = "snippet-minimal",
            label = "Minimal",
            command = "ls",
            category = null,
            hostId = null,
            createdAt = 1704326400000L,
        )

        val entity = SnippetEntity.fromDomain(originalSnippet)
        val restoredSnippet = entity.toDomain()

        assertEquals(originalSnippet, restoredSnippet)
    }

    @Test
    fun `round trip preserves all fields - only hostId without category`() {
        val originalSnippet = Snippet(
            id = "snippet-host-only",
            label = "Host Specific",
            command = "whoami",
            category = null,
            hostId = "dev-server",
            createdAt = 1704412800000L,
        )

        val entity = SnippetEntity.fromDomain(originalSnippet)
        val restoredSnippet = entity.toDomain()

        assertEquals(originalSnippet, restoredSnippet)
    }

    @Test
    fun `round trip preserves all fields - only category without hostId`() {
        val originalSnippet = Snippet(
            id = "snippet-cat-only",
            label = "Category Only",
            command = "pwd",
            category = "navigation",
            hostId = null,
            createdAt = 1704499200000L,
        )

        val entity = SnippetEntity.fromDomain(originalSnippet)
        val restoredSnippet = entity.toDomain()

        assertEquals(originalSnippet, restoredSnippet)
    }
}
