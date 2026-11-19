// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import android.content.res.Resources
import fr.techtical.nextsh.R
import fr.techtical.nextsh.shared.core.backup.BackupImportReport
import fr.techtical.nextsh.shared.core.backup.BackupReportRow
import fr.techtical.nextsh.shared.core.backup.BackupReportWarning
import fr.techtical.nextsh.shared.core.backup.BackupRestoreStep
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The sentences of the import summary: which resource, which arguments, in which order. */
class ImportSummaryTextTest {

    private val resources = mockk<Resources>()

    @Test
    fun `a row is the label and the count through the shared format`() {
        every { resources.getString(R.string.vault_import_row_hosts) } returns "Hosts"
        every { resources.getString(R.string.vault_import_row_format, "Hosts", 4) } returns "Hosts: 4"

        assertEquals("Hosts: 4", importRowText(resources, BackupReportRow.HOSTS, 4))
    }

    @Test
    fun `tunnels skipped uses the existing plural with the count`() {
        every { resources.getQuantityString(R.plurals.vault_import_skipped_tunnels, 2, 2) } returns "2 skipped"

        val text = importWarningText(resources, BackupImportReport(tunnelsSkipped = 2), BackupReportWarning.TUNNELS_SKIPPED)

        assertEquals("2 skipped", text)
    }

    @Test
    fun `conflicts name the servers and count the others`() {
        val report = BackupImportReport().withKnownHostConflicts(listOf("a:22", "b:22", "c:2222", "d:22", "e:22"))
        every { resources.getQuantityString(R.plurals.vault_import_warning_more, 2, 2) } returns "and 2 others"
        every {
            resources.getQuantityString(
                R.plurals.vault_import_warning_known_host_conflicts,
                5,
                5,
                "a:22, b:22, c:2222 and 2 others",
            )
        } returns "conflict sentence"

        val text = importWarningText(resources, report, BackupReportWarning.KNOWN_HOST_CONFLICTS)

        assertEquals("conflict sentence", text)
    }

    @Test
    fun `conflicts that are all named add no and others`() {
        val report = BackupImportReport().withKnownHostConflicts(listOf("a:22", "b:22"))
        every {
            resources.getQuantityString(R.plurals.vault_import_warning_known_host_conflicts, 2, 2, "a:22, b:22")
        } returns "two conflicts"

        assertEquals("two conflicts", importWarningText(resources, report, BackupReportWarning.KNOWN_HOST_CONFLICTS))
    }

    @Test
    fun `held fingerprints name the servers like the conflicts do`() {
        val report = BackupImportReport(
            knownHostsHeld = 4,
            knownHostHeldNames = listOf("a:22", "b:22", "c:22"),
        )
        every { resources.getQuantityString(R.plurals.vault_import_warning_more, 1, 1) } returns "and 1 other"
        every {
            resources.getQuantityString(
                R.plurals.vault_import_warning_known_host_held,
                4,
                4,
                "a:22, b:22, c:22 and 1 other",
            )
        } returns "held sentence"

        assertEquals("held sentence", importWarningText(resources, report, BackupReportWarning.KNOWN_HOST_HELD))
    }

    @Test
    fun `recomputed public keys and kept local keys have their own plurals with the count`() {
        every { resources.getQuantityString(R.plurals.vault_import_warning_public_keys_recomputed, 2, 2) } returns "2 recomputed"
        every { resources.getQuantityString(R.plurals.vault_import_warning_local_keys_kept, 3, 3) } returns "3 kept"
        val report = BackupImportReport(publicKeysRecomputed = 2, localKeysKept = 3)

        assertEquals("2 recomputed", importWarningText(resources, report, BackupReportWarning.PUBLIC_KEYS_RECOMPUTED))
        assertEquals("3 kept", importWarningText(resources, report, BackupReportWarning.LOCAL_KEYS_KEPT))
        assertEquals(
            listOf(BackupReportWarning.PUBLIC_KEYS_RECOMPUTED, BackupReportWarning.LOCAL_KEYS_KEPT),
            report.warnings(),
        )
    }

    @Test
    fun `a partial restore lists the failed steps in a fixed order`() {
        every { resources.getString(R.string.vault_import_step_secrets) } returns "secrets"
        every { resources.getString(R.string.vault_import_step_known_hosts) } returns "known hosts"
        every { resources.getString(R.string.vault_import_step_settings) } returns "settings"
        every { resources.getString(R.string.vault_import_warning_partial, "secrets, settings") } returns "partial"
        val report = BackupImportReport(failedSteps = setOf(BackupRestoreStep.SETTINGS, BackupRestoreStep.VAULT_SECRETS))

        assertEquals("partial", importWarningText(resources, report, BackupReportWarning.PARTIAL_RESTORE))
    }

    @Test
    fun `invalid entries and newer data have their own sentences`() {
        every { resources.getQuantityString(R.plurals.vault_import_warning_invalid, 3, 3) } returns "3 invalid"
        every { resources.getString(R.string.vault_import_warning_newer_data) } returns "newer data"
        every { resources.getString(R.string.vault_import_warning_language) } returns "language"
        val report = BackupImportReport(invalidIgnored = 3, newerDataSkipped = true, languageChanged = true)

        assertEquals(
            listOf("3 invalid", "newer data", "language"),
            report.warnings().filter { it != BackupReportWarning.TUNNELS_SKIPPED }
                .map { importWarningText(resources, report, it) },
        )
    }
}
