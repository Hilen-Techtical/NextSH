// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.vault

import fr.techtical.nextsh.shared.core.backup.BackupSettings

/**
 * The settings half of a backup: what the exporter reads and what the importer writes.
 *
 * Only the allow-list of [BackupSettings] goes through here. Security settings (clipboard clearing,
 * capture hiding, sync, window geometry) are not part of it: a backup never exports them and an import
 * never changes them. Preferences are not transactional, so [apply] runs after the database part of a
 * restore has succeeded.
 */
interface BackupSettingsAccess {

    /** The settings to write into a backup: `common` plus the `desktop` section, values inside their domain only. */
    fun snapshot(): BackupSettings

    /**
     * Applies `common` and `desktop` of [settings] (the `android` section is ignored). A value outside its
     * domain is skipped. Returns how many values were applied.
     */
    fun apply(settings: BackupSettings?): BackupSettingsApplyResult
}

/**
 * @property applied number of values written.
 * @property languageChanged true when the language was applied AND differs from the previous one: it takes
 * effect at the next start.
 */
data class BackupSettingsApplyResult(val applied: Int, val languageChanged: Boolean)
