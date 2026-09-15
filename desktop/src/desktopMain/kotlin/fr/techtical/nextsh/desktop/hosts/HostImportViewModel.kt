// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.hosts

import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.shared.core.`import`.HostImportService
import fr.techtical.nextsh.shared.core.`import`.ImportFormatDetector
import fr.techtical.nextsh.shared.core.`import`.ImportResult
import fr.techtical.nextsh.shared.core.`import`.KeePassXcImporter
import fr.techtical.nextsh.shared.core.`import`.NextShImporter
import fr.techtical.nextsh.shared.core.`import`.ParsedHost
import fr.techtical.nextsh.shared.core.`import`.TermiusImporter
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.AppScope
import fr.techtical.nextsh.shared.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "HostImportVM"

// ImportFormatDetector.ImportFormat is a nested enum inside an object whose
// own package segment is the reserved word `import`: a direct member import
// with the backtick-escaped segment breaks compilation (real incident, wave
// 1), so it is aliased locally instead. See HostImportService.kt for the
// same workaround.
internal typealias ImportFormat = ImportFormatDetector.ImportFormat

/**
 * A single parsed host as shown in the preview/selection dialog. Holds only
 * non-secret display fields plus a [selected] flag; the underlying [ParsedHost]
 * (which carries the secret material) is kept by the ViewModel and never
 * surfaced to the UI.
 */
data class ImportPreviewRow(
    val index: Int,
    val label: String,
    val username: String,
    val hostname: String,
    val port: Int,
    val group: String?,
    val authType: fr.techtical.nextsh.shared.domain.model.AuthType,
    val selected: Boolean,
    /** True when the source entry carries a password / private key / passphrase. */
    val hasSecret: Boolean,
)

/** State machine for the Desktop host-import flow. */
sealed interface HostImportState {
    /** Nothing in flight: the import button is idle. */
    data object Idle : HostImportState

    /** A file was picked and is being parsed (brief). */
    data object Parsing : HostImportState

    /** Parse produced [rows]; the user picks which to import. [format] is the
     * source format [ImportFormatDetector] detected, shown to the user. */
    data class Preview(val rows: List<ImportPreviewRow>, val format: ImportFormat) : HostImportState

    /** Import is running. */
    data object Importing : HostImportState

    /** Import finished: show counts. */
    data class Done(val imported: Int, val skipped: Int) : HostImportState

    /**
     * A user-facing error. [messageKey] selects the localized string; the
     * screen resolves it via `stringResource`. No raw exception text is
     * surfaced (it could echo a hostname / path).
     */
    data class Error(val messageKey: ImportErrorKind) : HostImportState
}

/** Coarse error categories mapped to localized strings by the screen. */
enum class ImportErrorKind {
    /** Zero hosts parsed / unrecognized format. */
    NoHosts,

    /** Parse threw / file unreadable. */
    Generic,

    /** The picked file exceeds [ImportFormatDetector.MAX_IMPORT_FILE_BYTES]. */
    FileTooLarge,
}

/**
 * Drives "Import hosts from NextSH / Termius / KeePassXC" on Desktop.
 *
 * Flow: [parseFile] reads the picked file off the EDT (the screen runs the
 * Swing [javax.swing.JFileChooser] inline on the EDT, then hands the [File]
 * here) → rejects it up front if it exceeds
 * [ImportFormatDetector.MAX_IMPORT_FILE_BYTES] → auto-detects NextSH's own
 * format vs Termius (JSON) vs KeePassXC (CSV/XML) via [ImportFormatDetector]
 * → emits a [HostImportState.Preview]. The user toggles rows, then
 * [confirmImport] persists the selected entries via [HostImportService] on
 * [Dispatchers.IO].
 *
 * SECURITY: the parsed [ParsedHost] list (with secrets) lives only in
 * [parsedHosts] here; the UI sees only [ImportPreviewRow] (non-secret fields).
 * Secrets are wiped by [HostImportService] after storage, and any un-imported
 * entries are wiped on [reset].
 */
class HostImportViewModel(
    private val hostRepository: HostRepository = DesktopContainer.hostRepository,
    private val vaultManager: VaultManager = DesktopContainer.vaultManager,
    private val sshKeyRepository: SshKeyRepository = DesktopContainer.sshKeyRepository,
    private val appScope: AppScope = DesktopContainer.appScope,
    private val importService: HostImportService = HostImportService(),
) {
    private val _state = MutableStateFlow<HostImportState>(HostImportState.Idle)
    val state: StateFlow<HostImportState> = _state.asStateFlow()

    /** Parsed entries indexed by [ImportPreviewRow.index]; carries secrets. */
    private var parsedHosts: List<ParsedHost> = emptyList()

    /**
     * Reads + parses [file], auto-detecting the source format from its content
     * via [ImportFormatDetector.detect]. Rejects the file up front (before
     * reading it into memory) when it exceeds
     * [ImportFormatDetector.MAX_IMPORT_FILE_BYTES].
     */
    fun parseFile(file: File) {
        // Re-entrance guard: a flow already in progress (Parsing / Preview /
        // Importing / an unacknowledged Done or Error) must not be clobbered
        // by a second parseFile call. Without this, picking a second file
        // while an import is running reassigns [parsedHosts] to the new
        // preview's entries, so when the in-flight import coroutine later
        // wipes "the entries the user didn't select", it wipes secrets
        // belonging to the NEW preview instead of the one it was actually
        // importing (confirmed repro). The screen already disables the
        // Import/info buttons while busy (defence in depth), but this is the
        // authoritative guard.
        if (_state.value !is HostImportState.Idle) return
        _state.value = HostImportState.Parsing
        appScope.coroutineScope.launch {
            try {
                val tooLarge = withContext(Dispatchers.IO) {
                    ImportFormatDetector.isFileTooLarge(file.length())
                }
                if (tooLarge) {
                    _state.value = HostImportState.Error(ImportErrorKind.FileTooLarge)
                    return@launch
                }
                val content = withContext(Dispatchers.IO) { file.readText(Charsets.UTF_8) }
                val format = withContext(Dispatchers.Default) { ImportFormatDetector.detect(content) }
                val hosts = withContext(Dispatchers.Default) { parseContent(content, format) }
                if (hosts.isEmpty()) {
                    _state.value = HostImportState.Error(ImportErrorKind.NoHosts)
                    return@launch
                }
                parsedHosts = hosts
                _state.value = HostImportState.Preview(rows = hosts.toPreviewRows(), format = format)
            } catch (e: Exception) {
                Logger.w(TAG, "Host import parse failed (${e.message})")
                _state.value = HostImportState.Error(ImportErrorKind.Generic)
            }
        }
    }

    /** Toggles the selection of the preview row at [index]. */
    fun toggleSelection(index: Int) {
        val current = _state.value
        if (current !is HostImportState.Preview) return
        _state.value = current.copy(
            rows = current.rows.map { if (it.index == index) it.copy(selected = !it.selected) else it },
        )
    }

    /** Selects (or deselects) every preview row. */
    fun setAllSelected(selected: Boolean) {
        val current = _state.value
        if (current !is HostImportState.Preview) return
        _state.value = current.copy(rows = current.rows.map { it.copy(selected = selected) })
    }

    /** Imports the currently-selected preview rows. */
    fun confirmImport() {
        val current = _state.value
        if (current !is HostImportState.Preview) return
        val selectedIndices = current.rows.filter { it.selected }.map { it.index }.toSet()
        val toImport = parsedHosts.filterIndexed { i, _ -> i in selectedIndices }
        if (toImport.isEmpty()) {
            // Nothing selected: wipe and treat as a no-op cancel.
            reset()
            return
        }
        _state.value = HostImportState.Importing
        appScope.coroutineScope.launch {
            try {
                val result: ImportResult = withContext(Dispatchers.IO) {
                    importService.import(
                        entries = toImport,
                        hostRepository = hostRepository,
                        vaultManager = vaultManager,
                        sshKeyRepository = sshKeyRepository,
                    )
                }
                // Wipe any entries the user chose NOT to import (the imported ones are
                // already wiped by the service).
                parsedHosts.filterIndexed { i, _ -> i !in selectedIndices }.forEach { it.wipeSecrets() }
                parsedHosts = emptyList()
                _state.value = HostImportState.Done(imported = result.imported, skipped = result.skipped)
            } catch (e: Exception) {
                // Parity with Android's import(): on any unexpected failure,
                // wipe every retained secret (not just the unselected ones:
                // we don't know how far importService got) rather than leaving
                // plaintext credentials sitting in [parsedHosts] indefinitely.
                Logger.w(TAG, "Host import failed (${e::class.simpleName})")
                parsedHosts.forEach { it.wipeSecrets() }
                parsedHosts = emptyList()
                _state.value = HostImportState.Error(ImportErrorKind.Generic)
            }
        }
    }

    /** Clears the flow back to idle, wiping any retained secret material. */
    fun reset() {
        parsedHosts.forEach { it.wipeSecrets() }
        parsedHosts = emptyList()
        _state.value = HostImportState.Idle
    }

    private fun parseContent(content: String, format: ImportFormat): List<ParsedHost> =
        when (format) {
            ImportFormat.NEXTSH -> NextShImporter.parse(content)
            ImportFormat.TERMIUS -> TermiusImporter.parse(content)
            ImportFormat.KEEPASSXC -> KeePassXcImporter.parse(content)
        }

    /**
     * Maps parsed entries to display rows. Entries carrying a secret
     * (password / private key / passphrase) start **unchecked**: the user
     * opts back in explicitly, since importing them means the plaintext
     * secret sat in an on-disk export file.
     */
    private fun List<ParsedHost>.toPreviewRows(): List<ImportPreviewRow> =
        mapIndexed { index, host ->
            ImportPreviewRow(
                index = index,
                label = host.label,
                username = host.username,
                hostname = host.hostname,
                port = host.port,
                group = host.group,
                authType = host.authType,
                selected = !host.hasSecret,
                hasSecret = host.hasSecret,
            )
        }
}
