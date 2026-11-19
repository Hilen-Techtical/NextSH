// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.snippets

import android.content.Context
import android.database.sqlite.SQLiteFullException
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.techtical.nextsh.R
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.Snippet
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SnippetRepository
import fr.techtical.nextsh.shared.core.backup.BackupFieldProblem
import fr.techtical.nextsh.shared.core.backup.BackupFieldRules
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * What is wrong with the text typed for a snippet or theme field, by the rules a restore of a backup
 * applies ([BackupFieldRules], one source for both platforms). The typed text is judged as it will
 * be stored: trimmed.
 */
internal fun snippetLabelFieldProblem(label: String): BackupFieldProblem? =
    if (label.isBlank()) BackupFieldProblem.EMPTY else BackupFieldRules.snippetLabelProblem(label.trim())

internal fun snippetCommandFieldProblem(command: String): BackupFieldProblem? =
    if (command.isBlank()) BackupFieldProblem.EMPTY else BackupFieldRules.snippetCommandProblem(command.trim())

/** A blank category means "no category" and is fine. */
internal fun snippetCategoryFieldProblem(category: String?): BackupFieldProblem? =
    BackupFieldRules.snippetCategoryProblem(category?.trim()?.takeIf { it.isNotEmpty() })

internal fun themeNameFieldProblem(name: String): BackupFieldProblem? =
    BackupFieldRules.themeNameProblem(name.trim())

/**
 * The sentence under a field for [problem]. [isCommand] picks the limit quoted for TOO_LONG (64 KiB
 * for a command, 256 characters otherwise). No `else`: a new problem will not compile without a sentence.
 */
@StringRes
internal fun backupFieldProblemRes(problem: BackupFieldProblem, isCommand: Boolean = false): Int = when (problem) {
    BackupFieldProblem.EMPTY -> R.string.field_problem_empty
    BackupFieldProblem.TOO_LONG ->
        if (isCommand) R.string.field_problem_too_long_command else R.string.field_problem_too_long
    BackupFieldProblem.CONTROL_CHARACTER -> R.string.field_problem_control
    BackupFieldProblem.INVISIBLE_FORMAT -> R.string.field_problem_invisible
}

/**
 * The sentence shown when a save or a delete fails. The raw message of the exception is never shown
 * (it is technical and may name a table or a path): a full storage gets its own sentence, anything
 * else the generic one. No `else` branch on the cause: a new case needs a new sentence.
 */
@StringRes
internal fun snippetFailureRes(e: Exception, isDelete: Boolean): Int {
    val storageFull = e is SQLiteFullException
    return when {
        isDelete && storageFull -> R.string.snippet_delete_failed_storage_full
        isDelete -> R.string.snippet_delete_failed
        storageFull -> R.string.snippet_save_failed_storage_full
        else -> R.string.snippet_save_failed
    }
}

data class SnippetUiState(
    val snippets: List<Snippet> = emptyList(),
    val categories: List<String> = emptyList(),
    val selectedCategory: String? = null,
    val editingSnippet: Snippet? = null,
    val isEditing: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
    val successMessage: String? = null,
)

@HiltViewModel
class SnippetViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val snippetRepository: SnippetRepository,
    private val hostRepository: HostRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SnippetUiState())
    val uiState: StateFlow<SnippetUiState> = _uiState.asStateFlow()

    val hosts: StateFlow<List<Host>> = hostRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val allSnippets = snippetRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            allSnippets.collect { snippets ->
                val selected = _uiState.value.selectedCategory
                _uiState.update { state ->
                    state.copy(
                        snippets = if (selected == null) snippets
                                   else snippets.filter { it.category == selected },
                        isLoading = false,
                    )
                }
            }
        }

        viewModelScope.launch {
            snippetRepository.observeCategories().collect { categories ->
                _uiState.update { it.copy(categories = categories) }
            }
        }
    }

    fun filterByCategory(category: String?) {
        val all = allSnippets.value
        _uiState.update { state ->
            state.copy(
                selectedCategory = category,
                snippets = if (category == null) all else all.filter { it.category == category },
                isLoading = false,
            )
        }
    }

    fun startEditing(snippet: Snippet? = null) {
        _uiState.update { it.copy(editingSnippet = snippet, isEditing = true) }
    }

    fun cancelEditing() {
        _uiState.update { it.copy(editingSnippet = null, isEditing = false) }
    }

    fun save(
        label: String,
        command: String,
        category: String?,
        hostId: String?,
        existingId: String? = null,
    ) {
        if (label.isBlank()) {
            _uiState.update { it.copy(error = context.getString(R.string.error_snippet_label_empty)) }
            return
        }
        if (command.isBlank()) {
            _uiState.update { it.copy(error = context.getString(R.string.error_snippet_command_empty)) }
            return
        }

        // The editor already refuses these; this keeps any other caller from storing a snippet
        // that a restore of the backup would reject.
        val problem = snippetLabelFieldProblem(label)?.let { backupFieldProblemRes(it) }
            ?: snippetCommandFieldProblem(command)?.let { backupFieldProblemRes(it, isCommand = true) }
            ?: snippetCategoryFieldProblem(category)?.let { backupFieldProblemRes(it) }
        if (problem != null) {
            _uiState.update { it.copy(error = context.getString(problem)) }
            return
        }

        viewModelScope.launch {
            try {
                val snippet = Snippet(
                    id = existingId ?: java.util.UUID.randomUUID().toString(),
                    label = label.trim(),
                    command = command.trim(),
                    category = category?.trim()?.takeIf { it.isNotBlank() },
                    hostId = hostId?.takeIf { it.isNotBlank() },
                )
                if (existingId != null) {
                    snippetRepository.update(snippet)
                    _uiState.update { it.copy(successMessage = context.getString(R.string.snippet_saved_updated), isEditing = false, editingSnippet = null) }
                } else {
                    snippetRepository.save(snippet)
                    _uiState.update { it.copy(successMessage = context.getString(R.string.snippet_saved_added), isEditing = false, editingSnippet = null) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e("Snippet save failed (${errorClass(e)})")
                _uiState.update { it.copy(error = context.getString(snippetFailureRes(e, isDelete = false))) }
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                snippetRepository.delete(id)
                _uiState.update { it.copy(successMessage = context.getString(R.string.snippet_deleted)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e("Snippet deletion failed (${errorClass(e)})")
                _uiState.update { it.copy(error = context.getString(snippetFailureRes(e, isDelete = true))) }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(error = null, successMessage = null) }
    }
}
