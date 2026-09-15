// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.composables.icons.lucide.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowDownUp
import com.composables.icons.lucide.ArrowLeftRight
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Monitor
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.SquareTerminal
import com.composables.icons.lucide.X
import fr.techtical.nextsh.desktop.data.db.repository.HostFolderWithHosts
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.action_close
import fr.techtical.nextsh.desktop.generated.resources.sidebar_action_new_host
import fr.techtical.nextsh.desktop.generated.resources.sidebar_folder_unsorted
import fr.techtical.nextsh.desktop.generated.resources.sidebar_host_status_connected
import fr.techtical.nextsh.desktop.generated.resources.sidebar_host_status_connecting
import fr.techtical.nextsh.desktop.generated.resources.sidebar_host_status_error
import fr.techtical.nextsh.desktop.generated.resources.sidebar_host_status_none
import fr.techtical.nextsh.desktop.generated.resources.sidebar_section_active_sessions
import fr.techtical.nextsh.desktop.generated.resources.sidebar_section_active_sessions_empty
import fr.techtical.nextsh.desktop.generated.resources.sidebar_section_hosts
import fr.techtical.nextsh.desktop.generated.resources.sidebar_section_hosts_empty
import fr.techtical.nextsh.desktop.generated.resources.sidebar_tab_hosts
import fr.techtical.nextsh.desktop.generated.resources.sidebar_tab_sessions
import fr.techtical.nextsh.desktop.generated.resources.sidebar_tab_settings
import fr.techtical.nextsh.desktop.generated.resources.sidebar_tab_transfers
import fr.techtical.nextsh.desktop.generated.resources.sidebar_tab_tunnels
import fr.techtical.nextsh.desktop.generated.resources.sidebar_tab_vault
import fr.techtical.nextsh.desktop.generated.resources.titlebar_search_placeholder
import fr.techtical.nextsh.desktop.sessions.SessionTab
import fr.techtical.nextsh.desktop.sessions.TabContent
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import fr.techtical.nextsh.desktop.theme.Border1
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.ErrorRed
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.GoldMuted
import fr.techtical.nextsh.desktop.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.desktop.theme.NearBlack
import fr.techtical.nextsh.desktop.theme.SidebarBg
import fr.techtical.nextsh.desktop.theme.Radii
import fr.techtical.nextsh.desktop.theme.Spacing
import fr.techtical.nextsh.desktop.theme.Surface
import fr.techtical.nextsh.desktop.theme.SuccessGreen
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.theme.TextDisabled
import fr.techtical.nextsh.desktop.theme.WarningAmber
import fr.techtical.nextsh.desktop.theme.White
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SessionStatus

/**
 * Onglets primaires de la nav rail. [shortcutKey] est la touche brute du
 * raccourci **Ctrl+Shift+&lt;shortcutKey&gt;** câblé dans le
 * [KeyEventDispatcher][java.awt.KeyEventDispatcher] AWT global de Main.kt
 * (HOSTS..VAULT → chiffres 1..5, SETTINGS → virgule) : même mapping que
 * [fr.techtical.nextsh.desktop.screenForTab]. [sidebarShortcutLabel] dérive
 * le libellé affiché à partir de cette touche.
 */
enum class SidebarTab(val labelRes: StringResource, val icon: ImageVector, val shortcutKey: String) {
    HOSTS(Res.string.sidebar_tab_hosts, Lucide.Monitor, "1"),
    SESSIONS(Res.string.sidebar_tab_sessions, Lucide.SquareTerminal, "2"),
    TUNNELS(Res.string.sidebar_tab_tunnels, Lucide.ArrowLeftRight, "3"),
    TRANSFERS(Res.string.sidebar_tab_transfers, Lucide.ArrowDownUp, "4"),
    VAULT(Res.string.sidebar_tab_vault, Lucide.KeyRound, "5"),
    SETTINGS(Res.string.sidebar_tab_settings, Lucide.Settings, ","),
}

/**
 * Returns the OS-appropriate label for [tab]'s Ctrl+Shift+<key> navigation
 * shortcut (wired in Main.kt's global AWT `KeyEventDispatcher`). Same
 * platform split as [cmdKShortcutLabel] just below: this is a *Control*
 * combo on every platform, never Command, so macOS gets the compact glyph
 * form (⇧⌃, not ⇧⌘) rather than the spelled-out Windows/Linux form:
 * - macOS: "⇧⌃1"
 * - Windows / Linux: "Ctrl+Shift+1"
 */
private fun sidebarShortcutLabel(tab: SidebarTab): String {
    val os = System.getProperty("os.name", "").lowercase()
    return if ("mac" in os) "⇧⌃${tab.shortcutKey}" else "Ctrl+Shift+${tab.shortcutKey}"
}

/**
 * Ranks [status] for reducing several concurrent [SessionTab]s on the same
 * host down to the single status the host tree's dot should show. Lower
 * value = higher priority = wins the reduction: a live [SessionStatus.CONNECTED]
 * session always wins over a merely in-flight CONNECTING/RECONNECTING one,
 * which in turn wins over an ERROR one: the user should see the best news
 * available for that host, not the worst.
 *
 * [SessionStatus.DISCONNECTED] is given the lowest priority purely to keep
 * the `when` exhaustive: [hostSessionStatusOf] filters DISCONNECTED tabs out
 * before this function is ever consulted, so that branch never actually
 * competes against another in practice.
 */
internal fun sessionStatusPriority(status: SessionStatus): Int = when (status) {
    SessionStatus.CONNECTED -> 0
    SessionStatus.CONNECTING, SessionStatus.RECONNECTING -> 1
    SessionStatus.ERROR -> 2
    SessionStatus.DISCONNECTED -> 3
}

/**
 * Reduces one host's concurrent session statuses (e.g. two terminal tabs
 * open on the same host, one connected and one erroring after a drop) down
 * to the single [SessionStatus] the sidebar tree dot for that host should
 * reflect, per [sessionStatusPriority]. [statuses] must be non-empty: the
 * only caller, [hostSessionStatusOf], only ever invokes this on the non-empty
 * groups produced by `groupBy`.
 */
internal fun reduceSessionStatuses(statuses: List<SessionStatus>): SessionStatus =
    statuses.minBy(::sessionStatusPriority)

/**
 * Derives, per host id, the live [SessionStatus] the sidebar host tree's
 * status dot should show for that host: the contract [HostTreeItem] renders
 * against. A host absent from the returned map has no live session (or only
 * disconnected/closed ones) and should fall back to the neutral "no active
 * session" dot.
 *
 * This supersedes the earlier design where the "Active sessions" section
 * above the host tree was the sole surface for live-connection state; the
 * tree now mirrors it too, so [DesktopSidebar] recomputes this whenever
 * [activeSessions] changes and threads it down to the tree.
 */
internal fun hostSessionStatusOf(sessions: List<SessionTab>): Map<String, SessionStatus> =
    sessions
        .filter { it.uiStatus != SessionStatus.DISCONNECTED }
        .groupBy({ it.host.id }, { it.uiStatus })
        .mapValues { (_, statuses) -> reduceSessionStatuses(statuses) }

/**
 * Sidebar refondue (Phase 1.2). 240 dp, alignée avec la maquette Claude
 * Design adaptée pour Windows : nav rail avec icônes Lucide, sessions
 * actives, host tree groupé par `host.group`.
 *
 * Depuis le passage à un `Window(undecorated = false)`, l'omnibox Cmd+K
 * vit dans la sidebar elle-même (zone 32 dp en tête de colonne, juste
 * au-dessus de la nav rail). Plus de bandeau horizontal pleine largeur
 * sous la title bar OS, l'espace au-dessus du contenu principal
 * (terminals, hosts, etc.) est rendu intégralement à l'app. La title
 * bar OS Windows est peinte NearBlack via [WindowsDarkTitleBar] pour
 * la continuité visuelle.
 *
 * Les flows de données (sessions, host folders) sont déjà collectés au
 * niveau supérieur (App.kt) : le composable reçoit des values plates pour
 * rester déterministe et testable.
 */
@Composable
fun DesktopSidebar(
    activeTab: SidebarTab,
    activeSessions: List<SessionTab>,
    foldersWithHosts: List<HostFolderWithHosts>,
    pendingConflicts: Int,
    activeTransfers: Int,
    onSelectTab: (SidebarTab) -> Unit,
    onSelectSession: (sessionId: String) -> Unit,
    onCloseSession: (sessionId: String) -> Unit,
    onConnectHost: (Host) -> Unit,
    onCmdKTrigger: () -> Unit,
    onNewHost: () -> Unit = {},
    /**
     * Resolves the live RTT (ms) for a [SessionTab]'s underlying SSH session.
     * `null` when no session is associated yet (e.g. SFTP linked SSH closed).
     * Caller is expected to drop a fresh value when [DesktopSshTerminalSession]
     * emits a new probe: sidebar collects the StateFlow.
     */
    latencyForSession: ((SessionTab) -> kotlinx.coroutines.flow.StateFlow<Long?>?)? = null,
) {
    // Per-host live-session status for the host tree dot below (contract:
    // the dot mirrors whatever's actually connecting/connected/erroring for
    // that host: see [hostSessionStatusOf]). Recomputed only when the
    // active-sessions list identity changes, not on every recomposition.
    val hostSessionStatus = remember(activeSessions) { hostSessionStatusOf(activeSessions) }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(240.dp)
            .background(SidebarBg)
            // Border-right 1 px (séparateur vertical de la main area)
            // dessiné via drawBehind faute de Modifier.borderEnd natif.
            .drawBehind {
                val sw = 1.dp.toPx()
                drawLine(
                    color = Border1,
                    start = androidx.compose.ui.geometry.Offset(size.width - sw / 2, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width - sw / 2, size.height),
                    strokeWidth = sw,
                )
            },
    ) {
        SidebarCmdKBar(onClick = onCmdKTrigger)
        SidebarNavRail(
            activeTab = activeTab,
            pendingConflicts = pendingConflicts,
            activeTransfers = activeTransfers,
            onSelect = onSelectTab,
        )
        Divider()
        SidebarActiveSessions(
            sessions = activeSessions,
            onSelectSession = onSelectSession,
            onCloseSession = onCloseSession,
            latencyForSession = latencyForSession,
        )
        Divider()
        SidebarHostTree(
            foldersWithHosts = foldersWithHosts,
            hostSessionStatus = hostSessionStatus,
            onConnectHost = onConnectHost,
            onNewHost = onNewHost,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Cmd+K pill rendered in a 44 dp bar at the top of the sidebar. The
 * pill itself is 32 dp tall with 13 sp placeholder text and a 14 dp
 * search icon: proportions tuned so the text reads cleanly at typical
 * 100 % / 125 % Windows scaling factors. Below this bar the nav rail
 * shifts down by ~12 dp compared to the previous tight 32 dp band; the
 * trade-off is worth it because the search bar is the busiest UI
 * element in the sidebar and squinting at 11 sp was unusable.
 */
@Composable
private fun SidebarCmdKBar(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .drawBehind {
                val sw = 1.dp.toPx()
                drawLine(
                    color = Border1,
                    start = androidx.compose.ui.geometry.Offset(0f, size.height - sw / 2),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height - sw / 2),
                    strokeWidth = sw,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .padding(horizontal = Spacing.Sm)
                .clip(RoundedCornerShape(Radii.Md))
                .border(1.dp, Border1, RoundedCornerShape(Radii.Md))
                .background(Color(0xFF161616))
                .clickable(onClick = onClick)
                .padding(horizontal = Spacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Lucide.Search,
                contentDescription = null,
                tint = TextDisabled,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(Spacing.Sm))
            Text(
                text = stringResource(Res.string.titlebar_search_placeholder),
                color = TextDisabled,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Text(text = cmdKShortcutLabel(), color = TextDisabled, fontSize = 11.sp)
        }
    }
}

/**
 * Returns the OS-appropriate Ctrl+Shift+K keyboard shortcut label for the
 * Cmd+K palette. Plain Ctrl+K is intentionally left for the terminal. This
 * is a *Control* combo on every platform (never Command), same convention
 * as [sidebarShortcutLabel] just above and `ctrlShift` in ShortcutsWindow.kt,
 * so macOS gets the compact glyph form "⇧⌃K", not "⇧⌘K": Main.kt's global
 * AWT `KeyEventDispatcher` checks `CTRL_DOWN_MASK`, never the macOS Command
 * modifier, and the label must reflect the key that actually triggers it.
 * - macOS: "⇧⌃K"
 * - Windows / Linux: "Ctrl+Shift+K"
 */
private fun cmdKShortcutLabel(): String {
    val os = System.getProperty("os.name", "").lowercase()
    return if ("mac" in os) "⇧⌃K" else "Ctrl+Shift+K"
}

@Composable
private fun SidebarNavRail(
    activeTab: SidebarTab,
    pendingConflicts: Int,
    activeTransfers: Int,
    onSelect: (SidebarTab) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = Spacing.Xs)) {
        SidebarTab.entries.forEach { tab ->
            val badge = when (tab) {
                SidebarTab.SETTINGS -> pendingConflicts
                SidebarTab.TRANSFERS -> activeTransfers
                else -> 0
            }
            NavRailItem(
                tab = tab,
                isActive = tab == activeTab,
                badge = badge,
                onClick = { onSelect(tab) },
            )
        }
    }
}

@Composable
private fun NavRailItem(tab: SidebarTab, isActive: Boolean, badge: Int, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    // Active = full Burgundy + gold icon/label (selection signal, unchanged).
    // Hovered (not active) = Burgundy α=0.18, same convention as the Cmd+K
    // palette items, so the hover affordance is consistent across the chrome.
    val bg = when {
        isActive -> Burgundy
        hovered -> Burgundy.copy(alpha = 0.18f)
        else -> Color.Transparent
    }
    val fg = if (isActive) Gold else TextSecondary
    Row(
        modifier = Modifier
            .padding(horizontal = Spacing.Md, vertical = 1.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.Sm))
            .hoverable(interactionSource)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.Sm, vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label = stringResource(tab.labelRes)
        Icon(tab.icon, contentDescription = label, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Spacing.Sm))
        Text(
            text = label,
            color = fg,
            fontSize = 13.sp,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
            // maxLines + ellipsis: the Windows/Linux shortcut label spelled
            // out in full ("Ctrl+Shift+1") is long enough that, combined with
            // the longer FR labels ("Transferts", "Paramètres") in this
            // narrow 240 dp rail, the two could together exceed the row's
            // width. Without this guard the label (the only flexible/weighted
            // child in the Row) would wrap onto a 2nd line and desync this
            // row's height from its siblings. Truncating the label instead
            // keeps every row a fixed single line and never touches the
            // shortcut hint itself, which must stay fully legible.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Spacing.Xs))
        if (badge > 0) {
            BadgeBubble(badge)
        } else {
            KbdHint(sidebarShortcutLabel(tab))
        }
    }
}

@Composable
private fun SidebarActiveSessions(
    sessions: List<SessionTab>,
    onSelectSession: (String) -> Unit,
    onCloseSession: (String) -> Unit,
    latencyForSession: ((SessionTab) -> kotlinx.coroutines.flow.StateFlow<Long?>?)?,
) {
    Column(modifier = Modifier.padding(vertical = Spacing.Sm)) {
        SidebarSectionHeader(
            title = stringResource(Res.string.sidebar_section_active_sessions),
            count = sessions.size,
            actions = {},
        )
        if (sessions.isEmpty()) {
            Text(
                text = stringResource(Res.string.sidebar_section_active_sessions_empty),
                color = TextDisabled,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = Spacing.Lg, vertical = Spacing.Xs),
            )
        } else {
            sessions.forEach { tab ->
                SessionPill(
                    tab = tab,
                    onClick = { onSelectSession(tab.tabId) },
                    onClose = { onCloseSession(tab.tabId) },
                    latencyFlow = latencyForSession?.invoke(tab),
                )
            }
        }
    }
}

@Composable
private fun SessionPill(
    tab: SessionTab,
    onClick: () -> Unit,
    onClose: () -> Unit,
    latencyFlow: kotlinx.coroutines.flow.StateFlow<Long?>?,
) {
    val isSftp = tab.content is TabContent.Sftp
    val dotColor = if (isSftp) Gold else SuccessGreen
    val latencyMs = latencyFlow?.collectAsState()?.value
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val bg = if (hovered) Burgundy.copy(alpha = 0.18f) else NearBlack
    Row(
        modifier = Modifier
            .padding(horizontal = Spacing.Md, vertical = 1.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.Sm))
            .hoverable(interactionSource)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.Sm, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(Spacing.Sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tab.host.label,
                color = TextPrimary,
                fontSize = 12.sp,
                maxLines = 1,
            )
            Text(
                text = "${tab.host.username}@ • ${if (isSftp) "SFTP" else "SSH"}",
                color = TextDisabled,
                fontSize = 10.sp,
            )
        }
        // Live latency next to the close button. Same convention as the
        // PaneHeader: SuccessGreen "● ${ms}" when measured, TextDisabled
        // "● -" when the session is live but no probe has resolved yet.
        if (latencyFlow != null) {
            Text(
                text = if (latencyMs != null) "${latencyMs}ms" else "-",
                color = if (latencyMs != null) SuccessGreen else TextDisabled,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                maxLines = 1,
            )
            Spacer(Modifier.width(Spacing.Xs))
        }
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(Radii.Xs))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.X, contentDescription = stringResource(Res.string.action_close), tint = TextSecondary, modifier = Modifier.size(11.dp))
        }
    }
}

@Composable
private fun SidebarHostTree(
    foldersWithHosts: List<HostFolderWithHosts>,
    hostSessionStatus: Map<String, SessionStatus>,
    onConnectHost: (Host) -> Unit,
    onNewHost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(vertical = Spacing.Sm)) {
        SidebarSectionHeader(
            title = stringResource(Res.string.sidebar_section_hosts),
            count = foldersWithHosts.sumOf { it.hosts.size },
            actions = {
                IconActionButton(Lucide.Plus, stringResource(Res.string.sidebar_action_new_host), onClick = onNewHost)
            },
        )
        if (foldersWithHosts.isEmpty()) {
            Text(
                text = stringResource(Res.string.sidebar_section_hosts_empty),
                color = TextDisabled,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = Spacing.Lg, vertical = Spacing.Xs),
            )
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = Spacing.Md, vertical = Spacing.Xs),
        ) {
            foldersWithHosts.forEach { bucket ->
                item(key = bucket.folder?.name ?: "__orphan__") {
                    FolderRow(bucket = bucket, hostSessionStatus = hostSessionStatus, onConnectHost = onConnectHost)
                }
            }
        }
    }
}

@Composable
private fun FolderRow(
    bucket: HostFolderWithHosts,
    hostSessionStatus: Map<String, SessionStatus>,
    onConnectHost: (Host) -> Unit,
) {
    var expanded by remember(bucket.folder?.name) { mutableStateOf(true) }
    val folderName = bucket.folder?.name ?: stringResource(Res.string.sidebar_folder_unsorted)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radii.Sm))
                .clickable { expanded = !expanded }
                .padding(horizontal = Spacing.Sm, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Lucide.ChevronDown else Lucide.ChevronRight,
                contentDescription = null,
                tint = TextDisabled,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(Spacing.Xs))
            Icon(
                imageVector = if (expanded) Lucide.FolderOpen else Lucide.Folder,
                contentDescription = null,
                tint = Gold,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(Spacing.Sm))
            Text(
                text = folderName,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = bucket.hosts.size.toString(),
                color = TextDisabled,
                fontSize = 10.sp,
            )
        }
        if (expanded) {
            bucket.hosts.forEach { host ->
                HostTreeItem(
                    host = host,
                    hostSessionStatus = hostSessionStatus,
                    onConnect = { onConnectHost(host) },
                )
            }
        }
    }
}

/**
 * Host tree row. The leading dot mirrors that host's real session state,
 * resolved from [hostSessionStatus] (built by [hostSessionStatusOf] from the
 * live [SessionTab]s), not a hardcoded placeholder. A host absent from the
 * map has no live session and falls back to the neutral GoldMuted dot (the
 * pre-existing "no active session" look). This intentionally supersedes the
 * former contract where the "Active sessions" section above the tree was
 * documented as the *only* place a live connection was surfaced: the tree
 * now reflects it too, with a non-color signal (bold label) alongside the
 * dot for colorblind users.
 */
@Composable
private fun HostTreeItem(
    host: Host,
    hostSessionStatus: Map<String, SessionStatus>,
    onConnect: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val status = hostSessionStatus[host.id]
    val dotColor = when (status) {
        SessionStatus.CONNECTED -> SuccessGreen
        SessionStatus.CONNECTING, SessionStatus.RECONNECTING -> WarningAmber
        SessionStatus.ERROR -> ErrorRed
        SessionStatus.DISCONNECTED, null -> GoldMuted
    }
    // Same `when` as the dot color above: an ERROR session was previously
    // announced as "online" (only online/offline existed), which is actively
    // misleading for screen-reader users. One label per color.
    val statusLabel = stringResource(
        when (status) {
            SessionStatus.CONNECTED -> Res.string.sidebar_host_status_connected
            SessionStatus.CONNECTING, SessionStatus.RECONNECTING -> Res.string.sidebar_host_status_connecting
            SessionStatus.ERROR -> Res.string.sidebar_host_status_error
            SessionStatus.DISCONNECTED, null -> Res.string.sidebar_host_status_none
        },
    )
    Row(
        modifier = Modifier
            .padding(start = Spacing.Lg)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.Xs))
            .hoverable(interactionSource)
            .background(if (hovered) Burgundy.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onConnect)
            .padding(horizontal = Spacing.Sm, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
                .semantics { contentDescription = statusLabel },
        )
        Spacer(Modifier.width(Spacing.Sm))
        Text(
            text = host.label,
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = if (status != null) FontWeight.Medium else null,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
    }
}

@Composable
private fun SidebarSectionHeader(
    title: String,
    count: Int,
    actions: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Lg, vertical = Spacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            color = TextDisabled,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (count > 0) {
            Text(
                text = count.toString(),
                color = TextDisabled,
                fontSize = 10.sp,
            )
            Spacer(Modifier.width(Spacing.Sm))
        }
        actions()
    }
}

@Composable
private fun IconActionButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(Radii.Xs))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = TextSecondary, modifier = Modifier.size(12.dp))
    }
}

@Composable
private fun KbdHint(text: String) {
    Text(
        text = text,
        color = TextDisabled,
        fontSize = 10.sp,
        fontFamily = MaterialTheme.typography.bodySmall.fontFamily,
        // This is a non-weighted Row child, so it always renders at its
        // full intrinsic width regardless of how little room is left: the
        // sibling label Text is the one that yields space (see its
        // maxLines/ellipsis guard in NavRailItem). maxLines = 1 here is
        // still cheap insurance against ever wrapping the shortcut text
        // itself, which must stay fully legible.
        maxLines = 1,
    )
}

@Composable
private fun BadgeBubble(count: Int) {
    val label = if (count > 99) "99+" else count.toString()
    Box(
        modifier = Modifier
            .height(16.dp)
            .clip(RoundedCornerShape(Radii.Xs))
            .background(Burgundy)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = White, fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Border1),
    )
}
