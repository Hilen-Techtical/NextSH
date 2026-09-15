// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.core.`import`.HostImportService
import fr.techtical.nextsh.shared.core.`import`.ImportFormatDetector
import fr.techtical.nextsh.shared.core.`import`.ImportResult
import fr.techtical.nextsh.shared.core.`import`.KeePassXcImporter
import fr.techtical.nextsh.shared.core.`import`.NextShImporter
import fr.techtical.nextsh.shared.core.`import`.ParsedHost
import fr.techtical.nextsh.shared.core.`import`.TermiusImporter
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Local alias for the shared detector's nested enum. Needed because the
 * detector lives in the `fr.techtical.nextsh.shared.core.import` package
 * (`import` is a reserved word, hence the backticked segment): importing a
 * *nested* member through a backticked package segment
 * (`fr.techtical.nextsh.shared.core.`import`.ImportFormatDetector.ImportFormat`)
 * breaks compilation, so the enum is referenced qualified off the object
 * import instead and given a short local name here.
 */
private typealias ImportFormat = ImportFormatDetector.ImportFormat

/**
 * A single parsed host as shown in the Android preview/selection screen. Holds
 * only non-secret display fields plus a [selected] flag; the secret-bearing
 * [ParsedHost] is kept by the ViewModel and never surfaced to the UI.
 *
 * [hasSecret] mirrors [ParsedHost.hasSecret]: it is a boolean flag, never the
 * secret material itself, so it is safe to surface to the preview UI.
 */
data class HostImportPreviewRow(
    val index: Int,
    val label: String,
    val username: String,
    val hostname: String,
    val port: Int,
    val group: String?,
    val authType: AuthType,
    val hasSecret: Boolean,
    val selected: Boolean,
)

/** Which importer parsed the currently-previewed file, shown in the preview header. */
enum class HostImportDetectedFormat { NEXTSH, TERMIUS, KEEPASSXC }

/** Coarse, localizable error categories for the host-import flow. */
enum class HostImportError {
    /** Zero hosts parsed / unrecognized format. */
    NoHosts,

    /** Parse threw / file unreadable. */
    Generic,

    /** File size (declared or observed while bound-reading) exceeds the import cap. */
    TooLarge,
}

/**
 * UI state for the Android "Import hosts (NextSH / Termius / KeePassXC)" screen.
 *
 * SECURITY: only [rows] (non-secret fields) is observable by the UI. The
 * parsed [ParsedHost] entries (with passwords / PEMs / passphrases) live in
 * the ViewModel's private field and are wiped after import.
 */
data class HostImportUiState(
    val isParsing: Boolean = false,
    val isImporting: Boolean = false,
    val rows: List<HostImportPreviewRow> = emptyList(),
    val detectedFormat: HostImportDetectedFormat? = null,
    val result: ImportResult? = null,
    val error: HostImportError? = null,
) {
    val hasPreview: Boolean get() = rows.isNotEmpty()
    val selectedCount: Int get() = rows.count { it.selected }
    val hasSecretEntries: Boolean get() = rows.any { it.hasSecret }
}

/**
 * Hilt ViewModel backing the Android host-import flow. The screen reads a SAF
 * document into a [String] and hands it to [parse]; this auto-detects Termius
 * (JSON) vs KeePassXC (CSV/XML), then exposes a non-secret preview. [import]
 * persists the selected entries through the shared [HostImportService] using
 * the Hilt-injected repos and the (adapter-backed) shared [VaultManager].
 */
@HiltViewModel
class HostImportViewModel @Inject constructor(
    private val hostRepository: HostRepository,
    private val vaultManager: VaultManager,
    private val sshKeyRepository: SshKeyRepository,
) : ViewModel() {

    private val importService = HostImportService()

    private val _uiState = MutableStateFlow(HostImportUiState())
    val uiState: StateFlow<HostImportUiState> = _uiState.asStateFlow()

    /** Parsed entries indexed by [HostImportPreviewRow.index]; carries secrets. */
    private var parsedHosts: List<ParsedHost> = emptyList()

    /**
     * Parses [content], auto-detecting the source format via
     * [ImportFormatDetector.detect] and routing to the matching importer
     * ([NextShImporter] / [TermiusImporter] / [KeePassXcImporter]).
     */
    fun parse(content: String) {
        _uiState.value = HostImportUiState(isParsing = true)
        viewModelScope.launch {
            try {
                val parsed = withContext(Dispatchers.Default) { parseContent(content) }
                if (parsed.hosts.isEmpty()) {
                    _uiState.value = HostImportUiState(error = HostImportError.NoHosts)
                    return@launch
                }
                parsedHosts = parsed.hosts
                _uiState.value = HostImportUiState(
                    rows = parsed.hosts.toPreviewRows(),
                    detectedFormat = parsed.format.toUiFormat(),
                )
            } catch (e: Exception) {
                Timber.w("Host import parse failed: ${e.message}")
                _uiState.value = HostImportUiState(error = HostImportError.Generic)
            }
        }
    }

    /** Reports an unreadable file from the screen (SAF read threw). */
    fun reportReadError() {
        _uiState.value = HostImportUiState(error = HostImportError.Generic)
    }

    /**
     * Reports a file whose declared (or, for an undeclared size, bound-read)
     * byte length exceeds [ImportFormatDetector.MAX_IMPORT_FILE_BYTES]. The
     * screen performs the size check before ever calling [parse]: this
     * content never reaches the ViewModel.
     */
    fun reportFileTooLarge() {
        _uiState.value = HostImportUiState(error = HostImportError.TooLarge)
    }

    /** Toggles the selection of the preview row at [index]. */
    fun toggleSelection(index: Int) {
        val state = _uiState.value
        if (!state.hasPreview) return
        _uiState.value = state.copy(
            rows = state.rows.map { if (it.index == index) it.copy(selected = !it.selected) else it },
        )
    }

    /** Selects (or deselects) every preview row. */
    fun setAllSelected(selected: Boolean) {
        val state = _uiState.value
        if (!state.hasPreview) return
        _uiState.value = state.copy(rows = state.rows.map { it.copy(selected = selected) })
    }

    /** Imports the currently-selected preview rows. */
    fun import() {
        val state = _uiState.value
        if (!state.hasPreview) return
        val selectedIndices = state.rows.filter { it.selected }.map { it.index }.toSet()
        val toImport = parsedHosts.filterIndexed { i, _ -> i in selectedIndices }
        if (toImport.isEmpty()) {
            // Nothing selected: wipe and treat as a no-op cancel (parity with
            // Desktop's reset()). A bare `return` here left every parsed
            // secret sitting in memory indefinitely instead of being cleared.
            clear()
            return
        }

        _uiState.value = state.copy(isImporting = true)
        viewModelScope.launch {
            try {
                val result: ImportResult = withContext(Dispatchers.IO) {
                    importService.import(
                        entries = toImport,
                        hostRepository = hostRepository,
                        vaultManager = vaultManager,
                        sshKeyRepository = sshKeyRepository,
                    )
                }
                // Wipe entries the user chose NOT to import (imported ones are
                // wiped by the service).
                parsedHosts.filterIndexed { i, _ -> i !in selectedIndices }.forEach { it.wipeSecrets() }
                parsedHosts = emptyList()
                _uiState.value = HostImportUiState(result = result)
            } catch (e: Exception) {
                Timber.w("Host import failed: ${e.message}")
                parsedHosts.forEach { it.wipeSecrets() }
                parsedHosts = emptyList()
                _uiState.value = HostImportUiState(error = HostImportError.Generic)
            }
        }
    }

    /** Clears state back to empty, wiping any retained secret material. */
    fun clear() {
        parsedHosts.forEach { it.wipeSecrets() }
        parsedHosts = emptyList()
        _uiState.value = HostImportUiState()
    }

    override fun onCleared() {
        super.onCleared()
        parsedHosts.forEach { it.wipeSecrets() }
        parsedHosts = emptyList()
    }

    /** Format + parsed hosts, bundled so [parse] can surface the detected format in the UI state. */
    private data class ParsedContent(val format: ImportFormat, val hosts: List<ParsedHost>)

    private fun parseContent(content: String): ParsedContent {
        val format = ImportFormatDetector.detect(content)
        val hosts = when (format) {
            ImportFormat.NEXTSH -> NextShImporter.parse(content)
            ImportFormat.TERMIUS -> TermiusImporter.parse(content)
            ImportFormat.KEEPASSXC -> KeePassXcImporter.parse(content)
        }
        return ParsedContent(format, hosts)
    }

    private fun ImportFormat.toUiFormat(): HostImportDetectedFormat = when (this) {
        ImportFormat.NEXTSH -> HostImportDetectedFormat.NEXTSH
        ImportFormat.TERMIUS -> HostImportDetectedFormat.TERMIUS
        ImportFormat.KEEPASSXC -> HostImportDetectedFormat.KEEPASSXC
    }

    /**
     * Rows carrying a secret ([ParsedHost.hasSecret]) start **unchecked** - the
     * user must opt in per-entry to importing plaintext secret material from
     * the source file into the vault. Every other row keeps the previous
     * "selected by default" behaviour.
     */
    private fun List<ParsedHost>.toPreviewRows(): List<HostImportPreviewRow> =
        mapIndexed { index, host ->
            HostImportPreviewRow(
                index = index,
                label = host.label,
                username = host.username,
                hostname = host.hostname,
                port = host.port,
                group = host.group,
                authType = host.authType,
                hasSecret = host.hasSecret,
                selected = !host.hasSecret,
            )
        }
}
