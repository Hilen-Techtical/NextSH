// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.composables.icons.lucide.ArrowLeftRight
import com.composables.icons.lucide.CornerDownLeft
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Keyboard
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Settings
import fr.techtical.nextsh.desktop.theme.Border1
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.GoldLight
import fr.techtical.nextsh.desktop.theme.GoldMuted
import fr.techtical.nextsh.desktop.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.desktop.theme.Radii
import fr.techtical.nextsh.desktop.window.PopupRoundedCorners
import fr.techtical.nextsh.desktop.window.WindowCaptureProtection
import fr.techtical.nextsh.desktop.window.WindowsToolWindow
import fr.techtical.nextsh.desktop.theme.Spacing
import fr.techtical.nextsh.desktop.theme.SpaceGroteskFamily
import fr.techtical.nextsh.desktop.theme.Surface
import fr.techtical.nextsh.desktop.theme.SurfaceVariant
import fr.techtical.nextsh.desktop.theme.TextDisabled
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.cmdk_empty
import fr.techtical.nextsh.desktop.generated.resources.cmdk_enter_hint
import fr.techtical.nextsh.desktop.generated.resources.cmdk_search_placeholder
import fr.techtical.nextsh.desktop.generated.resources.cmdk_section_commands
import fr.techtical.nextsh.desktop.generated.resources.cmdk_section_hosts
import org.jetbrains.compose.resources.stringResource

/**
 * Palette de commandes Cmd+K (Phase 1.4, polish).
 *
 * Implémentée via [Window] non-modal (fenêtre OS séparée, `undecorated =
 * true`, `transparent = true`) : PAS `DialogWindow`, qui est automatiquement
 * modale par rapport à sa fenêtre owner sur Compose Desktop et bloquerait
 * tous les clics sur la fenêtre principale (cf. commentaire sur
 * `Window(...)` ci-dessous). Une fenêtre OS séparée reste de toute façon la
 * seule approche fiable dans ce module : le widget JediTerm est rendu via un
 * heavyweight `SwingPanel` qui perce les overlays Compose dans la même
 * fenêtre (Popup, Dialog material3).
 *
 * Le panneau est ancré sur la fenêtre principale NextSH, centré
 * horizontalement, à 1/4 de sa hauteur depuis le haut (convention Spotlight
 * / VS Code Quick Open), et borné à l'écran qui porte réellement cette
 * fenêtre (voir [computeCmdKPosition] / [usableBoundsOf]), pas à l'écran
 * primaire : sur un setup multi-écrans, la fenêtre principale peut être sur
 * un moniteur secondaire (coordonnées négatives, taille différente), et
 * l'ancienne implémentation basée sur `GraphicsEnvironment.maximumWindowBounds`
 * (primaire uniquement) plaçait alors la palette n'importe où. La fermeture
 * sur perte de focus émule un click-outside : dès que l'utilisateur clique
 * ailleurs (autre fenêtre, app principale derrière), la palette se ferme.
 */
@Composable
fun CmdKOverlay(
    viewModel: CmdKViewModel,
    parentWindow: ComposeWindow,
    onDismiss: () -> Unit,
) {
    val query by viewModel.query.collectAsState()
    val filteredItems by viewModel.filteredItems.collectAsState()
    val selectedIndex by viewModel.selectedIndex.collectAsState()
    val selectionSource by viewModel.selectionSource.collectAsState()

    // Position ancrée sur la fenêtre principale (pas sur l'écran primaire)
    // et bornée à l'écran qui la porte réellement : voir
    // [computeCmdKPosition] / [usableBoundsOf] plus bas dans ce fichier.
    val (dialogX, dialogY) = remember(parentWindow) {
        val point = computeCmdKPosition(parentWindow.bounds, usableBoundsOf(parentWindow))
        point.x to point.y
    }
    val windowState = rememberWindowState(
        position = WindowPosition(dialogX.dp, dialogY.dp),
        size = DpSize(CMDK_WIDTH_DP.dp, CMDK_HEIGHT_DP.dp),
    )

    // `Window` (non-modal) au lieu de `DialogWindow` : DialogWindow est
    // automatiquement modale par rapport à sa fenêtre owner sur Compose
    // Desktop, ce qui bloque tous les clics sur la main window. Avec
    // `Window` standalone, les clics passent derrière, le main window
    // peut regagner le focus, et le `windowGainedFocus` ci-dessous fait
    // dismiss.
    //
    // Pas de `alwaysOnTop = true` : ça maintenait la palette au-dessus
    // de TOUTES les apps même après Alt-Tab. Sans le flag, la palette
    // se cache naturellement derrière une autre app au moment du switch,
    // et le focus listener ferme la palette de toute façon dès que la
    // main window regagne le focus.
    Window(
        onCloseRequest = onDismiss,
        state = windowState,
        title = "",
        undecorated = true,
        transparent = true,
        resizable = false,
        focusable = true,
    ) {
        // Coins : la fenêtre AWT est clippée au même rayon que la carte,
        // le renderer OPENGL forcé (Main.kt) ne supporte pas la transparence
        // per-pixel sous Windows, l'extérieur du clip Compose rendait des
        // pointes noires aux quatre coins. Voir PopupWindowStyle.kt.
        PopupRoundedCorners(window, Radii.Xl)
        // Capture : la palette liste les labels et adresses d'hôtes, elle doit
        // suivre le réglage « Cacher de la capture d'écran » comme la fenêtre
        // principale. HWND distinct = flag à poser séparément.
        WindowCaptureProtection(window)
        // Taskbar : `setType(UTILITY)` lève passé ce point (fenêtre déjà
        // displayable), et le cycle setVisible(false/true) de l'ancien
        // workaround cassait le rendu. Le style étendu WS_EX_TOOLWINDOW se
        // pose lui sur un HWND vivant sans cycle de visibilité.
        LaunchedEffect(Unit) { WindowsToolWindow.apply(window) }
        //
        // Click-outside : on n'écoute PAS les événements de la dialog elle-même.
        // Compose Desktop instancie ce DialogWindow comme dialog owned par la
        // fenêtre principale ; sur owner+dialog AWT/Swing partage l'état focus,
        // donc `windowLostFocus` / `windowDeactivated` côté dialog ne se
        // déclenchent pas de façon fiable quand l'utilisateur clique sur la main
        // window. L'AWTEventListener global ne reçoit pas non plus les clics qui
        // transitent par les heavyweight ComposePanel/SwingPanel sous Windows.
        //
        // Approche fiable : on attache les listeners au PARENT (la main window).
        // Quand la dialog s'ouvre, le parent perd le focus (c'est la dialog qui
        // l'a). Dès que l'utilisateur clique sur le parent (sidebar, terminal
        // JediTerm, n'importe où dans la fenêtre principale), le parent regagne
        // le focus AWT, et `windowGainedFocus` se déclenche. C'est un signal
        // bien défini par AWT et fiable pour les dialogues owned. On ajoute
        // `windowActivated` en filet de sécurité (certains WMs distinguent
        // activation et focus).
        //
        // Bootstrap delay : la dialog peut se réaliser après le tick courant ;
        // on ignore tout signal pendant 150 ms pour éviter un dismiss immédiat.
        val coroutineScope = rememberCoroutineScope()
        DisposableEffect(parentWindow) {
            var canDismiss = false
            val focusListener = object : WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent?) {
                    if (canDismiss) onDismiss()
                }
                override fun windowLostFocus(e: WindowEvent?) = Unit
            }
            val activationListener = object : WindowAdapter() {
                override fun windowActivated(e: WindowEvent?) {
                    if (canDismiss) onDismiss()
                }
            }
            parentWindow.addWindowFocusListener(focusListener)
            parentWindow.addWindowListener(activationListener)
            val bootstrapJob = coroutineScope.launch {
                delay(150)
                canDismiss = true
            }
            onDispose {
                bootstrapJob.cancel()
                parentWindow.removeWindowFocusListener(focusListener)
                parentWindow.removeWindowListener(activationListener)
            }
        }

        CmdKPanel(
            query = query,
            filteredItems = filteredItems,
            selectedIndex = selectedIndex,
            selectionSource = selectionSource,
            onQueryChange = viewModel::onQueryChange,
            onMoveUp = viewModel::onMoveUp,
            onMoveDown = viewModel::onMoveDown,
            onHoverIndex = viewModel::setSelectedIndex,
            onExecuteSelected = { viewModel.executeSelected(onDismiss) },
            onExecuteIndex = { idx -> viewModel.execute(idx, onDismiss) },
            onDismiss = onDismiss,
        )
    }
}

// ── Position multi-écrans ────────────────────────────────────────────────────

/** Dimensions de la palette : mêmes valeurs que le `DpSize` de [rememberWindowState] ci-dessus. */
internal const val CMDK_WIDTH_DP = 640
internal const val CMDK_HEIGHT_DP = 480

/**
 * Calcul de position pur : ancre la palette sur [anchor] (bounds de la
 * fenêtre principale NextSH) et la borne aux limites de [screen] (bounds
 * utilisables, insets exclus, de l'écran qui porte réellement cette
 * fenêtre : voir [usableBoundsOf]).
 *
 * Centrée horizontalement sur [anchor], placée à 1/4 de sa hauteur depuis
 * le haut (convention Spotlight / VS Code Quick Open). [anchor] et [screen]
 * sont dans le même espace de coordonnées écran AWT que
 * `ComposeWindow.bounds` / `GraphicsConfiguration.bounds` : le résultat
 * peut donc être réinjecté tel quel dans [WindowPosition] sans conversion
 * de densité.
 *
 * Le clamp est ce qui corrige le bug multi-écrans : l'ancienne
 * implémentation centrait sur `GraphicsEnvironment.maximumWindowBounds`,
 * qui ne renvoie jamais que l'écran PRIMAIRE (confirmé jusque dans le
 * bytecode CMP 1.7.3) : une fenêtre principale sur un écran secondaire
 * (y compris à coordonnées négatives, à gauche du primaire) plaçait la
 * palette n'importe où, potentiellement hors de tout écran.
 */
internal fun computeCmdKPosition(anchor: Rectangle, screen: Rectangle): Point {
    val rawX = anchor.x + (anchor.width - CMDK_WIDTH_DP) / 2
    val rawY = anchor.y + anchor.height / 4

    val minX = screen.x
    val maxX = (screen.x + screen.width - CMDK_WIDTH_DP).coerceAtLeast(minX)
    val minY = screen.y
    val maxY = (screen.y + screen.height - CMDK_HEIGHT_DP).coerceAtLeast(minY)

    return Point(rawX.coerceIn(minX, maxX), rawY.coerceIn(minY, maxY))
}

/**
 * Bounds utilisables (insets taskbar/dock exclus) de l'écran qui porte
 * [window], en suivant le pattern interne `align()` de Compose Desktop.
 * `GraphicsConfiguration.bounds` donne le rectangle plein du moniteur dans
 * l'espace écran AWT avec la bonne origine par-écran (contrairement à
 * `GraphicsEnvironment.maximumWindowBounds`, primaire uniquement) ;
 * `Toolkit.getScreenInsets` retire ensuite le chrome OS. Fallback défensif
 * sur `GraphicsEnvironment.maximumWindowBounds` si la fenêtre n'a pas
 * encore de configuration graphique résolue (pas encore réalisée).
 */
private fun usableBoundsOf(window: java.awt.Window): Rectangle {
    val gc = window.graphicsConfiguration
        ?: return GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
    val bounds = gc.bounds
    return Rectangle(
        bounds.x + insets.left,
        bounds.y + insets.top,
        bounds.width - insets.left - insets.right,
        bounds.height - insets.top - insets.bottom,
    )
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun CmdKPanel(
    query: String,
    filteredItems: List<CmdKItem>,
    selectedIndex: Int,
    selectionSource: CmdKSelectionSource,
    onQueryChange: (String) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onHoverIndex: (Int) -> Unit,
    onExecuteSelected: () -> Unit,
    onExecuteIndex: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    // ── Survol souris vs navigation clavier ──────────────────────────────────
    // Le survol sélectionne (highlight + Entrée exécute l'item survolé) mais ne
    // doit JAMAIS faire défiler la liste, sinon : survol → sélection → scroll →
    // un autre item glisse sous le curseur immobile → survol → scroll… et la
    // liste dévale toute seule jusqu'en bas (bug remonté en validation UI).
    //
    // Deux gardes complémentaires, l'une ne suffit pas :
    //  1. l'auto-scroll ne suit que les sélections d'origine clavier
    //     ([CmdKSelectionSource.KEYBOARD], porté par le ViewModel) ;
    //  2. pendant une navigation clavier, le survol est ignoré jusqu'à ce que
    //     la souris bouge RÉELLEMENT : sinon les `PointerEventType.Enter`
    //     émis par le défilement sous un curseur immobile ramèneraient la
    //     sélection sur la ligne pointée, à contresens des flèches.
    // Le pointeur reprend la main dès le premier déplacement effectif : on
    // compare la position (repère du panneau, stable quand seule la liste
    // défile) à la dernière connue.
    var hoverEnabled by remember { mutableStateOf(true) }
    var lastPointerPos by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(selectedIndex, filteredItems.size) {
        if (selectionSource == CmdKSelectionSource.KEYBOARD &&
            selectedIndex >= 0 && selectedIndex < filteredItems.size
        ) {
            listState.animateScrollToItem(selectedIndex)
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Le panneau remplit la dialog sans backdrop semi-transparent au-dessus
    // (la fenêtre dialog est transparente, le SwingPanel terminal reste
    // visible derrière). Le contour est un border discret + un radius pour
    // laisser respirer.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(Radii.Xl))
            .background(Surface)
            .border(1.dp, Border1, RoundedCornerShape(Radii.Xl))
            // Réarme le survol dès que la souris se déplace vraiment. La
            // position est relative au panneau (pas à la ligne survolée) : elle
            // ne change donc pas quand seule la liste défile sous un curseur
            // immobile, ce qui est exactement le cas qu'on veut ignorer.
            // Handler non consommant : les lignes reçoivent toujours leurs
            // événements Enter/clic.
            .onPointerEvent(PointerEventType.Move) { event ->
                val pos = event.changes.lastOrNull()?.position ?: return@onPointerEvent
                // Premier événement observé (pas encore de référence) : on
                // mémorise la position SANS réactiver le survol. Sans cette
                // garde, le tout premier Move livré après une navigation
                // clavier (même si la souris n'a pas bougé, simplement parce
                // que la liste vient de défiler sous elle) repasserait
                // `lastPointerPos` de null à une valeur et réarmerait le
                // survol par la même occasion, cf. le bug d'emballement
                // documenté plus haut.
                if (lastPointerPos == null) {
                    lastPointerPos = pos
                    return@onPointerEvent
                }
                if (pos != lastPointerPos) {
                    lastPointerPos = pos
                    hoverEnabled = true
                }
            },
    ) {
        // ── Champ de recherche ────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = Spacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Lucide.Search,
                contentDescription = null,
                tint = TextDisabled,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(Spacing.Sm))
            BasicTextField(
                value = query,
                onValueChange = {
                    // Symétrique des flèches : la frappe reconstruit
                    // `filteredItems` sous un curseur qui n'a pas bougé, donc
                    // sans cette garde le prochain Move (déclenché par la
                    // liste qui se recompose sous lui) réarmerait le survol
                    // et écraserait la sélection tout juste posée par la
                    // frappe.
                    hoverEnabled = false
                    onQueryChange(it)
                },
                textStyle = TextStyle(
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontFamily = SpaceGroteskFamily,
                ),
                cursorBrush = SolidColor(Gold),
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    // Preview pour intercepter Esc/Enter/↑/↓ AVANT que le
                    // BasicTextField ne traite l'event lui-même. Avec
                    // `onKeyEvent` (post), le TextField consomme déjà
                    // certaines touches (Enter notamment) et le handler
                    // n'est jamais appelé.
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Escape -> { onDismiss(); true }
                            Key.Enter  -> { onExecuteSelected(); true }
                            // Les flèches prennent la main sur le pointeur :
                            // le survol est neutralisé jusqu'au prochain
                            // déplacement réel de la souris (voir plus haut).
                            Key.DirectionUp   -> { hoverEnabled = false; onMoveUp(); true }
                            Key.DirectionDown -> { hoverEnabled = false; onMoveDown(); true }
                            else -> false
                        }
                    },
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(Res.string.cmdk_search_placeholder),
                                color = TextDisabled,
                                fontSize = 14.sp,
                            )
                        }
                        inner()
                    }
                },
            )
            Spacer(Modifier.width(Spacing.Sm))
            KbdHint("Esc")
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Border1),
        )

        // ── Liste des résultats ───────────────────────────────────────────
        if (filteredItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.cmdk_empty),
                    color = TextDisabled,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            val firstIsHost = filteredItems.first() is CmdKItem.HostItem
            val firstCommandIdx = filteredItems.indexOfFirst { it is CmdKItem.CommandItem }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                itemsIndexed(filteredItems) { index, item ->
                    val showHostsHeader = index == 0 && firstIsHost
                    val showCommandsHeader = index == firstCommandIdx

                    if (showHostsHeader) {
                        SectionHeader(stringResource(Res.string.cmdk_section_hosts))
                    } else if (showCommandsHeader) {
                        if (firstCommandIdx > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(Border1),
                            )
                        }
                        SectionHeader(stringResource(Res.string.cmdk_section_commands))
                    }

                    CmdKResultRow(
                        item = item,
                        isSelected = index == selectedIndex,
                        onHover = { if (hoverEnabled) onHoverIndex(index) },
                        onClick = { onExecuteIndex(index) },
                    )
                }
                item { Spacer(Modifier.height(Spacing.Sm)) }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label.uppercase(),
        color = TextDisabled,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(
            start = Spacing.Md,
            end = Spacing.Md,
            top = Spacing.Md,
            bottom = Spacing.Xs,
        ),
    )
}

/**
 * Ligne d'un résultat. Layout horizontal : icône à gauche (toujours
 * Gold/GoldMuted, pas d'icône en SurfaceVariant), label au centre,
 * détails (`user@host:port`) alignés à droite en JetBrains Mono. Le
 * highlight de sélection est un Burgundy semi-transparent pour rester
 * léger.
 */
@OptIn(
    ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)
@Composable
private fun CmdKResultRow(
    item: CmdKItem,
    isSelected: Boolean,
    onHover: () -> Unit,
    onClick: () -> Unit,
) {
    val bgColor = if (isSelected) Burgundy.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent
    val labelColor = if (isSelected) GoldLight else TextPrimary
    val detailColor = if (isSelected) Gold.copy(alpha = 0.75f) else TextDisabled
    val iconTint = if (isSelected) Gold else GoldMuted

    // Le BasicTextField au-dessus garde le focus clavier permanent (autofocus
    // au mount). Du coup `Modifier.clickable` standard ne reçoit jamais de
    // pointer event sur le row : Compose Desktop ne dispatch les clics qu'aux
    // composants focusables ou via `Modifier.onClick` (foundation
    // experimental, sans focus). On utilise `onClick` pour le tap et
    // `onPointerEvent(Enter)` pour le hover qui pilote le selectedIndex.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Sm, vertical = 1.dp)
            .clip(RoundedCornerShape(Radii.Sm))
            .background(bgColor)
            .onClick(onClick = onClick)
            // Enter couvre le changement de ligne survolée ; Move couvre le
            // déplacement DANS la ligne déjà survolée : indispensable après une
            // navigation clavier, où le survol est neutralisé jusqu'au premier
            // vrai mouvement de souris : sans Move, reprendre la main à la
            // souris exigerait de traverser une frontière de ligne.
            .onPointerEvent(PointerEventType.Enter) { onHover() }
            .onPointerEvent(PointerEventType.Move) { onHover() }
            .padding(horizontal = Spacing.Sm, vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (item) {
                is CmdKItem.HostItem -> Lucide.Server
                is CmdKItem.CommandItem -> commandIcon(item.command.icon)
            },
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(16.dp),
        )

        Spacer(Modifier.width(Spacing.Md))

        // Label principal : prend le weight pour pousser les détails à droite.
        Text(
            text = when (item) {
                is CmdKItem.HostItem -> item.host.label
                is CmdKItem.CommandItem -> stringResource(item.command.labelRes)
            },
            color = labelColor,
            fontSize = 13.sp,
            fontFamily = SpaceGroteskFamily,
            modifier = Modifier.weight(1f),
        )

        // Détails (user@host:port pour les hôtes), JetBrains Mono pour
        // l'identité visuelle "réseau".
        if (item is CmdKItem.HostItem) {
            Text(
                text = "${item.host.username}@${item.host.hostname}:${item.host.port}",
                color = detailColor,
                fontSize = 11.sp,
                fontFamily = JetBrainsMonoFamily,
            )
            Spacer(Modifier.width(Spacing.Sm))
        }

        // Hint "Enter" sur l'item sélectionné : indique l'action confirmée.
        if (isSelected) {
            Icon(
                Lucide.CornerDownLeft,
                contentDescription = stringResource(Res.string.cmdk_enter_hint),
                tint = GoldMuted,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
private fun KbdHint(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radii.Xs))
            .background(SurfaceVariant)
            .padding(horizontal = Spacing.Xs, vertical = 2.dp),
    ) {
        Text(
            text = text,
            color = TextDisabled,
            fontSize = 10.sp,
        )
    }
}

private fun commandIcon(icon: CmdKCommandIcon) = when (icon) {
    CmdKCommandIcon.PLUS             -> Lucide.Plus
    CmdKCommandIcon.KEY_ROUND        -> Lucide.KeyRound
    CmdKCommandIcon.LOCK             -> Lucide.Lock
    CmdKCommandIcon.SETTINGS         -> Lucide.Settings
    CmdKCommandIcon.ARROW_LEFT_RIGHT -> Lucide.ArrowLeftRight
    CmdKCommandIcon.KEYBOARD         -> Lucide.Keyboard
}
