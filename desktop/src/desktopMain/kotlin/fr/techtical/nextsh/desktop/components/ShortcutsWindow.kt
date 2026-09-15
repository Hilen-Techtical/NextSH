// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.components

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.composables.icons.lucide.Command
import com.composables.icons.lucide.Keyboard
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.SquareTerminal
import com.composables.icons.lucide.Terminal
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.action_close
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_general_close_window
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_general_nav_hosts
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_general_nav_sessions
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_general_nav_settings
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_general_nav_transfers
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_general_nav_tunnels
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_general_nav_vault
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_general_palette
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_key_escape
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_section_general
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_section_general_hint
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_section_sessions
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_section_sessions_hint
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_section_terminal
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_section_terminal_hint
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_sessions_broadcast
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_sessions_close_tab
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_sessions_new_tab
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_sessions_next_pane
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_sessions_next_tab
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_sessions_previous_pane
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_sessions_previous_tab
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_terminal_copy
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_terminal_paste
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_terminal_snippets
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_window_subtitle
import fr.techtical.nextsh.desktop.generated.resources.shortcuts_window_title
import fr.techtical.nextsh.desktop.theme.Border1
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.GoldLight
import fr.techtical.nextsh.desktop.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.desktop.theme.Radii
import fr.techtical.nextsh.desktop.window.PopupRoundedCorners
import fr.techtical.nextsh.desktop.window.WindowCaptureProtection
import fr.techtical.nextsh.desktop.theme.SpaceGroteskFamily
import fr.techtical.nextsh.desktop.theme.Spacing
import fr.techtical.nextsh.desktop.theme.SurfaceVariant
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Distance (px) parcourue par une pression flèche haut/bas sur [ShortcutsWindow]. */
private const val SHORTCUTS_ARROW_SCROLL_PX = 60f

/**
 * Fenêtre d'aide « Autres raccourcis clavier » : ouverte par la commande du
 * même nom dans la palette Ctrl+Shift+K (voir `ALL_COMMANDS` dans
 * CmdKViewModel.kt), sans aucune navigation : elle s'affiche par-dessus
 * l'écran courant et le rend tel quel à la fermeture.
 *
 * Elle remplace les trois commandes de session livrées précédemment
 * (diffusion / pane suivante / pane précédente) : celles-ci basculaient
 * l'application sur l'écran Sessions pour être utiles, ce qui téléportait
 * l'utilisateur hors de son écran. Lister les raccourcis répond au vrai
 * besoin (savoir qu'ils existent) sans effet de bord.
 *
 * Implémentation : `DialogWindow` OS séparé (`undecorated`/`transparent`)
 * comme tous les pickers du module : le terminal Desktop est rendu par un
 * heavyweight `SwingPanel` (chemin JediTerm) qui perce les overlays Compose
 * de la fenêtre principale. Le chrome est la carte partagée
 * [TechticalWindowCard], qui gère aussi ESC → fermeture.
 *
 * **Le contenu doit rester le reflet exact du code** : dispatcher AWT global
 * de `Main.kt` (raccourcis généraux et sessions) et `handleKeyEvent` /
 * gestes pointeur de `ComposeTerminalRenderer.kt` (raccourcis terminal).
 * Tout raccourci ajouté là-bas doit être ajouté ici.
 */
@Composable
fun ShortcutsWindow(onDismiss: () -> Unit) {
    val state = rememberDialogState(size = DpSize(560.dp, 620.dp))
    val title = stringResource(Res.string.shortcuts_window_title)
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    DialogWindow(
        onCloseRequest = onDismiss,
        state = state,
        title = title,
        undecorated = true,
        transparent = true,
        resizable = false,
    ) {
        PopupRoundedCorners(window, Radii.Xl)
        WindowCaptureProtection(window)
        TechticalWindowCard(
            title = title,
            icon = Lucide.Keyboard,
            onDismiss = onDismiss,
            subtitle = stringResource(Res.string.shortcuts_window_subtitle),
            footer = {
                WindowCardBtnGhostSm(onClick = onDismiss, label = stringResource(Res.string.action_close))
            },
            // Défilement clavier : cette fenêtre est une carte de référence
            // qu'on consulte souvent au clavier : flèches et Page Up/Down
            // font défiler le contenu comme le ferait la molette, sans avoir
            // à cliquer d'abord dans la zone de scroll. ESC reste prioritaire
            // et géré par TechticalWindowCard lui-même avant ce hook.
            onPreviewKeyEvent = { ev ->
                if (ev.type == KeyEventType.KeyDown) {
                    val delta = when (ev.key) {
                        Key.DirectionDown -> SHORTCUTS_ARROW_SCROLL_PX
                        Key.DirectionUp -> -SHORTCUTS_ARROW_SCROLL_PX
                        Key.PageDown -> scrollState.viewportSize.toFloat()
                        Key.PageUp -> -scrollState.viewportSize.toFloat()
                        else -> null
                    }
                    if (delta != null) {
                        coroutineScope.launch { scrollState.animateScrollBy(delta) }
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            },
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Lg),
                ) {
                    // ── Généraux : dispatcher AWT global de Main.kt, actifs
                    // depuis n'importe quel écran une fois le vault déverrouillé,
                    // SAUF Échap, géré localement par chaque fenêtre/dialogue
                    // (TechticalWindowCard, BasicTextField de CmdK…) plutôt que
                    // par ce dispatcher ; le hint de section le précise pour ne
                    // pas laisser croire que c'est un raccourci global au même
                    // titre que les autres lignes.
                    ShortcutSection(
                        icon = Lucide.Command,
                        title = stringResource(Res.string.shortcuts_section_general),
                        hint = stringResource(Res.string.shortcuts_section_general_hint),
                    ) {
                        ShortcutRow(ctrlShift("K"), stringResource(Res.string.shortcuts_general_palette))
                        ShortcutRow(ctrlShift("1"), stringResource(Res.string.shortcuts_general_nav_hosts))
                        ShortcutRow(ctrlShift("2"), stringResource(Res.string.shortcuts_general_nav_sessions))
                        ShortcutRow(ctrlShift("3"), stringResource(Res.string.shortcuts_general_nav_tunnels))
                        ShortcutRow(ctrlShift("4"), stringResource(Res.string.shortcuts_general_nav_transfers))
                        ShortcutRow(ctrlShift("5"), stringResource(Res.string.shortcuts_general_nav_vault))
                        ShortcutRow(ctrlShift(","), stringResource(Res.string.shortcuts_general_nav_settings))
                        ShortcutRow(
                            stringResource(Res.string.shortcuts_key_escape),
                            stringResource(Res.string.shortcuts_general_close_window),
                        )
                    }

                    // ── Sessions : mêmes touches, mais conditionnées à l'écran
                    // Sessions dans le dispatcher AWT (les onglets et panes
                    // n'existent que là).
                    ShortcutSection(
                        icon = Lucide.SquareTerminal,
                        title = stringResource(Res.string.shortcuts_section_sessions),
                        hint = stringResource(Res.string.shortcuts_section_sessions_hint),
                    ) {
                        ShortcutRow(ctrlShift("T"), stringResource(Res.string.shortcuts_sessions_new_tab))
                        ShortcutRow(ctrlShift("W"), stringResource(Res.string.shortcuts_sessions_close_tab))
                        ShortcutRow(ctrlOnly("Tab"), stringResource(Res.string.shortcuts_sessions_next_tab))
                        ShortcutRow(ctrlShift("Tab"), stringResource(Res.string.shortcuts_sessions_previous_tab))
                        ShortcutRow(ctrlShift("N"), stringResource(Res.string.shortcuts_sessions_next_pane))
                        ShortcutRow(ctrlShift("P"), stringResource(Res.string.shortcuts_sessions_previous_pane))
                        ShortcutRow(ctrlShift("B"), stringResource(Res.string.shortcuts_sessions_broadcast))
                    }

                    // ── Terminal : traités par le rendu terminal lui-même
                    // (handleKeyEvent / gestes pointeur), donc uniquement
                    // quand un terminal a le focus clavier.
                    ShortcutSection(
                        icon = Lucide.Terminal,
                        title = stringResource(Res.string.shortcuts_section_terminal),
                        hint = stringResource(Res.string.shortcuts_section_terminal_hint),
                    ) {
                        ShortcutRow(ctrlShift("C"), stringResource(Res.string.shortcuts_terminal_copy))
                        ShortcutRow(ctrlShift("V"), stringResource(Res.string.shortcuts_terminal_paste))
                        // Enregistré sans garde d'écran dans le dispatcher,
                        // mais l'action vise le terminal actif, classé ici.
                        ShortcutRow(ctrlShift("S"), stringResource(Res.string.shortcuts_terminal_snippets))
                        // Les gestes standards d'un terminal (Ctrl+C, molette,
                        // sélection à la souris, Page Up/Down…) ne sont pas
                        // listés : évidents à l'usage, ils noyaient les
                        // raccourcis propres à NextSH.
                    }
                }

                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(scrollState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    style = ScrollbarStyle(
                        minimalHeight = 16.dp,
                        thickness = 8.dp,
                        shape = RoundedCornerShape(4.dp),
                        hoverDurationMillis = 300,
                        unhoverColor = TextSecondary.copy(alpha = 0.25f),
                        hoverColor = TextSecondary.copy(alpha = 0.45f),
                    ),
                )
            }
        }
    }
}

// ── Libellés plateforme ──────────────────────────────────────────────────────

/**
 * Libellé d'un accord `Ctrl+Shift+<touche>`, dans la forme attendue par la
 * plateforme. Même découpage que `sidebarShortcutLabel` / `cmdKShortcutLabel`
 * dans DesktopSidebar.kt : ce sont des combos *Control* sur toutes les
 * plateformes (jamais Command), donc macOS reçoit la forme glyphe compacte
 * `⇧⌃X` et Windows/Linux la forme littérale `Ctrl+Shift+X`.
 */
private fun ctrlShift(key: String): String =
    if (isMacOs()) "⇧⌃$key" else "Ctrl+Shift+$key"

/** Idem pour un accord `Ctrl+<touche>` sans Shift. */
private fun ctrlOnly(key: String): String =
    if (isMacOs()) "⌃$key" else "Ctrl+$key"

private fun isMacOs(): Boolean = "mac" in System.getProperty("os.name", "").lowercase()

// ── Blocs de présentation ────────────────────────────────────────────────────

@Composable
private fun ShortcutSection(
    icon: ImageVector,
    title: String,
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = GoldLight, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(Spacing.Sm))
            Text(
                text = title,
                color = TextPrimary,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
        }
        if (hint != null) {
            Spacer(Modifier.height(2.dp))
            // Hint de section (méta-information sur le périmètre du bloc, pas
            // le contenu principal) : TextSecondary/11sp, un cran en dessous
            // des descriptions de raccourcis ci-dessous (TextPrimary/13sp).
            Text(text = hint, color = TextSecondary, fontSize = 11.sp)
        }
        Spacer(Modifier.height(Spacing.Sm))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border1))
        Spacer(Modifier.height(Spacing.Xs))
        content()
    }
}

/**
 * Une ligne : description à gauche (lisible en premier, c'est ce que
 * l'utilisateur cherche), accord clavier à droite dans une puce monospace.
 */
@Composable
private fun ShortcutRow(keys: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Contenu principal de la ligne (ce que l'utilisateur vient
        // chercher) donc TextPrimary/13sp, pas TextSecondary/12sp comme un
        // simple hint.
        Text(
            text = description,
            color = TextPrimary,
            fontFamily = SpaceGroteskFamily,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Spacing.Md))
        KeyChip(keys)
    }
}

@Composable
private fun KeyChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radii.Xs))
            .background(SurfaceVariant)
            .padding(horizontal = Spacing.Xs, vertical = 2.dp),
    ) {
        // Gold délibérément conservé (pas TextPrimary/TextSecondary comme le
        // reste de la carte) : c'est l'emphase voulue d'une fenêtre de
        // référence : l'accord clavier doit sauter aux yeux avant la
        // description.
        Text(
            text = text,
            color = Gold,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
        )
    }
}
