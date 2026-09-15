// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.themes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Trash2
import fr.techtical.nextsh.R
import fr.techtical.nextsh.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.ui.components.ConfirmDeleteDialog
import fr.techtical.nextsh.ui.theme.Border1
import fr.techtical.nextsh.ui.theme.Burgundy
import fr.techtical.nextsh.ui.theme.ErrorRed
import fr.techtical.nextsh.ui.theme.GoldMuted
import fr.techtical.nextsh.ui.theme.NearBlack
import fr.techtical.nextsh.ui.theme.Radii
import fr.techtical.nextsh.ui.theme.SpaceGroteskFamily
import fr.techtical.nextsh.ui.theme.Spacing
import fr.techtical.nextsh.ui.theme.Surface
import fr.techtical.nextsh.ui.theme.TextDisabled
import fr.techtical.nextsh.ui.theme.TextPrimary
import fr.techtical.nextsh.ui.theme.TextSecondary
import fr.techtical.nextsh.ui.theme.White
import fr.techtical.nextsh.ui.theme.toPalette

/**
 * TerminalThemesScreen : gestion globale des thèmes terminal personnalisés
 * depuis Paramètres (parité ISO avec l'écran Desktop "Gérer les thèmes").
 *
 * Structure calquée sur KnownHostsScreen : Scaffold + TopAppBar avec flèche
 * de retour, LazyColumn de cards, états vide + editor dialog + delete dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalThemesScreen(
    onBack: () -> Unit,
    viewModel: ThemesViewModel = hiltViewModel(),
) {
    val themes by viewModel.customThemes.collectAsState()

    // null = éditeur fermé ; CustomThemeEditorState.NEW = création ; sinon édition.
    var editingTheme by remember { mutableStateOf<CustomTerminalTheme?>(null) }
    // Non-null pendant qu'une confirmation de suppression est affichée.
    var deletingTheme by remember { mutableStateOf<CustomTerminalTheme?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.title_terminal_themes),
                            color = TextPrimary,
                            fontFamily = SpaceGroteskFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp,
                        )
                        Text(
                            text = when {
                                themes.isEmpty() -> stringResource(R.string.themes_empty)
                                themes.size == 1 -> stringResource(R.string.themes_subtitle_one)
                                else -> stringResource(R.string.themes_subtitle_many, themes.size)
                            },
                            color = TextDisabled,
                            fontSize = 12.sp,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = TextPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NearBlack),
            )
        },
        containerColor = NearBlack,
    ) { padding ->
        if (themes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(onNewTheme = { editingTheme = CustomThemeEditorState.NEW })
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = Spacing.Lg, vertical = Spacing.Md),
                verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                items(themes, key = { it.id }) { theme ->
                    ThemeRow(
                        theme = theme,
                        onEdit = { editingTheme = theme },
                        onDelete = { deletingTheme = theme },
                    )
                }
                item {
                    Spacer(Modifier.height(Spacing.Md))
                    Button(
                        onClick = { editingTheme = CustomThemeEditorState.NEW },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Burgundy,
                            contentColor = White,
                        ),
                        shape = RoundedCornerShape(Radii.Md),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.action_new_theme),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(Spacing.Lg))
                }
            }
        }
    }

    // ── Éditeur de thème ────────────────────────────────────────────────
    editingTheme?.let { initial ->
        CustomThemeEditorDialog(
            initial = initial,
            onSave = { theme ->
                viewModel.saveCustomTheme(theme)
                editingTheme = null
            },
            onDismiss = { editingTheme = null },
        )
    }

    // ── Confirmation suppression ────────────────────────────────────────
    deletingTheme?.let { theme ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.dialog_delete_theme_title),
            message = stringResource(R.string.dialog_delete_theme_message, theme.name),
            onConfirm = {
                viewModel.deleteCustomTheme(theme.id)
                deletingTheme = null
            },
            onDismiss = { deletingTheme = null },
        )
    }
}

// ── Ligne de thème ──────────────────────────────────────────────────────────

@Composable
private fun ThemeRow(
    theme: CustomTerminalTheme,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(Radii.Lg))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
            .padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Miniature du thème
        Box(modifier = Modifier.width(80.dp)) {
            TerminalThemePreview(palette = theme.toPalette())
        }
        Spacer(Modifier.width(Spacing.Md))
        Text(
            text = theme.name,
            color = TextPrimary,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        // Modifier (crayon)
        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(
                Lucide.Pencil,
                contentDescription = stringResource(R.string.action_edit_theme),
                tint = TextSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
        // Supprimer (poubelle)
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                Lucide.Trash2,
                contentDescription = stringResource(R.string.action_delete_theme),
                tint = ErrorRed.copy(alpha = 0.8f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// ── État vide ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(onNewTheme: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = Spacing.Xl),
    ) {
        Icon(
            Lucide.Palette,
            contentDescription = null,
            tint = GoldMuted,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(Spacing.Md))
        Text(
            text = stringResource(R.string.themes_empty),
            color = TextPrimary,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Lg))
        Button(
            onClick = onNewTheme,
            colors = ButtonDefaults.buttonColors(
                containerColor = Burgundy,
                contentColor = White,
            ),
            shape = RoundedCornerShape(Radii.Md),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.action_new_theme),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
