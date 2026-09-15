// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sftp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Embedded SFTP browser pour les panes split-screen.
 *
 * Le ViewModel est isolé via une `key` scopée à [sessionId] pour que
 * chaque instance embedded ait son propre [SftpBrowserViewModel],
 * indépendant de la route SFTP fullscreen et des autres embedded.
 *
 * @param enableBackHandler vrai quand ce panneau a le focus. Le retour parcourt
 *   alors la même chaîne qu'en plein écran : sortie de sélection, puis remontée
 *   d'un répertoire, puis [onBack]. Faux pour un panneau non focalisé, dont le
 *   retour doit rester au niveau du split.
 * @param onBack appelé quand la chaîne du navigateur est épuisée, c'est-à-dire
 *   à la racine et hors sélection. Ferme le split.
 */
@Composable
fun EmbeddedSftpBrowser(
    sessionId: String,
    hostLabel: String,
    modifier: Modifier = Modifier,
    enableBackHandler: Boolean = false,
    onBack: () -> Unit = {},
    viewModel: SftpBrowserViewModel = hiltViewModel(key = "sftp_embedded_$sessionId"),
) {
    SftpBrowserScreen(
        sessionId         = sessionId,
        hostLabel         = hostLabel,
        onBack            = onBack,
        modifier          = modifier,
        enableBackHandler = enableBackHandler,
        viewModel         = viewModel,
    )
}
