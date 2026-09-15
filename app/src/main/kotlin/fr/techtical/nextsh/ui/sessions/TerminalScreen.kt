// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ArrowLeftRight
import com.composables.icons.lucide.Code
import com.composables.icons.lucide.Columns2
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Rows2
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.X
import kotlinx.coroutines.delay
import androidx.hilt.navigation.compose.hiltViewModel
import fr.techtical.nextsh.R
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SessionStatus
import fr.techtical.nextsh.domain.model.Snippet
import fr.techtical.nextsh.ui.theme.*
import timber.log.Timber

// ── Touches extra ─────────────────────────────────────────────────────────────

private data class ExtraKey(val label: String, val bytes: ByteArray)

private val EXTRA_KEYS = listOf(
    ExtraKey("ESC",  byteArrayOf(0x1B)),
    ExtraKey("TAB",  byteArrayOf(0x09)),
    ExtraKey("CTRL", byteArrayOf()),   // handled specially
    ExtraKey("ALT",  byteArrayOf()),   // handled specially
    ExtraKey("↑",    byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte())),
    ExtraKey("↓",    byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte())),
    ExtraKey("←",    byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte())),
    ExtraKey("→",    byteArrayOf(0x1B, '['.code.toByte(), 'C'.code.toByte())),
    ExtraKey("PgUp", byteArrayOf(0x1B, '['.code.toByte(), '5'.code.toByte(), '~'.code.toByte())),
    ExtraKey("PgDn", byteArrayOf(0x1B, '['.code.toByte(), '6'.code.toByte(), '~'.code.toByte())),
)

// ── TerminalScreen ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    hostId: String?,
    onBack: () -> Unit,
    onNavigateToSftp: (sessionId: String, hostLabel: String) -> Unit = { _, _ -> },
    viewModel: SessionViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val fontSize by viewModel.terminalFontSize.collectAsState()
    val hosts by viewModel.hosts.collectAsState()
    val customThemes by viewModel.customThemes.collectAsState()
    val fido2ChallengeState by viewModel.fido2ChallengeState.collectAsState()

    var showHostPicker by remember { mutableStateOf(false) }
    var showSnippetPicker by remember { mutableStateOf(false) }
    var snippetToConfirm by remember { mutableStateOf<Snippet?>(null) }

    val activeHostId = uiState.tabs.getOrNull(uiState.activeTabIndex)?.host?.id
    val snippetsForHost by remember(activeHostId) {
        viewModel.snippetsForHost(activeHostId)
    }.collectAsState()

    val configuration = LocalConfiguration.current
    val maxSheetHeight = (configuration.screenHeightDp * 0.65f).dp
    // Orientation de split adaptee a la largeur reelle, recalculee a chaque
    // rotation puisque l ecran peut desormais tourner.
    val splitOrientation = defaultSplitOrientation(configuration.screenWidthDp)

    // Connexion initiale au hostId passé en argument de nav, **une seule fois**.
    // Sans `rememberSaveable`, le LaunchedEffect re-fire après un change de
    // configuration (ex : rotation écran) et relance la connexion alors même
    // que l'utilisateur avait fermé l'onglet → bug "auto-reconnect après
    // rotation depuis EmptyTerminal".
    var initialConnectAttempted by androidx.compose.runtime.saveable.rememberSaveable(hostId) {
        mutableStateOf(false)
    }
    LaunchedEffect(hostId) {
        if (hostId != null && !initialConnectAttempted && uiState.tabs.none { it.host.id == hostId }) {
            initialConnectAttempted = true
            viewModel.connectToHost(hostId)
        }
    }

    var currentTerminalView by remember { mutableStateOf<TerminalView?>(null) }
    var ctrlDown by remember { mutableStateOf(false) }
    var altDown by remember { mutableStateOf(false) }

    var showSplitPicker by remember { mutableStateOf(false) }
    val splitPickerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val splitTerminalViews = remember { mutableStateMapOf<PaneSlot, TerminalView>() }

    val context = LocalContext.current
    val hideKeyboardAndGoBack: () -> Unit = {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        (context as? android.app.Activity)?.currentFocus?.let { view ->
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
        onBack()
    }

    // Chaîne de retour, du général au particulier. La priorité suit l'ordre de
    // composition : le dernier gestionnaire actif l'emporte, donc le plus
    // spécifique se déclare en dernier.
    //
    // Devient indispensable à targetSdk 36 : le retour prédictif étant actif,
    // le code de touche RETOUR n'est plus délivré aux vues, et la sortie de
    // sélection de texte que gérait le terminal Termux ne fonctionnerait plus.
    var isSelectingText by remember { mutableStateOf(false) }

    // L'état est recalculé depuis les vues, jamais recopié depuis le dernier
    // événement reçu, pour deux raisons.
    //  - En split, les deux panneaux notifient la même variable : fermer la
    //    sélection d'un panneau effacerait l'état de l'autre.
    //  - `stopTextSelectionMode` reste sans effet dans les 300 ms qui suivent
    //    l'ouverture de la sélection (garde anti-rebond de
    //    `TextSelectionCursorController.hide`) et n'émet alors aucun
    //    `copyModeChanged`. Écrire `false` en dur désarmerait le gestionnaire
    //    alors que la sélection est toujours affichée, et le retour suivant
    //    quitterait l'écran.
    val syncSelectionState: () -> Unit = {
        isSelectingText = currentTerminalView?.isSelectingText() == true ||
            splitTerminalViews.values.any { it.isSelectingText() }
    }

    // La carte des vues de split n'est pas purgée par la sortie de split : sans
    // ce nettoyage, des vues détachées y resteraient et seraient balayées à
    // chaque retour.
    LaunchedEffect(uiState.splitState == null) {
        if (uiState.splitState == null) splitTerminalViews.clear()
    }

    // 1. Défaut : quitter l'écran, hors mode split.
    androidx.activity.compose.BackHandler(
        enabled = uiState.splitState == null,
        onBack  = hideKeyboardAndGoBack,
    )

    // 2. En split, le retour ferme le split au lieu de quitter la session.
    //    exitSplit ne ferme aucune session, il rend simplement le plein écran
    //    au panneau qui avait le focus.
    androidx.activity.compose.BackHandler(enabled = uiState.splitState != null) {
        viewModel.exitSplit()
    }

    // 3. Une sélection de texte en cours se ferme avant tout le reste. Le test
    //    `isSelectingText()` évite d instancier le contrôleur de sélection sur
    //    une vue qui n en a jamais eu.
    //
    //    Le panneau SFTP d un split se compose plus bas, son gestionnaire passe
    //    donc devant celui-ci quand il a le focus. C est voulu : le retour agit
    //    sur le panneau que l utilisateur regarde, même si une sélection reste
    //    ouverte dans l autre.
    androidx.activity.compose.BackHandler(enabled = isSelectingText) {
        currentTerminalView?.let { if (it.isSelectingText()) it.stopTextSelectionMode() }
        splitTerminalViews.values.forEach { if (it.isSelectingText()) it.stopTextSelectionMode() }
        syncSelectionState()
    }

    // Plus de pilotage de l'orientation ici. L'application entière est
    // désormais libre de tourner, le terminal n'est plus le seul écran à
    // l'être. Réimposer le portrait à la sortie de cet écran verrouillerait
    // tout le reste de l'application, et Android 16 ignore de toute façon ces
    // demandes sur les écrans d'au moins 600 dp.

    // Snackbar canal unique pour TOUTES les erreurs de connexion. Sans cette
    // route, `uiState.error` resterait collée dans `EmptyTerminal` (cas où
    // tabs.isEmpty au moment de l'échec) ou totalement invisible (cas
    // tabs.isNotEmpty, l'EmptyTerminal n'est jamais rendu). On flush dans
    // tous les cas, EmptyTerminal n'a plus à afficher l'erreur.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.error) {
        val err = uiState.error
        if (err != null) {
            snackbarHostState.showSnackbar(err)
            viewModel.clearError()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NearBlack)
            .imePadding(),
    ) {
    Column(modifier = Modifier.fillMaxSize()) {
        val splitState = uiState.splitState

        if (splitState != null) {
            SplitModeTopBar(
                splitState           = splitState,
                tabs                 = uiState.tabs,
                onToggleOrientation  = viewModel::toggleSplitOrientation,
                onSwapPanes          = viewModel::swapPanes,
                onExitSplit          = viewModel::exitSplit,
                onBack               = hideKeyboardAndGoBack,
            )

            Box(modifier = Modifier.weight(1f)) {
                SplitTerminalLayout(
                    splitState          = splitState,
                    tabs                = uiState.tabs,
                    customThemes        = customThemes,
                    fontSize            = fontSize,
                    readCtrl            = { ctrlDown },
                    readAlt             = { altDown },
                    onPaneFocused       = viewModel::setFocusedPane,
                    onSplitRatioChanged = viewModel::updateSplitRatio,
                    onTerminalViewReady = { slot, view ->
                        splitTerminalViews[slot] = view
                        if (slot == splitState.focusedPane) currentTerminalView = view
                    },
                    onCopyModeChanged = { syncSelectionState() },
                    onExitSplit = viewModel::exitSplit,
                    onReconnect = viewModel::reconnectTab,
                )
            }
        } else {
            TerminalTopBar(
                tabs        = uiState.tabs,
                activeIndex = uiState.activeTabIndex,
                onSelectTab = viewModel::selectTab,
                onCloseTab  = viewModel::closeTab,
                onAddTab    = { showHostPicker = true },
                onBack      = hideKeyboardAndGoBack,
                onSplit     = if (uiState.tabs.size >= 2) {
                    { showSplitPicker = true }
                } else null,
            )

            val activeThemeBg = uiState.tabs.getOrNull(uiState.activeTabIndex)?.let { tab ->
                Color(resolveThemePalette(tab.host.terminalTheme, customThemes).background)
            } ?: NearBlack
            Box(modifier = Modifier.weight(1f).background(activeThemeBg)) {
                when {
                    uiState.isConnecting -> ConnectingOverlay()

                    uiState.tabs.isEmpty() -> EmptyTerminal(
                        onAddTab = { showHostPicker = true },
                        onBack   = hideKeyboardAndGoBack,
                    )

                    else -> {
                        val activeTab = uiState.tabs.getOrNull(uiState.activeTabIndex)
                        if (activeTab != null) {
                            if (activeTab.status == SessionStatus.DISCONNECTED ||
                                activeTab.status == SessionStatus.ERROR
                            ) {
                                ReconnectOverlay(
                                    isError    = activeTab.status == SessionStatus.ERROR,
                                    background = activeThemeBg,
                                    onReconnect = { viewModel.reconnectTab(uiState.activeTabIndex) },
                                )
                            } else {
                                TerminalViewWrapper(
                                    terminalSession = activeTab.terminalSession,
                                    fontSize        = fontSize,
                                    palette         = resolveThemePalette(activeTab.host.terminalTheme, customThemes),
                                    readCtrl        = { ctrlDown },
                                    readAlt         = { altDown },
                                    onCopyModeChanged = { syncSelectionState() },
                                    onViewReady     = { currentTerminalView = it },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (uiState.tabs.isNotEmpty()) {
            ExtraKeysBar(
                ctrlActive   = ctrlDown,
                altActive    = altDown,
                onCtrlToggle = { ctrlDown = !ctrlDown },
                onAltToggle  = { altDown = !altDown },
                onKeyPress   = { key ->
                    val session = viewModel.focusedSession
                    if (session != null && key.bytes.isNotEmpty()) {
                        session.write(key.bytes, 0, key.bytes.size)
                    }
                    ctrlDown = false
                    altDown  = false
                },
                onOpenSnippets = { showSnippetPicker = true },
                onOpenSftp     = {
                    val activeTab = uiState.tabs.getOrNull(uiState.activeTabIndex)
                    if (activeTab != null) {
                        if (uiState.splitState == null) {
                            viewModel.enterSplitWithSftp(activeTab.sessionId, activeTab.host.label, splitOrientation)
                        } else {
                            onNavigateToSftp(activeTab.sessionId, activeTab.host.label)
                        }
                    }
                },
            )
        }
    } // ── Column body
        SnackbarHost(
            hostState = snackbarHostState,
            modifier  = Modifier.align(Alignment.BottomCenter),
        )
    } // ── Box overlay

    // Note Phase 3.2 : le dialogue de vérification de clé hôte est rendu
    // globalement par MainActivity via HostKeyPromptCoordinator.

    fido2ChallengeState?.let { state ->
        Fido2ChallengeBottomSheet(
            state = state,
            hardwareKeyAuthenticator = viewModel.hardwareKeyAuth,
            onSigned = { signature -> viewModel.completeFido2Challenge(signature) },
            onCancel = { viewModel.cancelFido2Challenge() },
        )
    }

    if (showHostPicker) {
        ModalBottomSheet(
            onDismissRequest = { showHostPicker = false },
            containerColor   = Surface,
        ) {
            PickerSheetTitle(stringResource(R.string.dialog_connect_to_host))
            if (hosts.isEmpty()) {
                PickerSheetEmpty(stringResource(R.string.empty_no_hosts_picker))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = maxSheetHeight)) {
                    items(hosts, key = { it.id }) { host ->
                        HostPickerItem(
                            host    = host,
                            onClick = {
                                viewModel.connectToHost(host.id)
                                showHostPicker = false
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.Xxl))
        }
    }

    if (showSnippetPicker) {
        ModalBottomSheet(
            onDismissRequest = { showSnippetPicker = false },
            containerColor   = Surface,
        ) {
            PickerSheetTitle(stringResource(R.string.dialog_snippet_picker_title))
            if (snippetsForHost.isEmpty()) {
                PickerSheetEmpty(stringResource(R.string.empty_snippets_title))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = maxSheetHeight)) {
                    items(snippetsForHost, key = { it.id }) { snippet ->
                        SnippetPickerItem(
                            snippet = snippet,
                            onClick = {
                                snippetToConfirm = snippet
                                showSnippetPicker = false
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.Xxl))
        }
    }

    if (showSplitPicker) {
        ModalBottomSheet(
            onDismissRequest = { showSplitPicker = false },
            sheetState       = splitPickerSheetState,
            containerColor   = Surface,
        ) {
            PickerSheetTitle(stringResource(R.string.dialog_split_picker_title))
            uiState.tabs.forEachIndexed { index, tab ->
                if (index != uiState.activeTabIndex) {
                    HostPickerItem(
                        host    = tab.host,
                        onClick = {
                            viewModel.enterSplit(index, splitOrientation)
                            showSplitPicker = false
                        },
                    )
                }
            }
            val activeTab = uiState.tabs.getOrNull(uiState.activeTabIndex)
            if (activeTab != null) {
                SftpSplitOption(
                    onClick = {
                        viewModel.enterSplitWithSftp(activeTab.sessionId, activeTab.host.label, splitOrientation)
                        showSplitPicker = false
                    },
                )
            }
            Spacer(Modifier.height(Spacing.Xxl))
        }
    }

    snippetToConfirm?.let { snippet ->
        SnippetConfirmDialog(
            snippet = snippet,
            onDismiss = { snippetToConfirm = null },
            onExecute = {
                viewModel.executeSnippet(snippet.command)
                snippetToConfirm = null
            },
        )
    }
}

// ── TopBar single mode ───────────────────────────────────────────────────────

@Composable
private fun TerminalTopBar(
    tabs: List<SessionTab>,
    activeIndex: Int,
    onSelectTab: (Int) -> Unit,
    onCloseTab: (Int) -> Unit,
    onAddTab: () -> Unit,
    onBack: () -> Unit,
    onSplit: (() -> Unit)? = null,
) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NearBlack)
            .border(1.dp, Border1)
            // safeDrawing et non statusBars seuls : en paysage la barre d etat
            // mesure zero et c est l encoche, laterale, qui masquerait la
            // fleche de retour. Le decalage de 16 dp qui compensait le double
            // padding de NavGraph n a plus lieu d etre.
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            )
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(
                Lucide.ArrowLeft,
                contentDescription = stringResource(R.string.action_back),
                tint = TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                SessionTabChip(
                    tab      = tab,
                    isActive = index == activeIndex,
                    onClick  = { onSelectTab(index) },
                    onClose  = { onCloseTab(index) },
                )
            }
        }

        IconButton(onClick = onAddTab, modifier = Modifier.size(40.dp)) {
            Icon(
                Lucide.Plus,
                contentDescription = stringResource(R.string.action_new_tab),
                tint     = Gold,
                modifier = Modifier.size(18.dp),
            )
        }

        if (onSplit != null) {
            IconButton(onClick = onSplit, modifier = Modifier.size(40.dp)) {
                Icon(
                    Lucide.Columns2,
                    contentDescription = stringResource(R.string.action_split_screen),
                    tint     = Gold,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun SessionTabChip(
    tab: SessionTab,
    isActive: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val bgColor   = if (isActive) Burgundy else Surface
    val textColor = if (isActive) White else TextSecondary
    val borderColor = if (isActive) Burgundy else Border1
    val statusColor = when (tab.status) {
        SessionStatus.CONNECTED    -> SuccessGreen
        SessionStatus.CONNECTING   -> WarningAmber
        SessionStatus.RECONNECTING -> WarningAmber
        SessionStatus.DISCONNECTED -> TextDisabled
        SessionStatus.ERROR        -> ErrorRed
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radii.Sm))
            .background(bgColor, RoundedCornerShape(Radii.Sm))
            .border(1.dp, borderColor, RoundedCornerShape(Radii.Sm))
            .clickable(onClick = onClick)
            .padding(start = Spacing.Sm, end = Spacing.Xs, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(50))
                .background(statusColor, RoundedCornerShape(50)),
        )
        Text(
            text       = tab.host.label,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Medium,
            fontSize   = 12.sp,
            color      = textColor,
            maxLines   = 1,
        )
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(Radii.Xs))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Lucide.X,
                contentDescription = stringResource(R.string.action_close_tab, tab.host.label),
                tint     = textColor,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

// ── SplitModeTopBar ──────────────────────────────────────────────────────────

@Composable
private fun SplitModeTopBar(
    splitState: SplitState,
    tabs: List<SessionTab>,
    onToggleOrientation: () -> Unit,
    onSwapPanes: () -> Unit,
    onExitSplit: () -> Unit,
    onBack: () -> Unit,
) {

    fun paneLabel(content: PaneContent): String = when (content) {
        is PaneContent.Terminal -> tabs.getOrNull(content.tabIndex)?.host?.label ?: "Terminal"
        is PaneContent.Sftp     -> "SFTP: ${content.hostLabel}"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NearBlack)
            .border(1.dp, Border1)
            // safeDrawing et non statusBars seuls : en paysage la barre d etat
            // mesure zero et c est l encoche, laterale, qui masquerait la
            // fleche de retour. Le decalage de 16 dp qui compensait le double
            // padding de NavGraph n a plus lieu d etre.
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            )
            .height(40.dp)
            .padding(horizontal = Spacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(
                Lucide.ArrowLeft,
                contentDescription = stringResource(R.string.action_back),
                tint     = TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }

        SplitPaneLabel(
            label   = paneLabel(splitState.leftOrTopPane),
            focused = splitState.focusedPane == PaneSlot.LEFT_OR_TOP,
            modifier = Modifier.weight(1f),
        )

        IconButton(onClick = onSwapPanes, modifier = Modifier.size(40.dp)) {
            Icon(
                Lucide.ArrowLeftRight,
                contentDescription = stringResource(R.string.action_swap_panes),
                tint     = TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }

        SplitPaneLabel(
            label   = paneLabel(splitState.rightOrBottomPane),
            focused = splitState.focusedPane == PaneSlot.RIGHT_OR_BOTTOM,
            modifier = Modifier.weight(1f),
        )

        IconButton(onClick = onToggleOrientation, modifier = Modifier.size(40.dp)) {
            val orientationIcon = when (splitState.orientation) {
                SplitOrientation.HORIZONTAL -> Lucide.Columns2
                SplitOrientation.VERTICAL   -> Lucide.Rows2
            }
            Icon(
                orientationIcon,
                contentDescription = stringResource(R.string.action_toggle_orientation),
                tint     = TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }

        IconButton(onClick = onExitSplit, modifier = Modifier.size(40.dp)) {
            Icon(
                Lucide.X,
                contentDescription = stringResource(R.string.action_exit_split),
                tint     = TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SplitPaneLabel(label: String, focused: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier            = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text       = label,
            fontFamily = SpaceGroteskFamily,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            fontSize   = 12.sp,
            color      = if (focused) Gold else TextSecondary,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis,
        )
        if (focused) {
            Spacer(Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(2.dp)
                    .background(Gold),
            )
        }
    }
}

// ── Picker bottom sheet helpers ──────────────────────────────────────────────

@Composable
private fun PickerSheetTitle(text: String) {
    Text(
        text       = text,
        fontFamily = SpaceGroteskFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize   = 16.sp,
        color      = TextPrimary,
        modifier   = Modifier.padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
    )
}

@Composable
private fun PickerSheetEmpty(text: String) {
    Text(
        text       = text,
        fontFamily = SpaceGroteskFamily,
        fontSize   = 13.sp,
        color      = TextSecondary,
        modifier   = Modifier.padding(Spacing.Lg),
    )
}

@Composable
private fun HostPickerItem(host: Host, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(Radii.Sm))
                .background(SurfaceVariant, RoundedCornerShape(Radii.Sm)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Lucide.Server,
                contentDescription = null,
                tint     = GoldMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = host.label,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 14.sp,
                color      = TextPrimary,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            Text(
                text       = "${host.username}@${host.hostname}:${host.port}",
                fontFamily = JetBrainsMonoFamily,
                fontSize   = 11.sp,
                color      = TextSecondary,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SftpSplitOption(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(Radii.Sm))
                .background(Gold.copy(alpha = 0.10f), RoundedCornerShape(Radii.Sm)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Lucide.Folder,
                contentDescription = null,
                tint     = Gold,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text       = stringResource(R.string.action_split_with_sftp),
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize   = 14.sp,
            color      = TextPrimary,
        )
    }
}

@Composable
private fun SnippetPickerItem(snippet: Snippet, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(Radii.Sm))
                .background(Gold.copy(alpha = 0.10f), RoundedCornerShape(Radii.Sm)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Lucide.Code,
                contentDescription = null,
                tint     = Gold,
                modifier = Modifier.size(16.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = snippet.label,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 14.sp,
                color      = TextPrimary,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            Text(
                text       = snippet.command,
                fontFamily = JetBrainsMonoFamily,
                fontSize   = 11.sp,
                color      = TextSecondary,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Snippet confirm dialog ───────────────────────────────────────────────────

@Composable
private fun SnippetConfirmDialog(
    snippet: Snippet,
    onDismiss: () -> Unit,
    onExecute: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface,
        shape            = RoundedCornerShape(Radii.Lg),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(Radii.Sm))
                        .background(Gold.copy(alpha = 0.12f), RoundedCornerShape(Radii.Sm)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Lucide.Play,
                        contentDescription = null,
                        tint     = Gold,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text       = stringResource(R.string.dialog_execute_snippet_title),
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 16.sp,
                    color      = TextPrimary,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                Text(
                    text       = snippet.label,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize   = 13.sp,
                    color      = TextPrimary,
                )
                Text(
                    text       = snippet.command,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize   = 12.sp,
                    color      = Gold,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onExecute,
                colors  = ButtonDefaults.buttonColors(
                    containerColor = Burgundy,
                    contentColor   = White,
                ),
                shape = RoundedCornerShape(Radii.Md),
            ) {
                Text(
                    text       = stringResource(R.string.action_execute),
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 13.sp,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text       = stringResource(R.string.action_cancel),
                    fontFamily = SpaceGroteskFamily,
                    fontSize   = 13.sp,
                    color      = TextSecondary,
                )
            }
        },
    )
}

// ── États ─────────────────────────────────────────────────────────────────────

@Composable
private fun ConnectingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NearBlack),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            CircularProgressIndicator(color = Gold, strokeWidth = 2.dp)
            Text(
                text       = stringResource(R.string.status_connecting_ssh),
                fontFamily = SpaceGroteskFamily,
                fontSize   = 13.sp,
                color      = TextSecondary,
            )
        }
    }
}

@Composable
private fun EmptyTerminal(
    onAddTab: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NearBlack),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
            modifier = Modifier.padding(Spacing.Xxl),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(Radii.Md))
                    .background(GoldMuted.copy(alpha = 0.10f), RoundedCornerShape(Radii.Md)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Lucide.Server,
                    contentDescription = null,
                    tint     = GoldMuted,
                    modifier = Modifier.size(28.dp),
                )
            }
            Text(
                text       = stringResource(R.string.status_no_active_session),
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 15.sp,
                color      = TextPrimary,
            )
            Spacer(Modifier.height(Spacing.Sm))
            // Action principale : ouvrir le host picker, restaure la
            // possibilité de relancer une session après un échec qui a
            // persisté `uiState.error` dans cet écran.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radii.Md))
                    .background(Burgundy, RoundedCornerShape(Radii.Md))
                    .clickable(onClick = onAddTab)
                    .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                Icon(
                    Lucide.Plus,
                    contentDescription = null,
                    tint     = White,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text       = stringResource(R.string.action_new_tab),
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 13.sp,
                    color      = White,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radii.Md))
                    .border(1.dp, Border2, RoundedCornerShape(Radii.Md))
                    .clickable(onClick = onBack)
                    .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
            ) {
                Text(
                    text       = stringResource(R.string.action_back),
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 13.sp,
                    color      = TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun ReconnectOverlay(
    isError: Boolean,
    background: Color,
    onReconnect: () -> Unit,
) {
    Box(
        modifier         = Modifier
            .fillMaxSize()
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            Text(
                text       = if (isError)
                    stringResource(R.string.status_connection_error)
                else
                    stringResource(R.string.status_session_disconnected),
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 14.sp,
                color      = if (isError) ErrorRed else TextSecondary,
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radii.Md))
                    .background(Burgundy, RoundedCornerShape(Radii.Md))
                    .clickable(onClick = onReconnect)
                    .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
            ) {
                Text(
                    text       = stringResource(R.string.action_reconnect),
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 13.sp,
                    color      = White,
                )
            }
        }
    }
}

// ── ExtraKeysBar ─────────────────────────────────────────────────────────────

@Composable
private fun ExtraKeysBar(
    ctrlActive: Boolean,
    altActive: Boolean,
    onCtrlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onKeyPress: (ExtraKey) -> Unit,
    onOpenSnippets: () -> Unit,
    onOpenSftp: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface)
            .border(1.dp, Border1)
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.Xs, vertical = Spacing.Xs),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconKeyButton(
            icon  = Lucide.Code,
            label = stringResource(R.string.dialog_snippet_picker_title),
            onClick = onOpenSnippets,
        )
        IconKeyButton(
            icon  = Lucide.Folder,
            label = stringResource(R.string.action_sftp_browse),
            onClick = onOpenSftp,
        )

        EXTRA_KEYS.forEach { key ->
            when (key.label) {
                "CTRL" -> ModifierKeyButton(label = key.label, isActive = ctrlActive, onToggle = onCtrlToggle)
                "ALT"  -> ModifierKeyButton(label = key.label, isActive = altActive, onToggle = onAltToggle)
                else   -> RepeatableKeyButton(label = key.label, onPress = { onKeyPress(key) })
            }
        }
    }
}

@Composable
private fun IconKeyButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(Radii.Sm))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Gold,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun ModifierKeyButton(
    label: String,
    isActive: Boolean,
    onToggle: () -> Unit,
) {
    val bg = if (isActive) Burgundy else Color.Transparent
    val textColor = if (isActive) White else TextPrimary
    val borderColor = if (isActive) Burgundy else Border2

    Box(
        modifier = Modifier
            .height(32.dp)
            .defaultMinSize(minWidth = 44.dp)
            .clip(RoundedCornerShape(Radii.Sm))
            .background(bg, RoundedCornerShape(Radii.Sm))
            .border(1.dp, borderColor, RoundedCornerShape(Radii.Sm))
            .clickable(onClick = onToggle)
            .padding(horizontal = Spacing.Sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            color = textColor,
        )
    }
}

@Composable
private fun RepeatableKeyButton(label: String, onPress: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(isPressed) {
        if (isPressed) {
            delay(400)
            while (true) {
                onPress()
                delay(80)
            }
        }
    }

    Box(
        modifier = Modifier
            .height(32.dp)
            .defaultMinSize(minWidth = 44.dp)
            .clip(RoundedCornerShape(Radii.Sm))
            .border(1.dp, Border2, RoundedCornerShape(Radii.Sm))
            .clickable(onClick = onPress, interactionSource = interactionSource, indication = null)
            .padding(horizontal = Spacing.Sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            color = TextPrimary,
        )
    }
}

// ── Wrapper TerminalView (préservé verbatim) ─────────────────────────────────

@Composable
internal fun TerminalViewWrapper(
    terminalSession: fr.techtical.nextsh.core.ssh.SshTerminalSession,
    fontSize: Int,
    palette: TerminalThemePalette,
    readCtrl: () -> Boolean,
    readAlt: () -> Boolean,
    isSelected: () -> Boolean = { true },
    onCopyModeChanged: (Boolean) -> Unit = {},
    onViewReady: (TerminalView) -> Unit,
) {
    val currentPalette by rememberUpdatedState(palette)
    val currentIsSelected by rememberUpdatedState(isSelected)

    // Holds the live TerminalView so live-apply can re-theme an OPEN session
    // without recreating it. Set on factory creation; read by the LaunchedEffect.
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }

    // Live-apply: re-key on the resolved [palette]. When a custom theme in use by
    // this session is edited and saved, the upstream `customThemes` flow re-emits,
    // `resolveThemePalette` yields a new palette, and this effect re-applies the
    // ARGB colors to the already-attached emulator + invalidates: the open
    // terminal re-renders instantly, no leave/return needed.
    LaunchedEffect(terminalView, palette) {
        terminalView?.let { applyTerminalTheme(it, palette) }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            TerminalView(ctx, null).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                setTerminalViewClient(buildTerminalViewClient(this, { currentPalette }, readCtrl, readAlt, { currentIsSelected() }, onCopyModeChanged))
                setTextSize(fontSize)
                terminalSession.onScreenUpdate = { onScreenUpdated() }
                // Repose le thème dès que le programme distant remet la palette
                // à zéro. ncurses le fait à chaque redessin complet, donc à
                // chaque redimensionnement : ouvrir ou fermer le clavier
                // pendant un nano faisait sinon perdre le thème de l'hôte.
                terminalSession.onPaletteOverridden = { applyTerminalTheme(this, currentPalette) }
                attachSession(terminalSession)
                terminalView = this
                onViewReady(this)
                requestFocus()
            }
        },
        update = { view ->
            view.setTextSize(fontSize)
            if (view.mTermSession !== terminalSession) {
                terminalSession.onScreenUpdate = { view.onScreenUpdated() }
                view.attachSession(terminalSession)
                onViewReady(view)
            }
            terminalSession.onPaletteOverridden = { applyTerminalTheme(view, currentPalette) }
            if (terminalView !== view) terminalView = view
            applyTerminalTheme(view, currentPalette)
        },
    )
}

internal fun buildTerminalViewClient(
    terminalView: TerminalView,
    getPalette: () -> TerminalThemePalette,
    readCtrl: () -> Boolean,
    readAlt: () -> Boolean,
    isSelected: () -> Boolean = { true },
    onCopyModeChanged: (Boolean) -> Unit = {},
): TerminalViewClient {
    return object : TerminalViewClient {
        override fun onScale(scale: Float): Float = scale

        override fun onSingleTapUp(e: MotionEvent?) {
            terminalView.requestFocus()
            val imm = terminalView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = isSelected()

        override fun copyModeChanged(copyMode: Boolean) { onCopyModeChanged(copyMode) }

        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
        override fun onLongPress(event: MotionEvent?): Boolean = false

        override fun readControlKey(): Boolean = readCtrl()
        override fun readAltKey(): Boolean = readAlt()
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

        override fun onEmulatorSet() {
            applyTerminalTheme(terminalView, getPalette())
        }

        override fun logError(tag: String, message: String) { Timber.tag(tag).e(message) }
        override fun logWarn(tag: String, message: String) { Timber.tag(tag).w(message) }
        override fun logInfo(tag: String, message: String) { Timber.tag(tag).i(message) }
        override fun logDebug(tag: String, message: String) { Timber.tag(tag).d(message) }
        override fun logVerbose(tag: String, message: String) { Timber.tag(tag).v(message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
            Timber.tag(tag).e(e, message)
        }
        override fun logStackTrace(tag: String, e: Exception) { Timber.tag(tag).e(e) }
    }
}

internal fun applyTerminalTheme(terminalView: TerminalView, palette: TerminalThemePalette) {
    val emulator = terminalView.mEmulator ?: return
    applyPaletteTo(emulator.mColors.mCurrentColors, palette)
    terminalView.invalidate()
}

/**
 * Écrit la palette du thème dans la table de couleurs de l'émulateur.
 *
 * Seuls les seize index ANSI et les trois couleurs spéciales sont touchés :
 * les index 16 à 255, qu'un programme distant peut définir lui-même, restent
 * intacts.
 */
internal fun applyPaletteTo(colors: IntArray, palette: TerminalThemePalette) {
    colors[com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND] = palette.foreground
    colors[com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND] = palette.background
    colors[com.termux.terminal.TextStyle.COLOR_INDEX_CURSOR] = palette.cursor
    for (i in 0..15) { colors[i] = palette.ansi[i] }
}
