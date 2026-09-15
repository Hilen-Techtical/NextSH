// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions

import com.jediterm.terminal.TtyConnector
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.reconnect_default_message_lost
import fr.techtical.nextsh.desktop.generated.resources.session_error_biometric_unsupported
import fr.techtical.nextsh.desktop.generated.resources.session_error_certificate_missing
import fr.techtical.nextsh.desktop.generated.resources.session_error_fido2_key_invalid
import fr.techtical.nextsh.desktop.generated.resources.session_error_key_or_certificate_missing
import fr.techtical.nextsh.desktop.generated.resources.session_error_key_repository_unavailable
import fr.techtical.nextsh.desktop.generated.resources.session_error_linked_terminal_closed
import fr.techtical.nextsh.desktop.generated.resources.session_error_private_key_missing
import fr.techtical.nextsh.desktop.generated.resources.session_error_ssh_key_missing
import fr.techtical.nextsh.desktop.generated.resources.session_error_ssh_session_missing
import fr.techtical.nextsh.desktop.core.ssh.DesktopKnownHostsVerifier
import fr.techtical.nextsh.desktop.core.ssh.DesktopSftpManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopSshSessionManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopSshTerminalSession
import fr.techtical.nextsh.desktop.core.ssh.HostKeyVerifyResult
import kotlinx.coroutines.sync.withLock
import fr.techtical.nextsh.desktop.theme.ResolvedTheme
import fr.techtical.nextsh.desktop.theme.TerminalThemePalette
import fr.techtical.nextsh.desktop.theme.resolveTheme
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SessionStatus
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.SshSession
import fr.techtical.nextsh.shared.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.AppScope
import fr.techtical.nextsh.shared.util.randomUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap

/**
 * Terminal-tab lifecycle states. The tab outlives any single SSH session
 * attached to it (so the UI keeps it visible after disconnect and offers a
 * reconnect action). [sshSessionId] points into [DesktopSshSessionManager]
 * and is only set while connected.
 */
sealed interface TerminalTabStatus {
    data object Connecting : TerminalTabStatus
    data class AwaitingPassword(val host: Host) : TerminalTabStatus
    data class Connected(
        val sshSessionId: String,
        val terminal: DesktopSshTerminalSession,
    ) : TerminalTabStatus
    data class Disconnected(val reason: String) : TerminalTabStatus
    data class Error(val message: String) : TerminalTabStatus
}

/**
 * SFTP-tab lifecycle. An SFTP tab piggybacks on a terminal's already-
 * authenticated `SSHClient` (see [TabContent.Sftp.linkedSshSessionId]):
 * no re-auth needed. If the linked terminal tab closes, the SSH client
 * disconnects and the SFTP tab transitions to Error.
 */
sealed interface SftpTabState {
    data object Opening : SftpTabState
    data class Open(val cwd: String) : SftpTabState
    data class Error(val message: String) : SftpTabState
}

enum class SplitOrientation { HORIZONTAL, VERTICAL }
enum class PaneSlot { LEFT_OR_TOP, RIGHT_OR_BOTTOM }

sealed interface TabContent {
    data class Terminal(
        val theme: ResolvedTheme,
        val status: TerminalTabStatus,
    ) : TabContent

    data class Sftp(
        val linkedSshSessionId: String,
        val state: SftpTabState,
    ) : TabContent

    /**
     * Two non-split panes side by side (or stacked). Nested splits are not
     * supported: constructor asserts `first` and `second` are not Split.
     * Each pane keeps its own lifecycle (own SSH session for Terminal panes,
     * own SFTPClient for Sftp panes).
     */
    data class Split(
        val first: TabContent,
        val second: TabContent,
        val orientation: SplitOrientation,
        val focusedSlot: PaneSlot,
        val ratio: Float = 0.5f,
    ) : TabContent {
        init {
            // Nested splits are allowed: any pane can itself be a Split. The
            // manager caps depth at [MAX_SPLIT_DEPTH] before creating a new
            // sub-split, so init only guards the ratio.
            require(ratio in 0.20f..0.80f) { "Ratio must be in [0.20, 0.80]" }
        }

        fun pane(slot: PaneSlot): TabContent = when (slot) {
            PaneSlot.LEFT_OR_TOP -> first
            PaneSlot.RIGHT_OR_BOTTOM -> second
        }

        fun withPane(slot: PaneSlot, content: TabContent): Split = when (slot) {
            PaneSlot.LEFT_OR_TOP -> copy(first = content)
            PaneSlot.RIGHT_OR_BOTTOM -> copy(second = content)
        }
    }
}

/** Maximum nesting depth for [TabContent.Split] (≤ 8 panes per tab). */
internal const val MAX_SPLIT_DEPTH = 3

/**
 * Descends [content]'s focused path (recursing through [TabContent.Split]
 * `focusedSlot`s) and returns the sshSessionId if the deepest focused pane is
 * a Connected Terminal: null otherwise (SFTP pane, connecting, disconnected,
 * error). Pure so it stays directly unit-testable with hand-built trees.
 */
internal fun focusedConnectedTerminalId(content: TabContent): String? = when (content) {
    is TabContent.Terminal -> (content.status as? TerminalTabStatus.Connected)?.sshSessionId
    is TabContent.Sftp -> null
    is TabContent.Split -> focusedConnectedTerminalId(content.pane(content.focusedSlot))
}

/**
 * Lists the sshSessionId of every Terminal pane in [content] that is
 * currently [TerminalTabStatus.Connected], in DFS visual order (mirrors
 * [paneLeafPaths]). Unlike [sshSessionIdsIn] this deliberately EXCLUDES Sftp
 * panes: an Sftp pane's `linkedSshSessionId` shares the same underlying SSH
 * session as its terminal, but broadcast must never fan out into an SFTP
 * browser (there is no shell on the other end to receive the bytes). Pure
 * so it stays directly unit-testable with hand-built trees. Backs
 * [DesktopSessionManager]'s broadcast ("synchronize-panes", Ctrl+Shift+B)
 * feature: both the toggle's "≥ 2 panes" guard and the fan-out target list.
 */
internal fun connectedTerminalIdsIn(content: TabContent): List<String> = when (content) {
    is TabContent.Terminal -> (content.status as? TerminalTabStatus.Connected)
        ?.sshSessionId?.let { listOf(it) } ?: emptyList()
    is TabContent.Sftp -> emptyList()
    is TabContent.Split -> connectedTerminalIdsIn(content.first) + connectedTerminalIdsIn(content.second)
}

/**
 * Lists the [path][List<PaneSlot>] of every leaf pane (Terminal or Sftp,
 * never a Split) in [content], in DFS visual order: [TabContent.Split.first]
 * before [TabContent.Split.second], recursively. A lone Terminal/Sftp pane
 * (tab with no Split at all) yields a single-element list: `[emptyList()]`.
 * Pure so it stays directly unit-testable with hand-built trees. Backs
 * [DesktopSessionManager.focusNextPane] / [DesktopSessionManager.focusPreviousPane].
 */
internal fun paneLeafPaths(content: TabContent): List<List<PaneSlot>> = when (content) {
    is TabContent.Terminal, is TabContent.Sftp -> listOf(emptyList())
    is TabContent.Split ->
        paneLeafPaths(content.first).map { listOf(PaneSlot.LEFT_OR_TOP) + it } +
            paneLeafPaths(content.second).map { listOf(PaneSlot.RIGHT_OR_BOTTOM) + it }
}

/**
 * Like [paneLeafPaths] but keeps ONLY [TabContent.Terminal] leaves, in the
 * same DFS visual order. Backs the Ctrl+Shift+N / Ctrl+Shift+P pane cycle
 * ([DesktopSessionManager.focusNextPane] / [focusPreviousPane]).
 *
 * SFTP panes are skipped on purpose: the shortcut is "cycle through the
 * TERMINALS". Landing the selection ring on an SFTP browser moved the
 * visual highlight but not the keyboard focus ([terminalFocusEpoch] is
 * only observed by Terminal pane renderers), so the next keystroke went to
 * the terminal the user had just left, i.e. typing into the wrong pane.
 *
 * Terminal panes are included whatever their [TerminalTabStatus]: a
 * Connecting / AwaitingPassword / Disconnected / Error pane still renders a
 * real, focusable Compose surface (password field, Reconnect button), so
 * cycling onto it is meaningful. Pure so it stays directly unit-testable
 * with hand-built trees.
 */
internal fun terminalPaneLeafPaths(content: TabContent): List<List<PaneSlot>> = when (content) {
    is TabContent.Terminal -> listOf(emptyList())
    is TabContent.Sftp -> emptyList()
    is TabContent.Split ->
        terminalPaneLeafPaths(content.first).map { listOf(PaneSlot.LEFT_OR_TOP) + it } +
            terminalPaneLeafPaths(content.second).map { listOf(PaneSlot.RIGHT_OR_BOTTOM) + it }
}

data class SessionTab(
    val tabId: String,
    val host: Host,
    val content: TabContent,
    val connectedAt: Long? = null,
) {
    val uiStatus: SessionStatus
        get() = statusOf(content)

    companion object {
        fun statusOf(c: TabContent): SessionStatus = when (c) {
            is TabContent.Terminal -> when (c.status) {
                is TerminalTabStatus.Connecting -> SessionStatus.CONNECTING
                is TerminalTabStatus.AwaitingPassword -> SessionStatus.CONNECTING
                is TerminalTabStatus.Connected -> SessionStatus.CONNECTED
                is TerminalTabStatus.Disconnected -> SessionStatus.DISCONNECTED
                is TerminalTabStatus.Error -> SessionStatus.ERROR
            }
            is TabContent.Sftp -> when (c.state) {
                is SftpTabState.Opening -> SessionStatus.CONNECTING
                is SftpTabState.Open -> SessionStatus.CONNECTED
                is SftpTabState.Error -> SessionStatus.ERROR
            }
            // For a Split the focused pane's status drives the chip: users
            // care about the pane they're interacting with.
            is TabContent.Split -> statusOf(c.pane(c.focusedSlot))
        }
    }
}

/**
 * UI-facing wrapper around [DesktopSshSessionManager] + [DesktopSftpManager]
 * that owns the **tab model** (multi-session, mixed terminal / SFTP tabs):
 * which tabs are open, which is active, per-tab lifecycle state.
 * SSH / SFTP plumbing is delegated: this class does not speak protocol.
 */
class DesktopSessionManager(
    private val hostRepository: HostRepository,
    private val vaultManager: VaultManager,
    private val sshSessionManager: DesktopSshSessionManager,
    private val sftpManager: DesktopSftpManager,
    private val knownHostsVerifier: DesktopKnownHostsVerifier,
    private val appScope: AppScope,
    private val sshKeyRepository: SshKeyRepository? = null,
    private val customThemeRepository: CustomTerminalThemeRepository? = null,
) {
    private val _tabs = MutableStateFlow<List<SessionTab>>(emptyList())
    val tabs: StateFlow<List<SessionTab>> = _tabs.asStateFlow()

    /**
     * Latest snapshot of custom themes, kept current by observing the repo. Read
     * synchronously by [resolveThemeName] so a host's theme (possibly a custom
     * UUID) resolves to a palette without suspending in tab-construction paths.
     */
    @Volatile
    private var customThemesSnapshot: List<CustomTerminalTheme> = emptyList()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    // Monotonic "terminal focus" epoch. Bumped by [requestTerminalFocus] when
    // a modal picker (snippet / host) closes; the renderer of the active tab's
    // focused Terminal pane observes it and re-grabs keyboard focus (Compose
    // FocusRequester or Swing requestFocusInWindow depending on the renderer
    // flag). Without this, focus stays on whatever Compose node had it before
    // the picker opened: e.g. the TabBar snippet button, which re-activates
    // on Enter and reopens the picker instead of running the command.
    private val _terminalFocusEpoch = MutableStateFlow(0L)
    val terminalFocusEpoch: StateFlow<Long> = _terminalFocusEpoch.asStateFlow()

    /** Ask the active tab's focused Terminal pane to re-grab keyboard focus. */
    fun requestTerminalFocus() {
        _terminalFocusEpoch.update { it + 1 }
    }

    // Broadcast ("synchronize-panes", Ctrl+Shift+B): tabIds currently
    // fanning commands/keystrokes to every connected Terminal pane they
    // contain. This SET is the single source of truth: no target list is
    // cached anywhere, both the keystroke relay ([relayUserInput]) and the
    // command fan-out ([sendToActiveTerminal]) re-derive their targets from
    // the live tab tree. Purely in-memory UI state, never persisted: see the
    // "── Broadcast ──" section below for the toggle and its lifecycle
    // hooks (closeSession / closeAllSessions / closePaneAt / handleSessionsGone).
    private val _broadcastTabIds = MutableStateFlow<Set<String>>(emptySet())
    val broadcastTabIds: StateFlow<Set<String>> = _broadcastTabIds.asStateFlow()

    init {
        // Watch the SSH session list. When a sessionId we still hold a
        // Connected reference to disappears (network blip → watchdog
        // timed out → DesktopSshTerminalSession.shutdown → removeSession),
        // transition the matching Terminal panes to Disconnected so the
        // ErrorView's Reconnect button shows up. Without this observer the
        // tab stays in Connected state forever and the user only sees a
        // frozen JediTerm.
        appScope.coroutineScope.launch {
            var previous = sshSessionManager.sessions.value.map { it.id }.toSet()
            sshSessionManager.sessions.collect { current ->
                val currentIds = current.map { it.id }.toSet()
                val gone = previous - currentIds
                if (gone.isNotEmpty()) handleSessionsGone(gone)
                previous = currentIds
            }
        }

        // Keep the custom-theme snapshot fresh so theme resolution sees the
        // latest user/synced themes without a suspending lookup.
        customThemeRepository?.let { repo ->
            appScope.coroutineScope.launch {
                repo.observeAll().collect { customThemesSnapshot = it }
            }
        }
    }

    /** Resolves a host's `terminalTheme` value against the current custom-theme snapshot. */
    private fun resolveThemeName(themeName: String): ResolvedTheme =
        resolveTheme(themeName, customThemesSnapshot)

    /**
     * `suspend` because the two user-facing messages below are resolved from
     * compose-resources ([getString]); they are read once up-front so the
     * non-suspend [MutableStateFlow.update] lambda stays pure. Called from the
     * `sessions.collect { }` block in [init], already a coroutine.
     *
     * No broadcast hook to clear here any more: the relay hook is permanent
     * per compose session and re-reads the live topology on every keystroke
     * (see [relayUserInput]), so a pane that just left [TerminalTabStatus.Connected]
     * drops out of both the relay target list and the badge at the same time.
     */
    private suspend fun handleSessionsGone(gone: Set<String>) {
        val lostMessage = getString(Res.string.reconnect_default_message_lost)
        val linkedClosedMessage = getString(Res.string.session_error_linked_terminal_closed)
        _tabs.update { tabs ->
            tabs.map { tab ->
                tab.copy(content = mapDeadSessions(tab.content, gone, lostMessage, linkedClosedMessage))
            }
        }
        // Any broadcasting tab that just lost a Connected pane may have
        // dropped below the "≥ 2 connected panes" floor, same guard as
        // closePaneAt. Re-read the post-update tabs so the pane count
        // reflects the Disconnected transition just applied above.
        _tabs.value.forEach { tab ->
            if (tab.tabId in _broadcastTabIds.value) {
                refreshOrDisableBroadcastAfterTopologyChange(tab.tabId, tab.content)
            }
        }
    }

    private fun mapDeadSessions(
        content: TabContent,
        gone: Set<String>,
        lostMessage: String,
        linkedClosedMessage: String,
    ): TabContent = when (content) {
        is TabContent.Terminal -> {
            val connected = content.status as? TerminalTabStatus.Connected
            if (connected != null && connected.sshSessionId in gone) {
                content.copy(status = TerminalTabStatus.Disconnected(lostMessage))
            } else content
        }
        is TabContent.Sftp -> {
            if (content.linkedSshSessionId in gone) {
                content.copy(state = SftpTabState.Error(linkedClosedMessage))
            } else content
        }
        is TabContent.Split -> content.copy(
            first = mapDeadSessions(content.first, gone, lostMessage, linkedClosedMessage),
            second = mapDeadSessions(content.second, gone, lostMessage, linkedClosedMessage),
        )
    }

    // ── Host key verification prompts ─────────────────────────────────────────
    //
    // SSHJ calls `HostKeyVerifier.verify` on the IO thread during the handshake.
    // We bridge to the Compose UI via these StateFlows: the IO thread blocks in
    // `runBlocking` until the user answers the Unknown prompt, and receives a
    // fire-and-forget notification for Mismatch (no user bypass).
    //
    // Serialised with [hostKeyPromptMutex] so two parallel connects don't collide
    // on a single prompt slot.

    private val _hostKeyPrompt = MutableStateFlow<HostKeyVerifyResult.Unknown?>(null)
    val hostKeyPrompt: StateFlow<HostKeyVerifyResult.Unknown?> = _hostKeyPrompt.asStateFlow()

    private val _hostKeyMismatch = MutableStateFlow<HostKeyVerifyResult.Mismatch?>(null)
    val hostKeyMismatch: StateFlow<HostKeyVerifyResult.Mismatch?> = _hostKeyMismatch.asStateFlow()

    @Volatile private var currentHostKeyDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    private val hostKeyPromptMutex = kotlinx.coroutines.sync.Mutex()

    init {
        knownHostsVerifier.unknownHostCallback = { unknown ->
            hostKeyPromptMutex.withLock {
                val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                currentHostKeyDeferred = deferred
                _hostKeyPrompt.value = unknown
                try {
                    deferred.await()
                } finally {
                    _hostKeyPrompt.value = null
                    currentHostKeyDeferred = null
                }
            }
        }
        knownHostsVerifier.mismatchListener = { mismatch ->
            _hostKeyMismatch.value = mismatch
        }
    }

    /** UI action: user approved the unknown host key. Resumes the pending connect. */
    fun acceptHostKey() {
        currentHostKeyDeferred?.complete(true)
    }

    /** UI action: user rejected the unknown host key. Connection aborts. */
    fun rejectHostKey() {
        currentHostKeyDeferred?.complete(false)
    }

    /** UI action: user acknowledged the mismatch alert. */
    fun dismissMismatchAlert() {
        _hostKeyMismatch.value = null
    }

    /**
     * Compose-native terminal sessions, keyed by SSH session id and living
     * for the whole session lifetime. They MUST outlive Compose tab switches:
     * otherwise the renderer's disposal would tear down the underlying
     * `jediterm-core` session and kill the shell mid-conversation.
     *
     * Sole terminal cache since the Swing `JediTermWidget` renderer was
     * removed: there used to be a parallel `widgets` cache keyed the same
     * way for users who hadn't opted into the (then-experimental) Compose
     * renderer; Compose is now the only renderer, so there is nothing left
     * to keep separate.
     */
    private val composeSessions = ConcurrentHashMap<String, fr.techtical.nextsh.desktop.sessions.compose.ComposeTerminalSession>()

    private fun disposeComposeSession(sessionId: String) {
        composeSessions.remove(sessionId)?.close()
    }

    fun getOrCreateComposeSession(
        sessionId: String,
        connector: TtyConnector,
        initialPalette: TerminalThemePalette,
        // Read once at session-creation time from DesktopSettingsStore by the
        // caller (TerminalScreen): the session's TechticalTerminalSettings
        // then holds this as its starting point. Live changes afterwards are
        // NOT pushed through this holder alone (see TechticalTerminalSettings
        // kdoc); TerminalScreen also feeds the composable directly.
        initialFontSize: Int = 14,
    ): fr.techtical.nextsh.desktop.sessions.compose.ComposeTerminalSession =
        composeSessions.getOrPut(sessionId) {
            val settings = TechticalTerminalSettings(initialPalette, initialFontSize)
            fr.techtical.nextsh.desktop.sessions.compose.ComposeTerminalSession(
                sessionId = sessionId,
                connector = connector,
                settings = settings,
            ).apply {
                // Broadcast relay, installed unconditionally and for the whole
                // session lifetime: see [relayUserInput] for why it is NOT
                // installed/removed as broadcast is toggled.
                onUserInput = { bytes -> relayUserInput(sessionId, bytes) }
                start()
            }
        }

    // ── Terminal tabs ─────────────────────────────────────────────────────────

    /**
     * TabIds for terminal tabs that must auto-split an SFTP pane alongside
     * the terminal once their SSH connection succeeds (see [openSession] /
     * [openSessionById] `thenOpenSftp`). Populated when the connect is
     * initiated, consumed (removed) in [onConnectResult] on Success (where
     * it drives a [splitPaneAsSftpAt] on the tab's own root pane rather than
     * opening a separate tab), also cleared on Error or on [closeSession] so
     * a flag never lingers to fire spuriously on a later reconnect of an
     * unrelated tab.
     */
    private val sftpAfterConnect = ConcurrentHashMap.newKeySet<String>()

    fun openSession(host: Host, thenOpenSftp: Boolean = false): String {
        val tabId = randomUuid()
        val theme = resolveThemeName(host.terminalTheme)
        val content = TabContent.Terminal(theme = theme, status = TerminalTabStatus.Connecting)
        _tabs.update { it + SessionTab(tabId, host, content) }
        _activeTabId.value = tabId
        if (thenOpenSftp) sftpAfterConnect.add(tabId)
        appScope.coroutineScope.launch { prepareConnection(tabId, host) }
        return tabId
    }

    /**
     * Convenience overload for UI callers that only hold a host id. The host
     * is resolved via [HostRepository.getById] on [AppScope]; if it's missing
     * nothing happens (the tab is silently dropped).
     */
    fun openSessionById(hostId: String, thenOpenSftp: Boolean = false) {
        appScope.coroutineScope.launch {
            val host = hostRepository.getById(hostId)
            if (host != null) openSession(host, thenOpenSftp)
        }
    }

    fun submitPassword(tabId: String, password: CharArray) {
        val tab = _tabs.value.firstOrNull { it.tabId == tabId }
        val status = (tab?.content as? TabContent.Terminal)?.status
        if (status !is TerminalTabStatus.AwaitingPassword) {
            Arrays.fill(password, '\u0000')
            return
        }
        updateTerminalStatus(tabId) { TerminalTabStatus.Connecting }
        appScope.coroutineScope.launch { connectWithPassword(tabId, tab.host, password) }
    }

    fun reconnect(tabId: String) {
        val tab = _tabs.value.firstOrNull { it.tabId == tabId } ?: return
        updateTerminalStatus(tabId) { TerminalTabStatus.Connecting }
        appScope.coroutineScope.launch { prepareConnection(tabId, tab.host) }
    }

    fun selectTab(tabId: String) {
        if (_tabs.value.any { it.tabId == tabId }) _activeTabId.value = tabId
    }

    // ── SFTP tabs ─────────────────────────────────────────────────────────────

    /**
     * Opens a new SFTP tab for [host] that piggybacks on the authenticated
     * [SSHClient][net.schmizz.sshj.SSHClient] of the terminal session
     * [linkedSshSessionId]. Returns the new tab id, or null if [linkedSshSessionId]
     * has no live SSH session.
     */
    fun openSftpTab(host: Host, linkedSshSessionId: String): String? {
        if (sshSessionManager.getTerminalSession(linkedSshSessionId) == null) return null
        val tabId = randomUuid()
        val content = TabContent.Sftp(linkedSshSessionId = linkedSshSessionId, state = SftpTabState.Opening)
        _tabs.update { it + SessionTab(tabId, host, content) }
        _activeTabId.value = tabId
        appScope.coroutineScope.launch { prepareSftpTab(tabId, linkedSshSessionId) }
        return tabId
    }

    private suspend fun prepareSftpTab(tabId: String, linkedSshSessionId: String) {
        when (val open = sftpManager.openSftp(linkedSshSessionId)) {
            is SshResult.Success -> {
                when (val home = sftpManager.getHomeDirectory(linkedSshSessionId)) {
                    is SshResult.Success -> updateSftpState(tabId) { SftpTabState.Open(home.data) }
                    is SshResult.Error -> updateSftpState(tabId) {
                        SftpTabState.Error(home.message.ifBlank { home.code.name })
                    }
                }
            }
            is SshResult.Error -> updateSftpState(tabId) {
                SftpTabState.Error(open.message.ifBlank { open.code.name })
            }
        }
    }

    /**
     * Update the cached cwd for an SFTP surface. [slot] is `null` for a
     * tab-level SFTP (single Sftp tab) or non-null for an SFTP **pane**
     * inside a Split: the update is routed to the right cell so that the
     * cwd is preserved if the user switches tabs and comes back.
     */
    /**
     * Update the cached cwd for an SFTP surface. [path] = empty for a tab-
     * level Sftp tab; non-empty addresses an SFTP pane inside one or more
     * Splits. The cwd is preserved across tab switches.
     */
    fun updateSftpCwdAt(tabId: String, path: List<PaneSlot>, cwd: String) {
        updateSftpStateAt(tabId, path) { SftpTabState.Open(cwd) }
    }

    /** Legacy: single-level cwd update by slot (null = tab root). */
    fun updateSftpCwd(tabId: String, cwd: String, slot: PaneSlot? = null) {
        updateSftpCwdAt(tabId, slot?.let { listOf(it) } ?: emptyList(), cwd)
    }

    // ── Split ─────────────────────────────────────────────────────────────────

    /**
     * Splits the pane at [path] inside [tabId] with a new Terminal pane for
     * [secondHost]. [path] = `emptyList()` targets the tab root; deeper paths
     * target nested panes (the pane at that path is wrapped in a sub-Split).
     *
     * Only Terminal panes in [TerminalTabStatus.Connected] are eligible. Caps
     * at [MAX_SPLIT_DEPTH]: refuses to create a sub-Split if [path] is at the
     * limit. Returns `false` if any precondition fails.
     */
    fun splitPaneAt(
        tabId: String,
        path: List<PaneSlot>,
        secondHost: Host,
        orientation: SplitOrientation = SplitOrientation.HORIZONTAL,
    ): Boolean {
        if (path.size >= MAX_SPLIT_DEPTH) return false
        val tab = _tabs.value.firstOrNull { it.tabId == tabId } ?: return false
        val target = paneAt(tab.content, path) ?: return false
        val terminal = target as? TabContent.Terminal ?: return false
        if (terminal.status !is TerminalTabStatus.Connected) return false
        val secondTheme = resolveThemeName(secondHost.terminalTheme)
        val newSecondPane = TabContent.Terminal(theme = secondTheme, status = TerminalTabStatus.Connecting)
        updateTab(tabId) {
            it.copy(content = updateAtPath(it.content, path) {
                TabContent.Split(
                    first = terminal,
                    second = newSecondPane,
                    orientation = orientation,
                    focusedSlot = PaneSlot.RIGHT_OR_BOTTOM,
                )
            })
        }
        // Mark the new Split's focusedSlot as the active slot at every level
        // so the caller's expectations (focus follows the latest action) hold.
        focusPathAfterSplit(tabId, path)
        val newPanePath = path + PaneSlot.RIGHT_OR_BOTTOM
        appScope.coroutineScope.launch { preparePaneConnectionAt(tabId, newPanePath, secondHost) }
        return true
    }

    /**
     * Like [splitPaneAt] but the new pane is an SFTP browser that piggybacks
     * on the terminal's existing SSHClient: no extra auth.
     */
    fun splitPaneAsSftpAt(
        tabId: String,
        path: List<PaneSlot>,
        orientation: SplitOrientation = SplitOrientation.HORIZONTAL,
    ): Boolean {
        if (path.size >= MAX_SPLIT_DEPTH) return false
        val tab = _tabs.value.firstOrNull { it.tabId == tabId } ?: return false
        val target = paneAt(tab.content, path) ?: return false
        val terminal = target as? TabContent.Terminal ?: return false
        val connected = terminal.status as? TerminalTabStatus.Connected ?: return false
        val sshSessionId = connected.sshSessionId
        val newSftpPane = TabContent.Sftp(linkedSshSessionId = sshSessionId, state = SftpTabState.Opening)
        updateTab(tabId) {
            it.copy(content = updateAtPath(it.content, path) {
                TabContent.Split(
                    first = terminal,
                    second = newSftpPane,
                    orientation = orientation,
                    focusedSlot = PaneSlot.RIGHT_OR_BOTTOM,
                )
            })
        }
        focusPathAfterSplit(tabId, path)
        val newPanePath = path + PaneSlot.RIGHT_OR_BOTTOM
        appScope.coroutineScope.launch { preparePaneSftpAt(tabId, newPanePath, sshSessionId) }
        return true
    }

    /** Convenience: split the root Terminal of [tabId] with [secondHost]. */
    fun splitTab(tabId: String, secondHost: Host): Boolean =
        splitPaneAt(tabId, emptyList(), secondHost)

    /** Convenience: split the root Terminal of [tabId] with an SFTP pane. */
    fun splitTabAsSftp(tabId: String): Boolean =
        splitPaneAsSftpAt(tabId, emptyList())

    /**
     * Toggle orientation of the Split that contains the focused pane at the
     * top level. (For nested splits, only the outermost Split rotates, same
     * UX as the legacy single-level toggle.)
     */
    fun toggleSplitOrientation(tabId: String) {
        updateSplit(tabId) {
            it.copy(orientation = if (it.orientation == SplitOrientation.HORIZONTAL) SplitOrientation.VERTICAL else SplitOrientation.HORIZONTAL)
        }
    }

    /**
     * Close the pane at [path] inside [tabId]. Promotes the surviving sibling
     * to the parent Split's slot, i.e. the pane at `path` disappears, its
     * sibling takes the place of their common parent.
     *
     * `path = []` closes the whole tab via [closeSession].
     */
    fun closePaneAt(tabId: String, path: List<PaneSlot>) {
        if (path.isEmpty()) { closeSession(tabId); return }
        val tab = _tabs.value.firstOrNull { it.tabId == tabId } ?: return
        val targetSlot = path.last()
        val parentPath = path.dropLast(1)
        val parent = paneAt(tab.content, parentPath) as? TabContent.Split ?: return
        val toRemove = parent.pane(targetSlot)
        val survivor = when (targetSlot) {
            PaneSlot.LEFT_OR_TOP -> parent.second
            PaneSlot.RIGHT_OR_BOTTOM -> parent.first
        }
        val newContent = updateAtPath(tab.content, parentPath) { survivor }
        // Dispose the removed pane, but keep any SSH sessions that still
        // appear in the surviving subtree (e.g. shared SSH for a Terminal +
        // SFTP split).
        val keep = sshSessionIdsIn(newContent)
        disposePane(toRemove, keepSshSessionIds = keep)
        updateTab(tabId) { it.copy(content = newContent) }
        // The tab may have dropped below 2 connected Terminal panes (or, if
        // still ≥ 2, the surviving panes' relay target list has shrunk):
        // refresh broadcast for this tab either way.
        refreshOrDisableBroadcastAfterTopologyChange(tabId, newContent)
    }

    /**
     * Legacy: collapse the root Split, keeping its [TabContent.Split.first].
     * Equivalent to `closePaneAt(tabId, listOf(PaneSlot.RIGHT_OR_BOTTOM))`.
     */
    fun closeSplit(tabId: String) {
        closePaneAt(tabId, listOf(PaneSlot.RIGHT_OR_BOTTOM))
    }

    /**
     * Update the focused slot at path [parentPath] (must be a Split). Used
     * when the user clicks on a pane to give it focus, and when a split is
     * created (focus follows the new pane).
     */
    fun setFocusAt(tabId: String, parentPath: List<PaneSlot>, slot: PaneSlot) {
        updateTab(tabId) {
            it.copy(content = updateAtPath(it.content, parentPath) { node ->
                (node as? TabContent.Split)?.copy(focusedSlot = slot) ?: node
            })
        }
    }

    /** Legacy single-level convenience. */
    fun setSplitFocus(tabId: String, slot: PaneSlot) {
        setFocusAt(tabId, emptyList(), slot)
    }

    /** Update the ratio of the Split at [parentPath]. */
    fun setRatioAt(tabId: String, parentPath: List<PaneSlot>, ratio: Float) {
        val clamped = ratio.coerceIn(0.20f, 0.80f)
        updateTab(tabId) {
            it.copy(content = updateAtPath(it.content, parentPath) { node ->
                (node as? TabContent.Split)?.copy(ratio = clamped) ?: node
            })
        }
    }

    /** Legacy single-level convenience. */
    fun setSplitRatio(tabId: String, ratio: Float) {
        setRatioAt(tabId, emptyList(), ratio)
    }

    /**
     * Apply the theme named [themeName] (a preset enum NAME or a custom theme
     * UUID) to the Terminal pane at [path]. The name is resolved against the
     * current custom-theme snapshot. No-op if the pane is not a Terminal.
     */
    fun setThemeAt(tabId: String, path: List<PaneSlot>, themeName: String) {
        val resolved = resolveThemeName(themeName)
        updateTab(tabId) {
            it.copy(content = updateAtPath(it.content, path) { node ->
                (node as? TabContent.Terminal)?.copy(theme = resolved) ?: node
            })
        }
    }

    /** Legacy: applies [themeName] to the root Terminal of [tabId]. */
    fun setTheme(tabId: String, themeName: String) {
        setThemeAt(tabId, emptyList(), themeName)
    }

    /**
     * Live-apply for custom theme edits. After a custom theme is created or edited
     * (via the Host detail screen or the in-session picker), the repo save is async:
     * the `observeAll()` collector that refreshes [customThemesSnapshot] may not have
     * emitted yet. Callers therefore hand us the up-to-date [themes] list directly so
     * we can:
     *  1. refresh the snapshot synchronously (no race with the observer), then
     *  2. re-resolve every OPEN Terminal pane whose currently-resolved theme id ==
     *     [editedThemeId] and swap in the freshly-edited palette.
     *
     * The Compose Canvas renderer picks up the new palette on the next paint because
     * [TerminalScreen] keys its palette-apply `LaunchedEffect` on the pane's
     * [ResolvedTheme]. A session currently showing theme X therefore re-renders the
     * instant the edit is saved: no quit / reassign / return required.
     */
    fun refreshCustomThemes(themes: List<CustomTerminalTheme>, editedThemeId: String) {
        customThemesSnapshot = themes
        _tabs.update { tabs ->
            tabs.map { tab -> tab.copy(content = reresolveTheme(tab.content, editedThemeId)) }
        }
    }

    /** Walk a content tree, re-resolving any Terminal pane bound to [themeId]. */
    private fun reresolveTheme(content: TabContent, themeId: String): TabContent = when (content) {
        is TabContent.Terminal ->
            if (content.theme.name == themeId) content.copy(theme = resolveThemeName(themeId)) else content
        is TabContent.Sftp -> content
        is TabContent.Split -> content.copy(
            first = reresolveTheme(content.first, themeId),
            second = reresolveTheme(content.second, themeId),
        )
    }


    // ── Close ─────────────────────────────────────────────────────────────────

    fun closeSession(tabId: String) {
        val tab = _tabs.value.firstOrNull { it.tabId == tabId } ?: return
        sftpAfterConnect.remove(tabId)
        disableBroadcast(tabId)
        disposePane(tab.content)
        val remaining = _tabs.value.filterNot { it.tabId == tabId }
        _tabs.value = remaining
        if (_activeTabId.value == tabId) {
            _activeTabId.value = remaining.lastOrNull()?.tabId
        }
    }

    // ── Tab navigation (Desktop keyboard shortcuts) ────────────────────────────

    /**
     * Activate the tab whose index is one after the active tab, wrapping from
     * the last tab back to the first. No-op if fewer than one tab is open.
     * Backs Ctrl+Tab.
     */
    fun selectNextTab() = cycleTab(+1)

    /**
     * Activate the tab whose index is one before the active tab, wrapping from
     * the first tab to the last. No-op if fewer than one tab is open.
     * Backs Ctrl+Shift+Tab.
     */
    fun selectPreviousTab() = cycleTab(-1)

    private fun cycleTab(direction: Int) {
        val current = _tabs.value
        if (current.isEmpty()) return
        val activeId = _activeTabId.value
        val activeIndex = current.indexOfFirst { it.tabId == activeId }
        // No active tab yet → land on the first (next) or last (previous).
        val baseIndex = if (activeIndex < 0) (if (direction > 0) -1 else 0) else activeIndex
        val nextIndex = ((baseIndex + direction) % current.size + current.size) % current.size
        _activeTabId.value = current[nextIndex].tabId
    }

    // ── Pane navigation (Desktop keyboard shortcuts) ───────────────────────────

    /**
     * Activate the TERMINAL pane visually AFTER the active tab's currently
     * focused pane, in DFS order (see [terminalPaneLeafPaths]), wrapping from
     * the last terminal back to the first. SFTP panes are skipped: the
     * shortcut cycles through terminals, and stopping on an SFTP browser used
     * to move the selection ring without moving keyboard focus (the next
     * keystroke then landed in the terminal the user had just left).
     *
     * No-op if there's no active tab, the active tab doesn't exist, it has no
     * Terminal pane at all (SFTP-only tab), or its only Terminal pane already
     * has focus. Bumps [terminalFocusEpoch] so the newly-focused Terminal pane
     * re-grabs keyboard focus. Backs Ctrl+Shift+N.
     */
    fun focusNextPane() = cyclePane(+1)

    /**
     * Activate the TERMINAL pane visually BEFORE the active tab's currently
     * focused pane, wrapping from the first terminal back to the last. Same
     * no-op guards and same SFTP skipping as [focusNextPane]. Backs Ctrl+Shift+P.
     */
    fun focusPreviousPane() = cyclePane(-1)

    private fun cyclePane(direction: Int) {
        val tabId = _activeTabId.value ?: return
        val tab = _tabs.value.firstOrNull { it.tabId == tabId } ?: return
        val leaves = terminalPaneLeafPaths(tab.content)
        if (leaves.isEmpty()) return
        // < 0 means focus currently sits on an SFTP pane (skipped by
        // [terminalPaneLeafPaths]): enter the terminal ring at its start
        // going forward, at its end going backward.
        val currentIndex = leaves.indexOf(focusedPath(tab.content))
        // Single terminal that already has focus → nothing to move to; return
        // WITHOUT bumping the epoch. (Single terminal NOT focused, e.g. a
        // Terminal | SFTP split focused on the SFTP side, must still move.)
        if (leaves.size == 1 && currentIndex == 0) return
        val nextIndex = if (currentIndex < 0) {
            if (direction > 0) 0 else leaves.size - 1
        } else {
            ((currentIndex + direction) % leaves.size + leaves.size) % leaves.size
        }
        // Every path reached here is non-empty: an empty path can only come
        // from a tab whose whole content is one Terminal, and that case is the
        // `leaves.size == 1 && currentIndex == 0` early return above.
        // [focusPathAfterSplit] applies it level by level, the same primitive
        // [splitPaneAt] uses to land focus on a fresh pane.
        focusPathAfterSplit(tabId, leaves[nextIndex])
        requestTerminalFocus()
    }

    fun closeAllSessions() {
        _tabs.value.forEach { tab -> disposePane(tab.content) }
        _tabs.value = emptyList()
        _activeTabId.value = null
        _broadcastTabIds.value = emptySet()
    }

    // ── Broadcast ("synchronize-panes", Ctrl+Shift+B) ──────────────────────────
    //
    // Fans commands (sendToActiveTerminal, e.g. the snippet picker) and live
    // keystrokes (ComposeTerminalSession.onUserInput) out to every connected
    // Terminal pane of the active tab, the desktop equivalent of tmux's
    // `synchronize-panes`. Scope is the active tab's own pane tree only; never
    // persisted (ephemeral UI state, reset on every relaunch and purged the
    // moment the topology it was watching changes underneath it).

    /**
     * Toggles broadcast on the active tab. No-op if there's no active tab or
     * fewer than two of its Terminal panes are Connected: broadcasting to a
     * single pane (or to none) is meaningless, so the shortcut simply does
     * nothing rather than turning "on" with an empty target list. Backs
     * Ctrl+Shift+B.
     */
    fun toggleBroadcastOnActiveTab() {
        val tabId = _activeTabId.value ?: return
        if (tabId in _broadcastTabIds.value) {
            disableBroadcast(tabId)
            return
        }
        val tab = _tabs.value.firstOrNull { it.tabId == tabId } ?: return
        if (connectedTerminalIdsIn(tab.content).size < 2) return
        _broadcastTabIds.update { it + tabId }
    }

    /**
     * Re-evaluates broadcast for [tabId] after its pane tree changed shape
     * ([closePaneAt] / [handleSessionsGone]): fewer than 2 connected Terminal
     * panes left → broadcasting no longer means anything, turn it off. No-op
     * if [tabId] isn't currently broadcasting.
     *
     * Nothing to "refresh" beyond that any more: the relay target list is no
     * longer cached anywhere, [relayUserInput] recomputes it from the live
     * tree on every keystroke.
     */
    private fun refreshOrDisableBroadcastAfterTopologyChange(tabId: String, newContent: TabContent) {
        if (tabId !in _broadcastTabIds.value) return
        if (connectedTerminalIdsIn(newContent).size < 2) disableBroadcast(tabId)
    }

    /** Removes [tabId] from the broadcast set. No-op if it isn't broadcasting. */
    private fun disableBroadcast(tabId: String) {
        _broadcastTabIds.update { it - tabId }
    }

    /**
     * Live-typing relay. Installed as [ComposeTerminalSession.onUserInput] on
     * EVERY compose session at creation time ([getOrCreateComposeSession]) and
     * never removed: it decides on each invocation, from the current tab
     * tree, whether [sourceSessionId] is part of a broadcasting tab and who
     * its siblings are.
     *
     * Why not install/uninstall the hook as broadcast is toggled: the previous
     * implementation captured the target list by value at toggle time, so any
     * pane that reached [TerminalTabStatus.Connected] AFTERWARDS (late connect,
     * reconnect, which mints a fresh sshSessionId, `splitPaneAt` /
     * `splitPaneAsSftpAt` on a broadcasting tab) showed the "Diffusion" badge
     * (which SessionTabsScreen derives from the live tree) while silently
     * receiving nothing, and the snippet fan-out ([sendToActiveTerminal],
     * also live) reached it. Two channels, two different truths. Reading the
     * topology here removes the cached list entirely, so badge, keystroke
     * relay and command fan-out can never disagree.
     *
     * Cost when nothing is broadcasting (the overwhelmingly common case) is
     * one `Set.isEmpty()` read per keystroke, before any tree walk.
     *
     * Relayed sends use `userInput = false`, which never re-triggers this hook
     * (anti-loop), so a fan-out that hits every target directly can safely use
     * `userInput = false` too without double delivery. A target with no mounted
     * compose session is silently skipped; a throwing target (mid-teardown)
     * doesn't block its siblings ([runCatching]).
     */
    private fun relayUserInput(sourceSessionId: String, bytes: ByteArray) {
        val broadcasting = _broadcastTabIds.value
        if (broadcasting.isEmpty()) return
        for (tab in _tabs.value) {
            if (tab.tabId !in broadcasting) continue
            val targets = connectedTerminalIdsIn(tab.content)
            if (sourceSessionId !in targets) continue
            targets.forEach { targetId ->
                if (targetId != sourceSessionId) {
                    runCatching { composeSessions[targetId]?.sendBytes(bytes, userInput = false) }
                }
            }
            // A session id belongs to exactly one tab: stop at the first hit.
            return
        }
    }

    /**
     * Walks [content] and tears down the terminal / SFTP resources it owns.
     * [keepSshSessionIds] holds SSH session ids that are still used elsewhere
     * (e.g. the kept side of a split shares an SSH with the discarded side):
     * those sessions are NOT disconnected, only the widget is torn down.
     * Called for both plain tab closures and split-pane disposals.
     */
    private fun disposePane(content: TabContent, keepSshSessionIds: Set<String> = emptySet()) {
        when (content) {
            is TabContent.Terminal -> {
                (content.status as? TerminalTabStatus.Connected)?.let { connected ->
                    disposeComposeSession(connected.sshSessionId)
                    if (connected.sshSessionId !in keepSshSessionIds) {
                        appScope.coroutineScope.launch {
                            // Close SFTP first so the SFTPClient releases its channel cleanly.
                            sftpManager.closeSftp(connected.sshSessionId)
                            sshSessionManager.disconnect(connected.sshSessionId)
                        }
                        // Any SFTP tabs linked to this session become orphans.
                        // Launched rather than applied inline because the
                        // message is resolved from compose-resources (suspend)
                        // and disposePane is called from non-suspend UI paths
                        // (closeSession / closePaneAt). The extra hop is
                        // invisible: the tab is torn down in the same frame,
                        // and the orphan flag only drives an error label.
                        appScope.coroutineScope.launch {
                            val linkedClosedMessage = getString(Res.string.session_error_linked_terminal_closed)
                            _tabs.update { list ->
                                list.map { t ->
                                    val sftp = t.content as? TabContent.Sftp ?: return@map t
                                    if (sftp.linkedSshSessionId == connected.sshSessionId) {
                                        t.copy(content = sftp.copy(state = SftpTabState.Error(linkedClosedMessage)))
                                    } else t
                                }
                            }
                        }
                    }
                }
            }
            is TabContent.Sftp -> {
                // No-op: the shared SSHClient is owned by the linked terminal.
            }
            is TabContent.Split -> {
                disposePane(content.first, keepSshSessionIds)
                disposePane(content.second, keepSshSessionIds)
            }
        }
    }

    /** Collect the SSH session ids referenced by [content] (for closeSplit). */
    private fun sshSessionIdsIn(content: TabContent): Set<String> = when (content) {
        is TabContent.Terminal -> (content.status as? TerminalTabStatus.Connected)
            ?.sshSessionId?.let { setOf(it) } ?: emptySet()
        is TabContent.Sftp -> setOf(content.linkedSshSessionId)
        is TabContent.Split -> sshSessionIdsIn(content.first) + sshSessionIdsIn(content.second)
    }

    /**
     * Resolves the [DesktopSshTerminalSession] for a given SSH session id.
     * Used by SFTP panes to read [DesktopSshTerminalSession.latencyMs] for
     * display in the PaneHeader without having to hold a direct reference.
     */
    fun terminalSessionFor(sshSessionId: String): DesktopSshTerminalSession? =
        sshSessionManager.getTerminalSession(sshSessionId)

    /**
     * Returns the [Host] associated with the given SSH session id. Used by the
     * PaneHeader for split panes: the second pane of a split connects to a
     * different host than the tab's primary host (`tab.host`), so reading
     * `tab.host` for both panes wrongly mirrors the first pane's identity.
     * Falls back to null if the session doesn't exist (yet): caller should
     * default to `tab.host` in that case.
     */
    fun hostForSshSession(sshSessionId: String): Host? =
        sshSessionManager.sessions.value.firstOrNull { it.id == sshSessionId }?.host

    /**
     * Number of LIVE SSH shells across every open tab: Terminal panes in
     * [TerminalTabStatus.Connected], counted through the same pure resolver
     * the broadcast feature uses ([connectedTerminalIdsIn]).
     *
     * This is what "N sessions SSH actives" must mean in the quit-confirmation
     * dialog. `tabs.value.size` (what that dialog used to count) is the
     * number of TABS, which over-counts an SFTP tab (it piggybacks on a
     * terminal's existing SSH client, it is not a session of its own) and a
     * tab left open on a Disconnected / Error / Connecting terminal (nothing
     * to lose), while under-counting a split tab running several shells.
     */
    fun connectedTerminalPaneCount(): Int =
        _tabs.value.sumOf { connectedTerminalIdsIn(it.content).size }

    /**
     * Returns the active tab's host, or null if no tab is open. Used by the
     * snippet picker to filter snippets by the host the user is currently
     * working with.
     */
    fun activeTabHost(): Host? {
        val tabId = _activeTabId.value ?: return null
        return _tabs.value.firstOrNull { it.tabId == tabId }?.host
    }

    /**
     * Returns the sshSessionId of the active tab's focused Terminal pane, or
     * null if no tab is open, the focused pane isn't a Terminal (SFTP), or
     * the SSH session isn't Connected (connecting / disconnected / error).
     * Shared by [sendToActiveTerminal] and the picker-close focus restore in
     * TerminalScreen (gates which split pane re-grabs keyboard focus).
     */
    fun activeFocusedTerminalSshSessionId(): String? {
        val tabId = _activeTabId.value ?: return null
        val tab = _tabs.value.firstOrNull { it.tabId == tabId } ?: return null
        return focusedConnectedTerminalId(tab.content)
    }

    /**
     * Writes [text] to a single session's compose terminal. Extracted from
     * [sendToActiveTerminal] so both the single-pane path and the broadcast
     * fan-out share one primitive. [userInput] defaults to `true` (matching
     * the pre-broadcast behaviour: "exactly as if the user had typed it");
     * the fan-out path passes `false` for every target since it is itself
     * the authoritative delivery (a `true` there would additionally trigger
     * [ComposeTerminalSession.onUserInput] on any target whose pane happens
     * to be hook-installed, i.e. every target here, double-delivering the
     * command to its siblings).
     */
    private fun sendToSession(sshSessionId: String, text: String, userInput: Boolean = true): Boolean {
        val compose = composeSessions[sshSessionId] ?: return false
        return runCatching { compose.sendString(text, userInput = userInput) }.isSuccess
    }

    /**
     * Writes [text] to the active tab's Terminal pane(s). No-op (returns
     * false) if no tab is open or there is nothing eligible to send to.
     * Used by the global snippet picker to inject the chosen command at the
     * user's prompt: the underlying [TtyConnector] passes the bytes
     * straight through to the remote shell(s), exactly as if the user had
     * typed them.
     *
     * Broadcast-aware: if the active tab is in broadcast mode ([broadcastTabIds]),
     * fans [text] out to every connected Terminal pane of the tab (one
     * failing target, e.g. a mid-teardown session, doesn't block the
     * others, via [runCatching] inside [sendToSession]). Otherwise behaves
     * exactly as before: only the focused Terminal pane, and only if it is
     * Connected.
     */
    fun sendToActiveTerminal(text: String): Boolean {
        val tabId = _activeTabId.value ?: return false
        val tab = _tabs.value.firstOrNull { it.tabId == tabId } ?: return false
        if (tabId in _broadcastTabIds.value) {
            val targets = connectedTerminalIdsIn(tab.content)
            if (targets.isEmpty()) return false
            return targets.map { sendToSession(it, text, userInput = false) }.any { it }
        }
        val sshSessionId = focusedConnectedTerminalId(tab.content) ?: return false
        return sendToSession(sshSessionId, text)
    }

    // ── Path helpers ──────────────────────────────────────────────────────────
    //
    // A pane is identified by its [path]: a [List<PaneSlot>] that walks down
    // the tab content tree from the root. `[]` = the tab content itself,
    // `[LEFT_OR_TOP]` = pane.first of the root Split, etc. This generalises
    // the single-level `slot: PaneSlot?` so the manager can address any pane
    // including those inside nested splits.

    /** Returns the pane at [path] inside [content], or null if it doesn't resolve. */
    private fun paneAt(content: TabContent, path: List<PaneSlot>): TabContent? {
        if (path.isEmpty()) return content
        val split = content as? TabContent.Split ?: return null
        return paneAt(split.pane(path.first()), path.drop(1))
    }

    /**
     * Returns a new content tree where the pane at [path] is replaced by
     * `transform(currentPane)`. Identity if path doesn't resolve.
     */
    private fun updateAtPath(
        content: TabContent,
        path: List<PaneSlot>,
        transform: (TabContent) -> TabContent,
    ): TabContent {
        if (path.isEmpty()) return transform(content)
        val split = content as? TabContent.Split ?: return content
        val head = path.first()
        val tail = path.drop(1)
        val updatedChild = updateAtPath(split.pane(head), tail, transform)
        return split.withPane(head, updatedChild)
    }

    /**
     * Returns the path from the root of [content] down to the deepest focused
     * pane. Each Split node contributes its `focusedSlot`. Empty for a leaf.
     */
    fun focusedPath(content: TabContent): List<PaneSlot> = when (content) {
        is TabContent.Terminal, is TabContent.Sftp -> emptyList()
        is TabContent.Split -> listOf(content.focusedSlot) + focusedPath(content.pane(content.focusedSlot))
    }

    /**
     * After [splitPaneAt] / [splitPaneAsSftpAt] creates a new Split at [path],
     * mark every Split on the way to that new sub-Split as focused on the
     * slot leading to it: so the focused path lands on the freshly created
     * RIGHT_OR_BOTTOM pane.
     */
    private fun focusPathAfterSplit(tabId: String, path: List<PaneSlot>) {
        if (path.isEmpty()) return
        for (i in 1..path.size) {
            val parentPath = path.take(i - 1)
            val slot = path[i - 1]
            setFocusAt(tabId, parentPath, slot)
        }
    }

    // ── Pane-aware password / reconnect API ───────────────────────────────────

    /**
     * Submit a password for the Terminal pane at [path] that is currently in
     * [TerminalTabStatus.AwaitingPassword]. No-op if preconditions don't hold.
     */
    fun submitPasswordAt(tabId: String, path: List<PaneSlot>, password: CharArray) {
        val tab = _tabs.value.firstOrNull { it.tabId == tabId } ?: run {
            Arrays.fill(password, Char(0)); return
        }
        val pane = paneAt(tab.content, path) as? TabContent.Terminal ?: run {
            Arrays.fill(password, Char(0)); return
        }
        if (pane.status !is TerminalTabStatus.AwaitingPassword) {
            Arrays.fill(password, Char(0)); return
        }
        updateTerminalStatusAt(tabId, path) { TerminalTabStatus.Connecting }
        appScope.coroutineScope.launch { connectPaneWithPasswordAt(tabId, path, tab.host, password) }
    }

    /** Reconnect the Terminal pane at [path]. */
    fun reconnectAt(tabId: String, path: List<PaneSlot>) {
        val tab = _tabs.value.firstOrNull { it.tabId == tabId } ?: return
        updateTerminalStatusAt(tabId, path) { TerminalTabStatus.Connecting }
        appScope.coroutineScope.launch { preparePaneConnectionAt(tabId, path, tab.host) }
    }

    /** Legacy single-level convenience used by the root tab APIs. */
    fun submitPaneAwaitingPassword(tabId: String, slot: PaneSlot, password: CharArray) {
        submitPasswordAt(tabId, listOf(slot), password)
    }

    /** Legacy single-level convenience. */
    fun reconnectPane(tabId: String, slot: PaneSlot) {
        reconnectAt(tabId, listOf(slot))
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Marks the tab's connect attempt as failed with [message].
     *
     * Also purges the [sftpAfterConnect] flag: these are the pre-flight
     * failures (credential missing in the vault, FIDO2 key unusable, auth type
     * unsupported on Desktop) that abort BEFORE [onConnectResult] is reached,
     * so nothing else would clear the flag, and a later manual reconnect of
     * the same tab would then silently pop open an SFTP tab the user never
     * asked for.
     */
    private fun failConnection(tabId: String, message: String) {
        sftpAfterConnect.remove(tabId)
        updateTerminalStatus(tabId) { TerminalTabStatus.Error(message) }
    }

    private suspend fun prepareConnection(tabId: String, host: Host) {
        when (host.authType) {
            AuthType.PASSWORD -> {
                val stored = vaultManager.getPassword(host.credentialId)
                if (stored != null) connectWithPassword(tabId, host, stored)
                // NOT a failure: the flow resumes through submitPassword ->
                // connectWithPassword -> onConnectResult, which is where the
                // sftpAfterConnect flag is legitimately consumed. Keep it.
                else updateTerminalStatus(tabId) { TerminalTabStatus.AwaitingPassword(host) }
            }
            AuthType.SSH_KEY -> {
                val pem = vaultManager.getPrivateKey(host.credentialId)
                if (pem == null) {
                    failConnection(tabId, getString(Res.string.session_error_ssh_key_missing, host.label))
                    return
                }
                val passphrase = vaultManager.getKeyPassphrase(host.credentialId)
                connectWithKey(tabId, host, pem, passphrase)
            }
            AuthType.CERTIFICATE -> {
                val pem = vaultManager.getPrivateKey(host.credentialId)
                if (pem == null) {
                    failConnection(tabId, getString(Res.string.session_error_private_key_missing, host.label))
                    return
                }
                val cert = vaultManager.getCertificate(host.credentialId)
                if (cert == null) {
                    failConnection(tabId, getString(Res.string.session_error_certificate_missing, host.label))
                    return
                }
                val passphrase = vaultManager.getKeyPassphrase(host.credentialId)
                connectWithCertificate(tabId, host, pem, cert, passphrase)
            }
            AuthType.FIDO2 -> {
                // Récupérer la SshKey SK-* depuis la BDD
                val keyRepo = sshKeyRepository
                if (keyRepo == null) {
                    failConnection(tabId, getString(Res.string.session_error_key_repository_unavailable))
                    return
                }
                val skKey = keyRepo.getById(host.credentialId)
                if (skKey == null || (skKey.keyType != SshKeyType.SK_ED25519 && skKey.keyType != SshKeyType.SK_ECDSA_256)) {
                    failConnection(tabId, getString(Res.string.session_error_fido2_key_invalid, host.label))
                    return
                }
                onConnectResult(tabId, host, sshSessionManager.connectWithFido2(host, skKey))
            }
            AuthType.BIOMETRIC_KEY -> {
                failConnection(tabId, getString(Res.string.session_error_biometric_unsupported))
            }
        }
    }

    private suspend fun connectWithPassword(tabId: String, host: Host, password: CharArray) {
        onConnectResult(tabId, host, sshSessionManager.connectWithPassword(host, password))
    }

    private suspend fun connectWithKey(tabId: String, host: Host, pem: String, passphrase: CharArray?) {
        onConnectResult(tabId, host, sshSessionManager.connectWithKey(host, pem, passphrase))
    }

    private suspend fun connectWithCertificate(
        tabId: String, host: Host, pem: String, cert: String, passphrase: CharArray?,
    ) {
        onConnectResult(tabId, host, sshSessionManager.connectWithCertificate(host, pem, cert, passphrase))
    }

    private suspend fun onConnectResult(tabId: String, host: Host, result: SshResult<SshSession>) {
        when (result) {
            is SshResult.Success -> {
                val terminal = sshSessionManager.getTerminalSession(result.data.id)
                if (terminal == null) {
                    failConnection(tabId, getString(Res.string.session_error_ssh_session_missing))
                    return
                }
                updateTab(tabId) { tab ->
                    val term = tab.content as? TabContent.Terminal ?: return@updateTab tab
                    tab.copy(
                        content = term.copy(status = TerminalTabStatus.Connected(result.data.id, terminal)),
                        connectedAt = System.currentTimeMillis(),
                    )
                }
                hostRepository.updateLastConnected(host.id)
                // Deferred SFTP-after-connect (host list's SFTP button): the
                // terminal is now Connected non-null, so splitPaneAsSftpAt's
                // precondition (root pane is a Terminal in Connected status)
                // is satisfied. Split the freshly-connected tab's root pane
                // Terminal|SFTP side by side instead of opening a separate
                // tab: the tab stays active, and the Split's focusedSlot
                // (RIGHT_OR_BOTTOM, set by splitPaneAsSftpAt) naturally lands
                // focus on the new SFTP pane.
                if (sftpAfterConnect.remove(tabId)) {
                    val splitOk = splitPaneAsSftpAt(tabId, emptyList())
                    if (!splitOk) {
                        // Precondition unmet (root pane not a Connected
                        // Terminal): not reachable today since we just set
                        // it to Connected above in this same branch, but the
                        // Boolean return is captured explicitly rather than
                        // silently ignored. No retry: the deferred SFTP
                        // intent is simply abandoned and the tab stays open
                        // as a plain terminal.
                    }
                }
            }
            is SshResult.Error -> {
                // No orphan flag left behind: a later manual reconnect of
                // this tab must not silently pop open an SFTP tab.
                sftpAfterConnect.remove(tabId)
                updateTerminalStatus(tabId) {
                    TerminalTabStatus.Error(result.message.ifBlank { result.code.name })
                }
            }
        }
    }

    private fun updateTab(tabId: String, transform: (SessionTab) -> SessionTab) {
        _tabs.update { list -> list.map { if (it.tabId == tabId) transform(it) else it } }
    }

    private fun updateTerminalStatus(tabId: String, transform: (TerminalTabStatus) -> TerminalTabStatus) {
        updateTab(tabId) { tab ->
            val term = tab.content as? TabContent.Terminal ?: return@updateTab tab
            tab.copy(content = term.copy(status = transform(term.status)))
        }
    }

    private fun updateSftpState(tabId: String, transform: (SftpTabState) -> SftpTabState) {
        updateTab(tabId) { tab ->
            val sftp = tab.content as? TabContent.Sftp ?: return@updateTab tab
            tab.copy(content = sftp.copy(state = transform(sftp.state)))
        }
    }

    private fun updateSplit(tabId: String, transform: (TabContent.Split) -> TabContent.Split) {
        updateTab(tabId) { tab ->
            val split = tab.content as? TabContent.Split ?: return@updateTab tab
            tab.copy(content = transform(split))
        }
    }

    /**
     * Pane-aware terminal status update. Replaces [updateSplitPaneTerminalStatus]
     * by routing through [updateAtPath]: supports any nesting depth.
     */
    private fun updateTerminalStatusAt(
        tabId: String,
        path: List<PaneSlot>,
        transform: (TerminalTabStatus) -> TerminalTabStatus,
    ) {
        updateTab(tabId) {
            it.copy(content = updateAtPath(it.content, path) { node ->
                (node as? TabContent.Terminal)?.let { t -> t.copy(status = transform(t.status)) } ?: node
            })
        }
    }

    private fun updateSftpStateAt(
        tabId: String,
        path: List<PaneSlot>,
        transform: (SftpTabState) -> SftpTabState,
    ) {
        updateTab(tabId) {
            it.copy(content = updateAtPath(it.content, path) { node ->
                (node as? TabContent.Sftp)?.let { s -> s.copy(state = transform(s.state)) } ?: node
            })
        }
    }

    private suspend fun preparePaneSftpAt(tabId: String, path: List<PaneSlot>, sshSessionId: String) {
        when (val open = sftpManager.openSftp(sshSessionId)) {
            is SshResult.Success -> {
                when (val home = sftpManager.getHomeDirectory(sshSessionId)) {
                    is SshResult.Success -> updateSftpStateAt(tabId, path) { SftpTabState.Open(home.data) }
                    is SshResult.Error -> updateSftpStateAt(tabId, path) {
                        SftpTabState.Error(home.message.ifBlank { home.code.name })
                    }
                }
            }
            is SshResult.Error -> updateSftpStateAt(tabId, path) {
                SftpTabState.Error(open.message.ifBlank { open.code.name })
            }
        }
    }

    /**
     * Pane-level equivalent of [prepareConnection]. Routes status updates
     * through the [path] so the result lands on the right pane (any depth).
     */
    private suspend fun preparePaneConnectionAt(tabId: String, path: List<PaneSlot>, host: Host) {
        when (host.authType) {
            AuthType.PASSWORD -> {
                val stored = vaultManager.getPassword(host.credentialId)
                if (stored != null) connectPaneWithPasswordAt(tabId, path, host, stored)
                else updateTerminalStatusAt(tabId, path) { TerminalTabStatus.AwaitingPassword(host) }
            }
            AuthType.SSH_KEY -> {
                val pem = vaultManager.getPrivateKey(host.credentialId)
                if (pem == null) {
                    failPaneConnection(tabId, path, getString(Res.string.session_error_ssh_key_missing, host.label))
                    return
                }
                val passphrase = vaultManager.getKeyPassphrase(host.credentialId)
                onPaneConnectResultAt(tabId, path, host, sshSessionManager.connectWithKey(host, pem, passphrase))
            }
            AuthType.CERTIFICATE -> {
                val pem = vaultManager.getPrivateKey(host.credentialId)
                val cert = vaultManager.getCertificate(host.credentialId)
                if (pem == null || cert == null) {
                    failPaneConnection(
                        tabId, path,
                        getString(Res.string.session_error_key_or_certificate_missing, host.label),
                    )
                    return
                }
                val passphrase = vaultManager.getKeyPassphrase(host.credentialId)
                onPaneConnectResultAt(tabId, path, host, sshSessionManager.connectWithCertificate(host, pem, cert, passphrase))
            }
            AuthType.FIDO2 -> {
                val keyRepo = sshKeyRepository
                if (keyRepo == null) {
                    failPaneConnection(tabId, path, getString(Res.string.session_error_key_repository_unavailable))
                    return
                }
                val skKey = keyRepo.getById(host.credentialId)
                if (skKey == null || (skKey.keyType != SshKeyType.SK_ED25519 && skKey.keyType != SshKeyType.SK_ECDSA_256)) {
                    failPaneConnection(tabId, path, getString(Res.string.session_error_fido2_key_invalid, host.label))
                    return
                }
                onPaneConnectResultAt(tabId, path, host, sshSessionManager.connectWithFido2(host, skKey))
            }
            AuthType.BIOMETRIC_KEY -> {
                failPaneConnection(tabId, path, getString(Res.string.session_error_biometric_unsupported))
            }
        }
    }

    /**
     * Pane-level counterpart of [failConnection]. No [sftpAfterConnect] purge
     * here on purpose: that flag is tab-scoped and only ever set by
     * [openSession] / [openSessionById] for a tab's ROOT terminal: a split
     * pane never carries one.
     */
    private fun failPaneConnection(tabId: String, path: List<PaneSlot>, message: String) {
        updateTerminalStatusAt(tabId, path) { TerminalTabStatus.Error(message) }
    }

    private suspend fun connectPaneWithPasswordAt(tabId: String, path: List<PaneSlot>, host: Host, password: CharArray) {
        onPaneConnectResultAt(tabId, path, host, sshSessionManager.connectWithPassword(host, password))
    }

    private suspend fun onPaneConnectResultAt(
        tabId: String, path: List<PaneSlot>, host: Host, result: SshResult<SshSession>,
    ) {
        when (result) {
            is SshResult.Success -> {
                val terminal = sshSessionManager.getTerminalSession(result.data.id)
                if (terminal == null) {
                    failPaneConnection(tabId, path, getString(Res.string.session_error_ssh_session_missing))
                    return
                }
                updateTerminalStatusAt(tabId, path) {
                    TerminalTabStatus.Connected(result.data.id, terminal)
                }
                hostRepository.updateLastConnected(host.id)
            }
            is SshResult.Error -> {
                updateTerminalStatusAt(tabId, path) {
                    TerminalTabStatus.Error(result.message.ifBlank { result.code.name })
                }
            }
        }
    }
}
