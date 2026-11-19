// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class BiometricKeyAliasTest {

    private val keyId = "0b1f2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"

    @Test
    fun `the alias generated for a key is the prefix followed by its id`() {
        assertEquals("nextsh_bio_$keyId", BiometricKeyAlias.forKeyId(keyId))
    }

    @Test
    fun `an alias generated for a random UUID key id is valid and matches that key`() {
        repeat(50) {
            val id = UUID.randomUUID().toString()
            val alias = BiometricKeyAlias.forKeyId(id)

            assertTrue(BiometricKeyAlias.isValid(alias), alias)
            assertTrue(BiometricKeyAlias.matches(alias, id), alias)
        }
    }

    @Test
    fun `an alias does not match the id of another key`() {
        val alias = BiometricKeyAlias.forKeyId(keyId)

        assertFalse(BiometricKeyAlias.matches(alias, "11111111-2222-4333-8444-555555555555"))
    }

    @Test
    fun `aliases that are not exactly a prefix and a UUID are invalid`() {
        val invalid = listOf(
            "",
            "nextsh_vault_auth_key",
            "nextsh_bio_",
            "nextsh_bio_key-1",
            "nextsh_bio_$keyId-extra",
            "nextsh_bio_${keyId}_",
            "nextsh_bio_$keyId\n",
            " nextsh_bio_$keyId",
            "NEXTSH_BIO_$keyId",
            "nextsh_bio_${keyId.uppercase()}",
            "nextsh_bio_../$keyId",
            "other_nextsh_bio_$keyId",
            "nextsh_bio_${"a".repeat(500)}",
        )
        for (alias in invalid) {
            assertFalse(BiometricKeyAlias.isValid(alias), "[$alias] must be invalid")
            assertFalse(BiometricKeyAlias.matches(alias, alias.removePrefix(BiometricKeyAlias.PREFIX)), "[$alias] must not match")
        }
    }

    @Test
    fun `matches needs the exact alias of that very key id`() {
        val alias = BiometricKeyAlias.forKeyId(keyId)

        assertTrue(BiometricKeyAlias.matches(alias, keyId))
        assertFalse(BiometricKeyAlias.matches(alias, "$keyId "))
        assertFalse(BiometricKeyAlias.matches("$alias ", keyId))
    }

    // ── The wipe of the vault deletes the biometric keys, and nothing else ──────

    @Test
    fun `the aliases to delete in a wipe are the biometric keys of the app, never the master key or a neighbour`() {
        val ours = listOf(
            BiometricKeyAlias.forKeyId(keyId),
            BiometricKeyAlias.forKeyId("11111111-2222-4333-8444-555555555555"),
        )
        val others = listOf(
            "nextsh_vault_auth_key",
            "nextsh_bio_",
            "nextsh_bio_not-a-uuid",
            "nextsh_bio_${keyId}_extra",
            "prefix_nextsh_bio_$keyId",
            "NEXTSH_BIO_${keyId.uppercase()}",
            "",
            "some_other_alias",
        )

        val selected = BiometricKeyAlias.biometricAliasesOf(others.take(4) + ours + others.drop(4))

        assertEquals(ours, selected)
    }

    @Test
    fun `no alias, no deletion`() {
        assertEquals(emptyList<String>(), BiometricKeyAlias.biometricAliasesOf(emptyList()))
    }
}
