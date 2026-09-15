// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun SessionTabsScreen(
    hostId: String? = null,
    onBack: () -> Unit,
    onNavigateToSftp: (sessionId: String, hostLabel: String) -> Unit = { _, _ -> },
    viewModel: SessionViewModel = hiltViewModel(),
) {
    TerminalScreen(
        hostId           = hostId,
        onBack           = onBack,
        onNavigateToSftp = onNavigateToSftp,
        viewModel        = viewModel,
    )
}
