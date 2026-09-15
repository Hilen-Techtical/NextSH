// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Code
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.SquareTerminal
import com.composables.icons.lucide.X
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.sessions_action_close
import fr.techtical.nextsh.desktop.generated.resources.sessions_action_close_tab
import fr.techtical.nextsh.desktop.generated.resources.sessions_empty_button
import fr.techtical.nextsh.desktop.generated.resources.sessions_empty_hint_prefix
import fr.techtical.nextsh.desktop.generated.resources.sessions_empty_kbd_label
import fr.techtical.nextsh.desktop.generated.resources.sessions_empty_kbd_suffix
import fr.techtical.nextsh.desktop.generated.resources.sessions_empty_title
import fr.techtical.nextsh.desktop.generated.resources.sessions_sftp_opening
import fr.techtical.nextsh.desktop.generated.resources.sessions_sftp_unavailable
import fr.techtical.nextsh.desktop.generated.resources.tabbar_new_session
import fr.techtical.nextsh.desktop.generated.resources.tabbar_open_sftp
import fr.techtical.nextsh.desktop.generated.resources.tabbar_open_snippet
import fr.techtical.nextsh.desktop.generated.resources.tabbar_split_sftp
import fr.techtical.nextsh.desktop.generated.resources.tabbar_toggle_orientation
import org.jetbrains.compose.resources.stringResource
import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.desktop.sftp.SftpBrowserScreen
import fr.techtical.nextsh.desktop.theme.Border1
import fr.techtical.nextsh.desktop.theme.Border2
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.BurgundyLight
import fr.techtical.nextsh.desktop.theme.ErrorRed
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.GoldMuted
import fr.techtical.nextsh.desktop.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.desktop.theme.NearBlack
import fr.techtical.nextsh.desktop.theme.ResolvedTheme
import fr.techtical.nextsh.desktop.theme.SpaceGroteskFamily
import fr.techtical.nextsh.desktop.theme.SuccessGreen
import fr.techtical.nextsh.desktop.theme.SurfaceVariant
import fr.techtical.nextsh.desktop.theme.TextDisabled
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.theme.WarningAmber
import fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.shared.domain.model.SessionStatus
import kotlinx.coroutines.launch

/**
 * Top-level screen for the "Sessions" sidebar entry.
 *
 * Renders:
 *  - [TabBar] (38 dp, dark bg #0D0D0D, Burgundy underline on active chip)
 *  - active tab's content wrapped in [PaneHost] which prepends a [PaneHeader]
 *    above each Terminal / SFTP pane
 *  - [EmptyState] when no tab is open
 *
 * Split actions (horizontal / vertical / close) are now per-pane via
 * [PaneHeader] instead of global TabBar buttons.
 *
 * Broadcast ("synchronize-panes", Ctrl+Shift+B) is wired here as a small
 * [PaneHeader] badge on the active tab's connected Terminal panes when
 * [DesktopSessionManager.broadcastTabIds] contains the tab: the toggle
 * itself lives in Main.kt's global key dispatcher, gated to this screen.
 * Workspaces remain Phase 3, not wired here.
 */
@Composable
fun SessionTabsScreen(
    sessionManager: DesktopSessionManager = DesktopContainer.sessionManager,
    onOpenSnippetPicker: () -> Unit = {},
) {
    val tabs by sessionManager.tabs.collectAsState()
    val activeTabId by sessionManager.activeTabId.collectAsState()
    var pickerOpen by remember { mutableStateOf(false) }
    var splitPickerOpen by remember { mutableStateOf(false) }

    // The path of the pane to be split, set when the user clicks split-H/V
    // in a [PaneHeader]; consumed when the picker resolves a host.
    var splitTargetPath by remember { mutableStateOf<List<PaneSlot>>(emptyList()) }
    var splitTargetOrientation by remember { mutableStateOf(SplitOrientation.HORIZONTAL) }

    val hostsState = remember { DesktopContainer.hostRepository.observeAll() }
        .collectAsState(initial = emptyList())
    val hosts = hostsState.value

    val customThemesState = remember { DesktopContainer.customThemeRepository.observeAll() }
        .collectAsState(initial = emptyList())
    val customThemes = customThemesState.value

    val activeTab = tabs.firstOrNull { it.tabId == activeTabId }
    // ThemePicker is driven by the focused pane: descend the focused path
    // and surface its theme if it lands on a Terminal.
    val focusedTerminal = activeTab?.let {
        sessionManager.focusedPath(it.content).let { fp ->
            (paneAt(it.content, fp) as? TabContent.Terminal)
        }
    }
    val activeTheme = focusedTerminal?.theme
    // SFTP actions in the TabBar are only enabled when the focused pane of
    // the active tab is a Terminal in Connected state.
    val focusedConnectedSshSessionId = (focusedTerminal?.status as? TerminalTabStatus.Connected)?.sshSessionId
    // Toggle-orientation is only relevant when the active tab has at least
    // one Split. We toggle the outermost (root) Split: same UX as legacy.
    val activeRootSplit = activeTab?.content as? TabContent.Split

    Column(modifier = Modifier.fillMaxSize().background(NearBlack)) {
        TabBar(
            tabs = tabs,
            activeTabId = activeTabId,
            onSelectTab = sessionManager::selectTab,
            onCloseTab = sessionManager::closeSession,
            onOpenPicker = { pickerOpen = true },
            activeTheme = activeTheme,
            customThemes = customThemes,
            onThemeChange = { newThemeName ->
                activeTab?.let {
                    sessionManager.setThemeAt(it.tabId, sessionManager.focusedPath(it.content), newThemeName)
                }
            },
            onSaveCustomTheme = { theme ->
                DesktopContainer.appScope.coroutineScope.launch {
                    DesktopContainer.customThemeRepository.save(theme)
                    // Live-apply: hand the session manager the up-to-date theme list
                    // (merge the saved theme into the current snapshot) so every open
                    // pane already showing this theme re-renders instantly, without
                    // waiting for the async observeAll() emission.
                    val merged = customThemes.filterNot { it.id == theme.id } + theme
                    sessionManager.refreshCustomThemes(merged, theme.id)
                    // Also select the saved theme for the focused terminal (covers the
                    // "new theme" case where the pane wasn't using it yet).
                    activeTab?.let {
                        sessionManager.setThemeAt(it.tabId, sessionManager.focusedPath(it.content), theme.id)
                    }
                }
            },
            onDeleteCustomTheme = { id ->
                DesktopContainer.appScope.coroutineScope.launch {
                    DesktopContainer.customThemeRepository.delete(id)
                }
            },
            onOpenSftp = if (activeTab != null && focusedConnectedSshSessionId != null) {
                { sessionManager.openSftpTab(activeTab.host, focusedConnectedSshSessionId) }
            } else null,
            onSplitAsSftp = if (activeTab != null && focusedConnectedSshSessionId != null) {
                {
                    sessionManager.splitPaneAsSftpAt(
                        activeTab.tabId,
                        sessionManager.focusedPath(activeTab.content),
                    )
                }
            } else null,
            onToggleOrientation = if (activeRootSplit != null && activeTab != null) {
                { sessionManager.toggleSplitOrientation(activeTab.tabId) }
            } else null,
            onOpenSnippetPicker = if (focusedConnectedSshSessionId != null) onOpenSnippetPicker else null,
        )
        if (activeTab == null) {
            EmptyState(onNewSession = { pickerOpen = true })
        } else {
            TabContentHost(
                tab = activeTab,
                sessionManager = sessionManager,
                onSplitHorizontal = { tabId, path ->
                    splitTargetPath = path
                    splitTargetOrientation = SplitOrientation.HORIZONTAL
                    splitPickerOpen = true
                },
                onSplitVertical = { tabId, path ->
                    splitTargetPath = path
                    splitTargetOrientation = SplitOrientation.VERTICAL
                    splitPickerOpen = true
                },
                onClosePaneAt = { tabId, path ->
                    sessionManager.closePaneAt(tabId, path)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    if (pickerOpen) {
        HostPickerDialog(
            hosts = hosts,
            onPick = { host ->
                pickerOpen = false
                sessionManager.openSession(host)
            },
            onDismiss = { pickerOpen = false },
        )
    }

    if (splitPickerOpen && activeTab != null) {
        HostPickerDialog(
            hosts = hosts,
            onPick = { host ->
                splitPickerOpen = false
                sessionManager.splitPaneAt(
                    tabId = activeTab.tabId,
                    path = splitTargetPath,
                    secondHost = host,
                    orientation = splitTargetOrientation,
                )
            },
            onDismiss = { splitPickerOpen = false },
        )
    }

    // Focus restore on picker close: same open→closed transition pattern as
    // the global snippet / Ctrl+Shift+T pickers in Main.kt. Matters mostly
    // for Esc-dismiss: without the epoch bump, focus stays on whatever node
    // had it (e.g. a pane-header split button) and the next Enter re-triggers
    // it instead of reaching the terminal. On the pick paths this is a
    // harmless extra bump: the new pane's sessionId-keyed grab wins.
    var pickerWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(pickerOpen) {
        if (pickerOpen) {
            pickerWasOpen = true
        } else if (pickerWasOpen) {
            pickerWasOpen = false
            sessionManager.requestTerminalFocus()
        }
    }
    var splitPickerWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(splitPickerOpen) {
        if (splitPickerOpen) {
            splitPickerWasOpen = true
        } else if (splitPickerWasOpen) {
            splitPickerWasOpen = false
            sessionManager.requestTerminalFocus()
        }
    }
}

/**
 * Resolves the pane at [path] inside [content], descending through Splits.
 * Mirrors the manager's private helper: kept locally so the screen can
 * read the focused pane without exposing the helper publicly.
 */
private fun paneAt(content: TabContent, path: List<PaneSlot>): TabContent? {
    if (path.isEmpty()) return content
    val split = content as? TabContent.Split ?: return null
    return paneAt(split.pane(path.first()), path.drop(1))
}

// ─────────────────────────────────────────────────────────────────────────────
// Tab content routing
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TabContentHost(
    tab: SessionTab,
    sessionManager: DesktopSessionManager,
    onSplitHorizontal: (tabId: String, path: List<PaneSlot>) -> Unit,
    onSplitVertical: (tabId: String, path: List<PaneSlot>) -> Unit,
    onClosePaneAt: (tabId: String, path: List<PaneSlot>) -> Unit,
    modifier: Modifier = Modifier,
) {
    PaneHost(
        tab = tab,
        content = tab.content,
        path = emptyList(),
        sessionManager = sessionManager,
        onSplitHorizontal = onSplitHorizontal,
        onSplitVertical = onSplitVertical,
        onClosePaneAt = onClosePaneAt,
        modifier = modifier,
    )
}

/**
 * Renders a [TabContent] at the position [path] inside the tab tree.
 * `path = emptyList()` is the tab root; deeper paths address nested panes.
 *
 * Each Terminal / SFTP leaf prepends a [PaneHeader] above its content. Split
 * recurses: each inner pane gets its own PaneHeader and addresses itself
 * via the path appended with its slot.
 */
@Composable
private fun PaneHost(
    tab: SessionTab,
    content: TabContent,
    path: List<PaneSlot>,
    sessionManager: DesktopSessionManager,
    onSplitHorizontal: (tabId: String, path: List<PaneSlot>) -> Unit,
    onSplitVertical: (tabId: String, path: List<PaneSlot>) -> Unit,
    onClosePaneAt: (tabId: String, path: List<PaneSlot>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A pane is focused when every Split on its path is focused on the slot
    // that leads to it. Roots (path empty) are always rendered as focused.
    val isFocused = isPaneFocusedAt(tab.content, path)
    // A connected Terminal pane is a broadcast target exactly when its OWN
    // tab is in broadcast mode: see DesktopSessionManager's "Broadcast"
    // section. Collected here (rather than threaded down as a parameter)
    // to mirror the per-node `latency` StateFlow collection just below.
    val broadcastTabIds by sessionManager.broadcastTabIds.collectAsState()

    when (content) {
        is TabContent.Terminal -> Column(modifier = modifier) {
            val connected = content.status as? TerminalTabStatus.Connected
            // `remember` MUST key on the terminal session so that when the status
            // transitions from Connecting → Connected the latency StateFlow is
            // re-collected. Without the key, the null fallback would be cached
            // forever in the same composition.
            val latency by (
                connected?.terminal?.latencyMs?.collectAsState()
                    ?: remember(connected?.terminal) { mutableStateOf<Long?>(null) }
                )
            // For split panes the second pane connects to a different host
            // than `tab.host` (the tab-level host belongs to the first pane).
            // Resolve the actual host via the SSH session id when available,
            // fall back to `tab.host` for the AwaitingPassword/Connecting/
            // root-pane case where there's no SSH session yet.
            val paneHost = connected?.let {
                sessionManager.hostForSshSession(it.sshSessionId)
            } ?: (content.status as? TerminalTabStatus.AwaitingPassword)?.host
                ?: tab.host
            PaneHeader(
                icon = PaneIcon.Terminal,
                hostName = paneHost.label.takeUnless { it.isBlank() } ?: paneHost.hostname,
                userAtHost = "${paneHost.username}@${paneHost.hostname}:${paneHost.port}",
                latencyMs = latency,
                showLatency = connected != null,
                isFocused = isFocused,
                // Only a Connected pane is ever an actual relay target
                // (connectedTerminalIdsIn excludes everything else). A
                // Connecting/AwaitingPassword/Error pane in a broadcasting
                // tab never shows the pill even though its tab is broadcasting.
                isBroadcastTarget = connected != null && tab.tabId in broadcastTabIds,
                // Splits are allowed up to MAX_SPLIT_DEPTH levels deep. Only a
                // Connected terminal can be split (we need the SSH session).
                canSplit = path.size < MAX_SPLIT_DEPTH && content.status is TerminalTabStatus.Connected,
                canClose = true,
                onSplitHorizontal = { onSplitHorizontal(tab.tabId, path) },
                onSplitVertical = { onSplitVertical(tab.tabId, path) },
                onClose = { onClosePaneAt(tab.tabId, path) },
            )
            TerminalScreen(
                theme = content.theme,
                status = content.status,
                sessionManager = sessionManager,
                onSubmitPassword = { pwd -> sessionManager.submitPasswordAt(tab.tabId, path, pwd) },
                onReconnect = { sessionManager.reconnectAt(tab.tabId, path) },
                modifier = Modifier.fillMaxSize().weight(1f),
            )
        }

        is TabContent.Sftp -> Column(modifier = modifier) {
            // Resolve the linked SSH session's latency for display in the header.
            val linkedSession = remember(content.linkedSshSessionId) {
                sessionManager.terminalSessionFor(content.linkedSshSessionId)
            }
            val latency by (
                linkedSession?.latencyMs?.collectAsState()
                    ?: remember(linkedSession) { mutableStateOf<Long?>(null) }
                )
            // SFTP pane reuses the SSH session of an attached terminal: its
            // host identity is the host of that linked SSH session, NOT
            // `tab.host` (which is only the tab-level primary host).
            val paneHost = sessionManager.hostForSshSession(content.linkedSshSessionId) ?: tab.host
            PaneHeader(
                icon = PaneIcon.Sftp,
                hostName = paneHost.label.takeUnless { it.isBlank() } ?: paneHost.hostname,
                userAtHost = "${paneHost.username}@${paneHost.hostname}:${paneHost.port}",
                latencyMs = latency,
                showLatency = linkedSession != null,
                isFocused = isFocused,
                canSplit = false, // SFTP panes cannot be further split
                canClose = true,
                onSplitHorizontal = {},
                onSplitVertical = {},
                onClose = { onClosePaneAt(tab.tabId, path) },
            )
            when (val state = content.state) {
                SftpTabState.Opening -> Box(
                    modifier = Modifier.fillMaxSize().weight(1f).background(NearBlack),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Gold)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(Res.string.sessions_sftp_opening), color = TextSecondary)
                    }
                }
                is SftpTabState.Open -> SftpBrowserScreen(
                    sessionId = content.linkedSshSessionId,
                    tabId = tab.tabId,
                    initialCwd = state.cwd,
                    // SftpBrowserScreen still takes a single PaneSlot for cwd
                    // routing: pass the last segment of the path (good enough
                    // for one level of nesting; deeper SFTP panes don't have
                    // their cwd persisted across tab switches yet).
                    paneSlot = path.lastOrNull(),
                    modifier = Modifier.fillMaxSize().weight(1f),
                )
                is SftpTabState.Error -> Column(
                    modifier = Modifier.fillMaxSize().weight(1f).background(NearBlack).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(stringResource(Res.string.sessions_sftp_unavailable), color = ErrorRed)
                    Spacer(Modifier.height(8.dp))
                    Text(state.message, color = TextSecondary)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { sessionManager.closeSession(tab.tabId) }) {
                        Text(stringResource(Res.string.sessions_action_close_tab), color = TextPrimary)
                    }
                }
            }
        }

        is TabContent.Split -> SplitLayout(
            orientation = content.orientation,
            ratio = content.ratio,
            focusedSlot = content.focusedSlot,
            onRatioChange = { sessionManager.setRatioAt(tab.tabId, path, it) },
            onFocusFirst = { sessionManager.setFocusAt(tab.tabId, path, PaneSlot.LEFT_OR_TOP) },
            onFocusSecond = { sessionManager.setFocusAt(tab.tabId, path, PaneSlot.RIGHT_OR_BOTTOM) },
            modifier = modifier,
            first = {
                PaneHost(
                    tab = tab,
                    content = content.first,
                    path = path + PaneSlot.LEFT_OR_TOP,
                    sessionManager = sessionManager,
                    onSplitHorizontal = onSplitHorizontal,
                    onSplitVertical = onSplitVertical,
                    onClosePaneAt = onClosePaneAt,
                    modifier = Modifier.fillMaxSize(),
                )
            },
            second = {
                PaneHost(
                    tab = tab,
                    content = content.second,
                    path = path + PaneSlot.RIGHT_OR_BOTTOM,
                    sessionManager = sessionManager,
                    onSplitHorizontal = onSplitHorizontal,
                    onSplitVertical = onSplitVertical,
                    onClosePaneAt = onClosePaneAt,
                    modifier = Modifier.fillMaxSize(),
                )
            },
        )
    }
}

/**
 * True when every Split on the way to [path] is focused on the slot leading
 * to it. Empty path = root pane = always focused (single-pane tab has no
 * "unfocused" state).
 */
private fun isPaneFocusedAt(content: TabContent, path: List<PaneSlot>): Boolean {
    if (path.isEmpty()) return true
    var node: TabContent = content
    for (slot in path) {
        val split = node as? TabContent.Split ?: return false
        if (split.focusedSlot != slot) return false
        node = split.pane(slot)
    }
    return true
}

// ─────────────────────────────────────────────────────────────────────────────
// TabBar (Phase 2.2 maquette)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Horizontal tab bar: 38 dp, bg #0D0D0D, 1 dp Border1 at the bottom.
 *
 * Split-H/V and per-pane close moved to [PaneHeader] per the maquette. The bar
 * still exposes two NextSH-specific SFTP actions (not in the maquette) when a
 * connected Terminal tab is active:
 *  - [onOpenSftp] : open a standalone SFTP tab piggybacking on the active SSH
 *  - [onSplitAsSftp] : split the active terminal with an SFTP pane sharing the
 *    same SSHClient (no re-auth)
 *
 * Both callbacks are null when no Terminal is active or it is not Connected.
 */
@Composable
private fun TabBar(
    tabs: List<SessionTab>,
    activeTabId: String?,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onOpenPicker: () -> Unit,
    activeTheme: ResolvedTheme?,
    customThemes: List<CustomTerminalTheme>,
    onThemeChange: (String) -> Unit,
    onSaveCustomTheme: (CustomTerminalTheme) -> Unit,
    onDeleteCustomTheme: (String) -> Unit,
    onOpenSftp: (() -> Unit)?,
    onSplitAsSftp: (() -> Unit)?,
    onToggleOrientation: (() -> Unit)?,
    onOpenSnippetPicker: (() -> Unit)?,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(activeTabId, tabs.size) {
        if (tabs.isNotEmpty()) scroll.animateScrollTo(scroll.maxValue)
    }

    val tabBarBg = Color(0xFF0D0D0D)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(tabBarBg)
            .drawBehind {
                val strokePx = 1.dp.toPx()
                drawLine(
                    color = Border1,
                    start = Offset(0f, size.height - strokePx / 2),
                    end = Offset(size.width, size.height - strokePx / 2),
                    strokeWidth = strokePx,
                )
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Scrollable tab chips
        Row(
            modifier = Modifier
                .weight(1f, fill = true)
                .horizontalScroll(scroll),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            tabs.forEach { tab ->
                TabChip(
                    tab = tab,
                    isActive = tab.tabId == activeTabId,
                    onSelect = { onSelectTab(tab.tabId) },
                    onClose = { onCloseTab(tab.tabId) },
                )
            }
        }

        // Toggle root-split orientation H↔V when the active tab is split.
        if (onToggleOrientation != null) {
            TabBarIconButton(
                icon = Lucide.RefreshCw,
                tooltipLabel = stringResource(Res.string.tabbar_toggle_orientation),
                tooltipShortcut = null,
                onClick = onToggleOrientation,
            )
        }

        // Snippet picker, same gating as SFTP actions: only when the
        // focused pane is a Terminal in Connected state, since the picker
        // sends the chosen command to that pane via the active TtyConnector.
        if (onOpenSnippetPicker != null) {
            TabBarIconButton(
                icon = Lucide.Code,
                tooltipLabel = stringResource(Res.string.tabbar_open_snippet),
                tooltipShortcut = "Ctrl+Shift+S",
                onClick = onOpenSnippetPicker,
            )
        }

        // SFTP actions (NextSH-specific, not in maquette): only when the
        // focused pane is a Terminal in Connected state.
        if (onOpenSftp != null) {
            TabBarIconButton(
                icon = Lucide.Folder,
                tooltipLabel = stringResource(Res.string.tabbar_open_sftp),
                tooltipShortcut = null,
                onClick = onOpenSftp,
            )
        }
        if (onSplitAsSftp != null) {
            TabBarIconButton(
                icon = Lucide.FolderOpen,
                tooltipLabel = stringResource(Res.string.tabbar_split_sftp),
                tooltipShortcut = null,
                onClick = onSplitAsSftp,
            )
        }

        // Theme picker (only when a terminal tab is active)
        if (activeTheme != null) {
            TerminalThemePickerButton(
                currentThemeName = activeTheme.name,
                customThemes = customThemes,
                onThemeChange = onThemeChange,
                onSaveCustomTheme = onSaveCustomTheme,
                onDeleteCustomTheme = onDeleteCustomTheme,
            )
        }

        // New session button
        TabBarIconButton(
            icon = Lucide.Plus,
            tooltipLabel = stringResource(Res.string.tabbar_new_session),
            tooltipShortcut = "Ctrl+Shift+K",
            onClick = onOpenPicker,
        )
    }
}

@Composable
private fun TabChip(
    tab: SessionTab,
    isActive: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    val bgColor = when {
        isActive -> NearBlack
        hovered -> Color.White.copy(alpha = 0.03f)
        else -> Color.Transparent
    }
    val labelColor = when {
        isActive || hovered -> TextPrimary
        else -> TextSecondary
    }

    Box(
        modifier = Modifier
            .height(38.dp)
            .widthIn(min = 140.dp, max = 240.dp)
            .hoverable(interactionSource)
            .background(bgColor)
            .clickable(onClick = onSelect, indication = null, interactionSource = remember { MutableInteractionSource() }),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 14.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status dot 6 dp
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(tabDotColor(tab)),
            )
            Spacer(Modifier.width(8.dp))

            // Label: ellipsis on overflow
            Text(
                text = tab.host.label.takeUnless { it.isBlank() } ?: tab.host.hostname,
                color = labelColor,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            Spacer(Modifier.width(4.dp))

            // Close button 16×16
            CloseTabButton(onClose = onClose)
        }

        // Active underline: 2 dp BurgundyLight at the bottom
        if (isActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(BurgundyLight),
            )
        }
    }
}

/**
 * Resolves the dot color for a tab chip based on its [SessionTab.uiStatus]
 * with fine-grained SFTP / Split awareness:
 * - Terminal connected → SuccessGreen
 * - Terminal connecting / reconnecting → WarningAmber
 * - Terminal error → ErrorRed
 * - Terminal disconnected → TextSecondary
 * - SFTP open → Gold
 * - SFTP opening → WarningAmber
 * - SFTP error → ErrorRed
 * - Split → status of the focused pane
 */
@Composable
private fun tabDotColor(tab: SessionTab): Color {
    return when (val status = tab.uiStatus) {
        SessionStatus.CONNECTED -> {
            // Distinguish Terminal (green) vs SFTP (gold) for the root pane
            if (isRootSftp(tab)) Gold else SuccessGreen
        }
        SessionStatus.CONNECTING, SessionStatus.RECONNECTING -> WarningAmber
        SessionStatus.ERROR -> ErrorRed
        SessionStatus.DISCONNECTED -> TextSecondary
    }
}

/** True when the tab's root content (accounting for splits) is an SFTP pane. */
private fun isRootSftp(tab: SessionTab): Boolean {
    val c = tab.content
    return when {
        c is TabContent.Sftp -> true
        c is TabContent.Split -> c.pane(c.focusedSlot) is TabContent.Sftp
        else -> false
    }
}

@Composable
private fun CloseTabButton(onClose: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .size(16.dp)
            .hoverable(interactionSource)
            .background(
                color = if (hovered) Color.White.copy(alpha = 0.10f) else Color.Transparent,
                shape = RoundedCornerShape(2.dp),
            )
            .clickable(onClick = onClose, indication = null, interactionSource = remember { MutableInteractionSource() }),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Lucide.X,
            contentDescription = stringResource(Res.string.sessions_action_close),
            tint = if (hovered) TextPrimary else TextDisabled,
            modifier = Modifier.size(11.dp),
        )
    }
}

/**
 * 28×28 dp icon button used in the right side of the [TabBar] for the new-tab
 * button, the optional SFTP/Snippet actions, and split-orientation toggle.
 * Hover background `rgba(255,255,255,0.04)` matching the maquette's `.btn-icon`
 * rule. Wrapped in a [TooltipArea] showing [tooltipLabel] (and an optional
 * keyboard shortcut chip) on hover after a short delay.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TabBarIconButton(
    icon: ImageVector,
    tooltipLabel: String,
    tooltipShortcut: String?,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    androidx.compose.foundation.TooltipArea(
        tooltip = { TabBarTooltip(label = tooltipLabel, shortcut = tooltipShortcut) },
        delayMillis = 350,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .hoverable(interactionSource)
                .background(
                    color = if (hovered) Color.White.copy(alpha = 0.04f) else Color.Transparent,
                    shape = RoundedCornerShape(4.dp),
                )
                // Toolbar buttons must never hold keyboard focus: a focused
                // `clickable` re-activates on Enter/Space, which reopened the
                // snippet picker right after a pick. Snippet and new-tab have
                // Ctrl+Shift shortcuts; the SFTP / orientation actions become
                // mouse-only pending dedicated shortcuts (Tab traversal out
                // of a focused terminal was already consumed by the shell).
                // Must precede `.clickable` (the focus target it installs).
                .focusProperties { canFocus = false }
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = tooltipLabel,
                tint = TextDisabled,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun TabBarTooltip(label: String, shortcut: String?) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .background(
                color = androidx.compose.ui.graphics.Color(0xFF1A1A1A),
                shape = RoundedCornerShape(4.dp),
            )
            .border(
                width = 1.dp,
                color = Border1,
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = TextPrimary,
            fontFamily = SpaceGroteskFamily,
            fontSize = 11.sp,
        )
        if (shortcut != null) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(
                        color = androidx.compose.ui.graphics.Color(0xFF242424),
                        shape = RoundedCornerShape(3.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = Border1,
                        shape = RoundedCornerShape(3.dp),
                    )
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text(
                    text = shortcut,
                    color = TextSecondary,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// EmptyState (Phase 2.2 maquette)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Shown when no session tab is open. Displays:
 *  - SquareTerminal icon 64 dp GoldMuted
 *  - title "Aucune session ouverte" Space Grotesk SemiBold 18 sp
 *  - paragraph with a [Ctrl+Shift+K] kbd-style hint
 *  - secondary "Nouvelle session" outlined button (helps on first launch)
 */
@Composable
private fun EmptyState(onNewSession: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Lucide.SquareTerminal,
                contentDescription = null,
                tint = GoldMuted,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(Res.string.sessions_empty_title),
                color = TextPrimary,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.sessions_empty_hint_prefix),
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                KbdChip(label = cmdKLabel())
            }
            Spacer(Modifier.height(20.dp))
            // Secondary outlined button for discoverability on first launch
            Box(
                modifier = Modifier
                    .border(1.dp, Border1, RoundedCornerShape(4.dp))
                    .clickable(onClick = onNewSession)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Lucide.Plus, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(14.dp))
                    Text(stringResource(Res.string.sessions_empty_button), color = TextPrimary, fontSize = 12.sp)
                }
            }
        }
    }
}

/** Renders a keyboard shortcut pill like `[Ctrl+Shift+K]`. */
@Composable
private fun KbdChip(label: String) {
    Box(
        modifier = Modifier
            .padding(start = 4.dp)
            .border(1.dp, Border2, RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.sp,
            color = TextSecondary,
        )
    }
}

/**
 * OS-appropriate keyboard shortcut label for the Cmd+K palette.
 * macOS: "⇧⌘K" · Windows/Linux: "Ctrl+Shift+K"
 */
private fun cmdKLabel(): String {
    val os = System.getProperty("os.name", "").lowercase()
    return if ("mac" in os) "⇧⌘K" else "Ctrl+Shift+K"
}
