// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.snippets

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.techtical.nextsh.R
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.Snippet
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SnippetRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

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
                    _uiState.update { it.copy(successMessage = "Snippet mis à jour", isEditing = false, editingSnippet = null) }
                } else {
                    snippetRepository.save(snippet)
                    _uiState.update { it.copy(successMessage = "Snippet ajouté", isEditing = false, editingSnippet = null) }
                }
            } catch (e: Exception) {
                Timber.e(e, "Erreur sauvegarde snippet")
                _uiState.update { it.copy(error = "Erreur lors de la sauvegarde : ${e.localizedMessage}") }
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                snippetRepository.delete(id)
                _uiState.update { it.copy(successMessage = "Snippet supprimé") }
            } catch (e: Exception) {
                Timber.e(e, "Erreur suppression snippet")
                _uiState.update { it.copy(error = "Erreur lors de la suppression : ${e.localizedMessage}") }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(error = null, successMessage = null) }
    }
}
