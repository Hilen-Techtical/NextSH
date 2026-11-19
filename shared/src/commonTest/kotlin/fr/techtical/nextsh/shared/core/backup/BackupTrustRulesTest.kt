// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.backup

import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupTrustRulesTest {

    private fun host(id: String, hostname: String, port: Int = 22) = Host(
        id = id,
        label = "label-$id",
        hostname = hostname,
        port = port,
        username = "u",
        authType = AuthType.PASSWORD,
        credentialId = "c-$id",
    )

    @Test
    fun `the key format is hostname colon port, verbatim, with the default port`() {
        assertEquals("a.example.test:22", BackupTrustRules.hostPortKey("a.example.test", 22))
        assertEquals("::1:2222", BackupTrustRules.hostPortKey("::1", 2222))
        assertEquals("[::1]:22", BackupTrustRules.hostPortKey("[::1]", 22))
    }

    @Test
    fun `a host new on the device is not held`() {
        assertTrue(BackupTrustRules.heldDestinations(emptyList(), listOf(host("9", "x.example.test"))).isEmpty())
        assertTrue(BackupTrustRules.heldDestinations(listOf(host("1", "a")), listOf(host("2", "b"))).isEmpty())
    }

    @Test
    fun `an existing host with the same address is held`() {
        val local = listOf(host("1", "a.example.test"))
        assertEquals(
            setOf("a.example.test:22"),
            BackupTrustRules.heldDestinations(local, listOf(host("1", "a.example.test"))),
        )
    }

    @Test
    fun `an existing host moved by the file is held at its new address of the file`() {
        val local = listOf(host("1", "old.example.test"), host("3", "p.example.test", 22))
        val incoming = listOf(host("1", "new.example.test"), host("3", "p.example.test", 2222))
        assertEquals(
            listOf("new.example.test:22", "p.example.test:2222"),
            BackupTrustRules.heldDestinations(local, incoming).toList(),
        )
    }

    @Test
    fun `a deleted local host counts as present because the caller passes it`() {
        // The caller passes alive hosts and tombstones in the same list: the rule only looks at ids.
        val tombstone = host("7", "gone.example.test")
        assertEquals(
            setOf("back.example.test:22"),
            BackupTrustRules.heldDestinations(listOf(tombstone), listOf(host("7", "back.example.test"))),
        )
    }

    @Test
    fun `order follows the file and duplicates are merged`() {
        val local = listOf(host("1", "a"), host("2", "b"))
        val incoming = listOf(host("2", "b"), host("1", "a"), host("2", "b"), host("5", "new"), host("1", "a", 22))
        assertEquals(listOf("b:22", "a:22"), BackupTrustRules.heldDestinations(local, incoming).toList())
    }

    @Test
    fun `mixed file keeps only the existing hosts`() {
        val local = listOf(host("1", "a"))
        val incoming = listOf(host("1", "a"), host("2", "fresh"))
        assertEquals(setOf("a:22"), BackupTrustRules.heldDestinations(local, incoming))
    }
}
