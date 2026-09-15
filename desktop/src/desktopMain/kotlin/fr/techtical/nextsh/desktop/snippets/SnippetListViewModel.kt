// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.snippets

import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.Snippet
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SnippetRepository
import fr.techtical.nextsh.shared.util.randomUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SnippetListViewModel(
    private val snippetRepo: SnippetRepository = DesktopContainer.snippetRepository,
    private val hostRepo: HostRepository = DesktopContainer.hostRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    data class UiState(
        val allSnippets: List<Snippet> = emptyList(),
        val snippets: List<Snippet> = emptyList(),
        val categories: List<String> = emptyList(),
        val hosts: List<Host> = emptyList(),
        val selectedCategory: String? = null,
        val editingSnippet: Snippet? = null,
        val isEditing: Boolean = false,
        val error: String? = null,
        val successMessage: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        // Observe repo flows and merge into UiState, preserving volatile fields.
        scope.launch {
            combine(
                snippetRepo.observeAll(),
                snippetRepo.observeCategories(),
                hostRepo.observeAll(),
            ) { snippets, categories, hosts ->
                Triple(snippets, categories, hosts)
            }.collect { (snippets, categories, hosts) ->
                _uiState.update { current ->
                    val filtered = current.selectedCategory?.let { cat ->
                        snippets.filter { it.category == cat }
                    } ?: snippets
                    current.copy(
                        allSnippets = snippets,
                        snippets = filtered,
                        categories = categories,
                        hosts = hosts,
                    )
                }
            }
        }
    }

    fun filterByCategory(category: String?) {
        _uiState.update { current ->
            val filtered = category?.let { cat ->
                current.allSnippets.filter { it.category == cat }
            } ?: current.allSnippets
            current.copy(selectedCategory = category, snippets = filtered)
        }
    }

    fun startEditing(snippet: Snippet?) {
        _uiState.update { it.copy(isEditing = true, editingSnippet = snippet) }
    }

    fun cancelEditing() {
        _uiState.update { it.copy(isEditing = false, editingSnippet = null) }
    }

    fun save(label: String, command: String, category: String?, hostId: String?, existingId: String?) {
        if (label.isBlank()) {
            _uiState.update { it.copy(error = "snippets_error_label_empty") }
            return
        }
        if (command.isBlank()) {
            _uiState.update { it.copy(error = "snippets_error_command_empty") }
            return
        }
        scope.launch {
            try {
                val snippet = Snippet(
                    id = existingId ?: randomUuid(),
                    label = label.trim(),
                    command = command.trim(),
                    category = category?.trim()?.takeIf { it.isNotBlank() },
                    hostId = hostId,
                )
                if (existingId != null) snippetRepo.update(snippet) else snippetRepo.save(snippet)
                _uiState.update {
                    it.copy(isEditing = false, editingSnippet = null, successMessage = "snippets_message_saved")
                }
            } catch (t: Throwable) {
                _uiState.update { it.copy(error = t.message ?: "snippets_error_unknown") }
            }
        }
    }

    fun delete(id: String) {
        scope.launch {
            try {
                snippetRepo.delete(id)
                _uiState.update { it.copy(successMessage = "snippets_message_deleted") }
            } catch (t: Throwable) {
                _uiState.update { it.copy(error = t.message ?: "snippets_error_unknown") }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(error = null, successMessage = null) }
    }
}
