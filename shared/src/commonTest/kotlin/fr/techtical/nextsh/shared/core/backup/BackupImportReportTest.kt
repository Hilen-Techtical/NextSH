// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupImportReportTest {

    @Test
    fun `an empty report has no row and no warning`() {
        val report = BackupImportReport()
        assertTrue(report.rows().isEmpty())
        assertTrue(report.warnings().isEmpty())
        assertTrue(report.isEmpty)
    }

    @Test
    fun `only non zero rows are listed, in display order`() {
        val report = BackupImportReport(hosts = 3, tunnels = 2, snippets = 1, knownHostsAdded = 5, settingsApplied = 4)
        assertEquals(
            listOf(
                BackupReportRow.HOSTS to 3,
                BackupReportRow.TUNNELS to 2,
                BackupReportRow.SNIPPETS to 1,
                BackupReportRow.KNOWN_HOSTS_ADDED to 5,
                BackupReportRow.SETTINGS_APPLIED to 4,
            ),
            report.rows(),
        )
    }

    @Test
    fun `warnings follow the counters`() {
        val report = BackupImportReport(
            tunnelsSkipped = 1,
            invalidIgnored = 2,
            newerDataSkipped = true,
            languageChanged = true,
            publicKeysRecomputed = 1,
            localKeysKept = 3,
        ).withKnownHostConflicts(listOf("a:22"))
            .withHeldKnownHosts(listOf("b:22"))
            .withFailedSteps(setOf(BackupRestoreStep.KNOWN_HOSTS))
        assertEquals(
            listOf(
                BackupReportWarning.PARTIAL_RESTORE,
                BackupReportWarning.TUNNELS_SKIPPED,
                BackupReportWarning.KNOWN_HOST_CONFLICTS,
                BackupReportWarning.KNOWN_HOST_HELD,
                BackupReportWarning.PUBLIC_KEYS_RECOMPUTED,
                BackupReportWarning.LOCAL_KEYS_KEPT,
                BackupReportWarning.INVALID_IGNORED,
                BackupReportWarning.NEWER_DATA_NOT_RESTORED,
                BackupReportWarning.LANGUAGE_AFTER_RESTART,
            ),
            report.warnings(),
        )
        assertFalse(report.isEmpty)
    }

    @Test
    fun `conflicts name at most three host ports and count the others`() {
        val all = listOf("a:22", "b:22", "c:22", "d:22", "e:22")
        val report = BackupImportReport().withKnownHostConflicts(all)
        assertEquals(5, report.knownHostConflicts)
        assertEquals(listOf("a:22", "b:22", "c:22"), report.knownHostConflictNames)
        assertEquals(2, report.knownHostConflictsNotNamed)

        val few = BackupImportReport().withKnownHostConflicts(listOf("a:22", "b:22"))
        assertEquals(0, few.knownHostConflictsNotNamed)
    }

    @Test
    fun `held known hosts name at most three and count the others`() {
        val report = BackupImportReport().withHeldKnownHosts(listOf("a:1", "b:1", "c:1", "d:1"))
        assertEquals(4, report.knownHostsHeld)
        assertEquals(listOf("a:1", "b:1", "c:1"), report.knownHostHeldNames)
        assertEquals(1, report.knownHostsHeldNotNamed)
        assertEquals(listOf(BackupReportWarning.KNOWN_HOST_HELD), report.warnings())
    }

    @Test
    fun `failed steps give a partial restore warning`() {
        val ok = BackupImportReport(hosts = 1)
        assertTrue(ok.failedSteps.isEmpty())
        assertFalse(BackupReportWarning.PARTIAL_RESTORE in ok.warnings())

        val failed = ok.withFailedSteps(setOf(BackupRestoreStep.VAULT_SECRETS)).withFailedSteps(setOf(BackupRestoreStep.SETTINGS))
        assertEquals(setOf(BackupRestoreStep.VAULT_SECRETS, BackupRestoreStep.SETTINGS), failed.failedSteps)
        assertEquals(listOf(BackupReportWarning.PARTIAL_RESTORE), failed.warnings())
        assertEquals(listOf(BackupReportRow.HOSTS to 1), failed.rows())
        assertFalse(failed.isEmpty)
    }

    @Test
    fun `the key warnings follow their counters and add no row`() {
        val recomputed = BackupImportReport(sshKeys = 2, publicKeysRecomputed = 1)
        assertEquals(listOf(BackupReportWarning.PUBLIC_KEYS_RECOMPUTED), recomputed.warnings())
        assertEquals(listOf(BackupReportRow.SSH_KEYS to 2), recomputed.rows())

        val kept = BackupImportReport(localKeysKept = 2)
        assertEquals(listOf(BackupReportWarning.LOCAL_KEYS_KEPT), kept.warnings())
        assertTrue(kept.rows().isEmpty())
        assertFalse(kept.isEmpty)

        assertTrue(BackupImportReport(publicKeysRecomputed = 0, localKeysKept = 0).isEmpty)
    }

    @Test
    fun `the text form never prints a host port`() {
        val report = BackupImportReport().withKnownHostConflicts(listOf("secret-host.example.test:22"))
        assertFalse(report.toString().contains("secret-host"))
    }
}
