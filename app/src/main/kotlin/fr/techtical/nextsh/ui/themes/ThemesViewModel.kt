// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.themes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.techtical.nextsh.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.domain.repository.CustomTerminalThemeRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel de la gestion globale des thèmes terminal personnalisés
 * (depuis Settings → Thèmes du terminal).
 *
 * Miroir exact du sous-ensemble thème de [fr.techtical.nextsh.ui.hosts.HostViewModel] :
 * mêmes patterns coroutines, même repo injecté, même naming.
 */
@HiltViewModel
class ThemesViewModel @Inject constructor(
    private val customThemeRepository: CustomTerminalThemeRepository,
) : ViewModel() {

    /** Liste des thèmes terminal personnalisés, observée en continu. */
    val customThemes: StateFlow<List<CustomTerminalTheme>> =
        customThemeRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Crée ou met à jour un thème personnalisé (la palette ANSI est validée au repo). */
    fun saveCustomTheme(theme: CustomTerminalTheme) {
        viewModelScope.launch { customThemeRepository.save(theme) }
    }

    /**
     * Supprime un thème personnalisé. Les hôtes qui le référençaient retombent sur
     * le preset par défaut via [resolveThemePalette] : pas de migration nécessaire.
     */
    fun deleteCustomTheme(id: String) {
        viewModelScope.launch { customThemeRepository.delete(id) }
    }
}
