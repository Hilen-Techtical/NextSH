// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.backup

import fr.techtical.nextsh.shared.core.ssh.PublicKeyDeriver
import fr.techtical.nextsh.shared.core.ssh.SshPublicKeyDerivation
import fr.techtical.nextsh.shared.core.ssh.SshPublicKeyDeriver
import fr.techtical.nextsh.shared.core.ssh.SshPublicKeyExport
import fr.techtical.nextsh.shared.core.ssh.SshPublicKeyExportRules
import fr.techtical.nextsh.shared.core.ssh.memoizedWithoutPassphrase
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `testdata/backup/v2-cles-incoherentes.json` (throwaway test keys, see its README): what [BackupKeyRules]
 * decides for each key, with the real deriver behind [memoizedWithoutPassphrase], as an importer runs it.
 * The platform tests read the same file encrypted as `.nextsh` and expect the same counters.
 */
class BackupKeyFixtureTest {

    private val validated = validOrFail(BackupCodec.decode(BackupFixtures.read("v2-cles-incoherentes.json")).decodedOrFail().document)
    private val document = validated.document
    private val rows = document.sshKeys.associateBy { it.id }

    /** `ssh-ed25519 <key A>`, taken from the first line of `key-two-lines` (the `.pub` of key A). */
    private val blobA = SshPublicKeyLine.blobKey(rows.getValue("key-two-lines").publicKey)!!

    private class Restore(val decisions: Map<String, SshKeyRestoreDecision>, val derivations: Int) {
        val publicKeysRecomputed get() = decisions.values.count { it.publicKeyRecomputed }
        val localKeysKept get() = decisions.values.count { it.localKeyKept }
        val privateKeysWritten get() = decisions.values.count { it.writePrivateKey }
    }

    /** Runs the rules over every id of the file, against [localRows] and the vault [localVault]. */
    private fun restore(localRows: Map<String, SshKey> = emptyMap(), localVault: Map<String, String> = emptyMap()): Restore {
        var derivations = 0
        val deriver = PublicKeyDeriver { pem, passphrase ->
            derivations++
            SshPublicKeyDeriver.derive(pem, passphrase)
        }.memoizedWithoutPassphrase()
        val ids = LinkedHashSet<String>().apply {
            addAll(rows.keys)
            addAll(document.privateKeys.keys)
            addAll(document.keyPassphrases.keys)
        }
        val decisions = ids.associateWith { id ->
            val incoming = rows[id]
            val local = localRows[id]
            val filePem = document.privateKeys[id]
            val state = BackupKeyRules.localPrivateKeyState(localVault[id], filePem)
            val derivation = if (filePem != null && BackupKeyRules.shouldDerive(incoming, local, true, state)) {
                deriver.derive(filePem, null) // no passphrase at restore: no key derivation function
            } else {
                null
            }
            BackupKeyRules.decide(incoming, local, filePem != null, id in document.keyPassphrases, state, derivation)
        }
        return Restore(decisions, derivations)
    }

    @Test
    fun `the file is fully valid and the public key on two lines is cleaned to one`() {
        assertEquals(0, validated.invalidTotal)
        assertEquals(listOf("key-a-line-b", "key-enc-line-b", "key-rsa", "key-two-lines", "key-kept"), document.sshKeys.map { it.id })
        assertEquals(5, document.privateKeys.size)
        assertEquals(setOf("key-enc-line-b"), document.keyPassphrases.keys)
        val twoLines = rows.getValue("key-two-lines").publicKey
        assertTrue(twoLines.none { it.isISOControl() })
        assertTrue(twoLines.startsWith("$blobA fixture-a ssh-ed25519 "))
        assertTrue(twoLines.endsWith(" fixture-b"))
    }

    @Test
    fun `on a blank device one public key is recomputed and nothing is kept`() {
        val r = restore()
        assertEquals(1, r.publicKeysRecomputed)
        assertEquals(0, r.localKeysKept)
        assertEquals(5, r.privateKeysWritten)
        // Key A is in the file twice: derived once. The encrypted key gives NeedsPassphrase without any KDF.
        assertEquals(4, r.derivations)

        val aLineB = r.decisions.getValue("key-a-line-b")
        assertEquals("$blobA fixture-b", aLineB.key?.publicKey) // key A, with the comment of the line of B
        assertTrue(aLineB.publicKeyRecomputed)

        val enc = r.decisions.getValue("key-enc-line-b")
        assertEquals(rows.getValue("key-enc-line-b").publicKey, enc.key?.publicKey) // not proven at restore
        assertFalse(enc.publicKeyRecomputed)
        assertTrue(enc.writePrivateKey)
        assertTrue(enc.writePassphrase)

        for (id in listOf("key-rsa", "key-two-lines", "key-kept")) {
            val d = r.decisions.getValue(id)
            assertSame(rows.getValue(id), d.key, id)
            assertFalse(d.publicKeyRecomputed, id)
            assertFalse(d.localKeyKept, id)
        }
        assertEquals(SshKeyType.RSA_4096, r.decisions.getValue("key-rsa").key?.keyType)
    }

    @Test
    fun `a key already on the device with another private key keeps its own key and is counted`() {
        val localLine = "$blobA fixture-a"
        val local = SshKey(id = "key-kept", label = "Local label", keyType = SshKeyType.ED25519, publicKey = localLine)
        val r = restore(
            localRows = mapOf("key-kept" to local),
            localVault = mapOf("key-kept" to document.privateKeys.getValue("key-a-line-b")), // key A, not the file's B
        )
        val kept = r.decisions.getValue("key-kept")
        assertFalse(kept.writePrivateKey)
        assertTrue(kept.localKeyKept)
        assertEquals(localLine, kept.key?.publicKey)
        assertEquals(rows.getValue("key-kept").label, kept.key?.label) // the file still renames it
        assertEquals(1, r.publicKeysRecomputed)
        assertEquals(1, r.localKeysKept)
        assertEquals(4, r.privateKeysWritten)
    }

    @Test
    fun `the encrypted key opened with its passphrase shows its line was wrong at copy time`() {
        val row = rows.getValue("key-enc-line-b")
        val derivation = SshPublicKeyDeriver.derive(document.privateKeys.getValue("key-enc-line-b"), "FIXTURE-pass".toCharArray())
        assertIs<SshPublicKeyDerivation.Derived>(derivation)
        val export = assertIs<SshPublicKeyExport.Corrected>(SshPublicKeyExportRules.resolve(row, derivation))
        assertTrue(export.hadStoredLine)
        assertTrue(export.line.endsWith(" fixture-b")) // the comment of the stored line is kept
        assertFalse(SshPublicKeyLine.blobKey(export.line) == SshPublicKeyLine.blobKey(row.publicKey))
    }

    @Test
    fun `the RSA and B keys of the file are consistent`() {
        for (id in listOf("key-rsa", "key-kept")) {
            val derived = assertIs<SshPublicKeyDerivation.Derived>(SshPublicKeyDeriver.derive(document.privateKeys.getValue(id), null), id)
            assertEquals(SshPublicKeyLine.blobKey(rows.getValue(id).publicKey), derived.blobKey, id)
        }
    }
}
