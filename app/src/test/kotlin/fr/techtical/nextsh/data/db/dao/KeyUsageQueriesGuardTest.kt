// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.db.dao

import fr.techtical.nextsh.testutil.ProjectFiles
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source-level guard for the two read-only queries behind the warning shown before a key is
 * deleted (`HostDao.getLabelsUsingKey`, `TunnelDao.countLiveTunnelsOfHostsUsingKey`).
 *
 * No Room database runs in the unit tests (there is no `androidTest` source set), so the SQL
 * cannot be executed here: Room checks that it compiles, and this test reads it to pin the rules
 * a mocked DAO cannot see. Same compromise as [SyncUpsertGuardTest]: a source that cannot be found
 * FAILS the test (see [ProjectFiles]).
 *
 * The rules: only live hosts (a deleted host is not warned about), only hosts that point at this
 * very key and through an auth type given by the caller (never a hard-coded list that could
 * include PASSWORD: a PASSWORD host may hold a key id by mistake and does not use the key),
 * alphabetical labels, and only live tunnels under live hosts.
 */
class KeyUsageQueriesGuardTest {

    /**
     * The SQL of the `@Query` right above `suspend fun [function](`: the string literals of the
     * annotation, concatenated, with the whitespace squeezed. A source that is not found FAILS.
     */
    private fun queryOf(fileName: String, function: String): String {
        val text = ProjectFiles.mainSource("data/db/dao/$fileName").readText()
        val functionAt = text.indexOf("suspend fun $function(")
        assertTrue(functionAt >= 0, "$fileName: $function not found")
        val queryAt = text.lastIndexOf("@Query(", functionAt)
        assertTrue(queryAt >= 0, "$fileName: $function has no @Query")
        val literal = Regex(""""((?:[^"\\]|\\.)*)"""")
        return literal.findAll(text.substring(queryAt, functionAt))
            .joinToString("") { it.groupValues[1] }
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    @Test
    fun `the hosts query reads only live hosts that point at this key through the given auth types`() {
        val sql = queryOf("HostDao.kt", "getLabelsUsingKey")

        assertTrue(sql.startsWith("SELECT label FROM hosts WHERE"), sql)
        assertTrue("credentialId = :keyId" in sql, "the key itself: $sql")
        assertTrue("authType IN (:authTypes)" in sql, "the auth types come from the caller: $sql")
        assertTrue("deleted = 0" in sql, "a deleted host is not warned about: $sql")
    }

    @Test
    fun `the hosts query never hard-codes PASSWORD or any auth type, and only reads`() {
        val sql = queryOf("HostDao.kt", "getLabelsUsingKey")

        assertFalse("PASSWORD" in sql.uppercase(), "a PASSWORD host does not use the key: $sql")
        assertFalse(Regex("""'[A-Z_0-9]+'""").containsMatchIn(sql), "no hard-coded auth type: $sql")
        assertTrue(Regex("""^SELECT\b""").containsMatchIn(sql), "read only: $sql")
        assertFalse(Regex("""\b(INSERT|UPDATE|DELETE|REPLACE|DROP|ALTER)\b""", RegexOption.IGNORE_CASE).containsMatchIn(sql), sql)
    }

    @Test
    fun `the hosts come alphabetical regardless of the case, with the id to break a tie`() {
        val sql = queryOf("HostDao.kt", "getLabelsUsingKey")

        assertTrue(sql.endsWith("ORDER BY label COLLATE NOCASE ASC, id ASC"), sql)
    }

    @Test
    fun `the tunnels query counts live tunnels under live hosts that use this key`() {
        val sql = queryOf("TunnelDao.kt", "countLiveTunnelsOfHostsUsingKey")

        assertTrue(sql.startsWith("SELECT COUNT(*) FROM tunnels t INNER JOIN hosts h ON h.id = t.hostId WHERE"), sql)
        assertTrue("t.deleted = 0" in sql, "a deleted tunnel is not counted: $sql")
        assertTrue("h.deleted = 0" in sql, "a tunnel under a deleted host is an orphan, not counted: $sql")
        assertTrue("h.credentialId = :keyId" in sql, "the key itself: $sql")
        assertTrue("h.authType IN (:authTypes)" in sql, "the auth types come from the caller: $sql")
        assertFalse("PASSWORD" in sql.uppercase(), sql)
        assertFalse(Regex("""\b(INSERT|UPDATE|DELETE|REPLACE|DROP|ALTER)\b""", RegexOption.IGNORE_CASE).containsMatchIn(sql), sql)
    }

    @Test
    fun `the queries are the ones read, not a lookalike`() {
        // A guard against the helper reading the wrong block: the two SQL texts differ.
        assertNotEquals(queryOf("HostDao.kt", "getLabelsUsingKey"), queryOf("TunnelDao.kt", "countLiveTunnelsOfHostsUsingKey"))
    }
}
