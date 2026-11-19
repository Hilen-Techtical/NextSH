// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.annotation.VisibleForTesting
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.techtical.nextsh.core.logging.TermuxLog
import fr.techtical.nextsh.core.ssh.SshSessionManager
import fr.techtical.nextsh.core.ssh.SshTerminalSession
import fr.techtical.nextsh.core.vault.VaultWipeGate
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SessionStatus
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.shared.util.LogId
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Un onglet terminal : un shell ouvert sur la session SSH [sessionId], pour l'hôte [host].
 *
 * L'onglet tient exactement une référence sur sa session SSH (voir la doc de `SshSessionManager`),
 * qu'il rend à sa fermeture, par [TerminalTabStore] et par lui seul.
 */
data class SessionTab(
    val sessionId: String,
    val host: Host,
    val terminalSession: SshTerminalSession,
    val status: SessionStatus = SessionStatus.CONNECTING,
)

/**
 * La part de l'état du terminal qui survit à l'écran : les onglets, celui qui est affiché et le
 * split. Ce qui est lié à l'écran (connexion en cours, erreur à montrer) reste dans
 * [SessionViewModel].
 */
data class TerminalTabsState(
    val tabs: List<SessionTab> = emptyList(),
    val activeTabIndex: Int = 0,
    val splitState: SplitState? = null,
)

/**
 * Le propriétaire des onglets terminal, tenu hors écran (un singleton d'app, comme
 * `BrowserSessionHolder`).
 *
 * Les onglets appartenaient au `SessionViewModel`, donc à l'entrée de navigation du terminal :
 * quitter l'écran les fermait tous. Un onglet ne se ferme désormais que sur une action explicite de
 * l'utilisateur, sa croix ([closeTab]) ou « Tout fermer » dans la notification du service au
 * premier plan ([closeAll]). Quitter l'écran ou l'application ne ferme rien : c'est le service qui
 * garde le processus en vie tant qu'un onglet est connecté (voir `TerminalSessionService`).
 *
 * Références SSH. Chaque onglet tient exactement une référence sur sa session. Elle lui est
 * transmise à l'ajout ([addTab]) ou au remplacement d'un onglet ([replaceTab], la reconnexion) :
 * celui qui ouvre l'onglet cesse d'en être responsable, et le store la rend une seule fois, à la
 * fermeture, après avoir fermé le shell. Un onglet n'est retiré de l'état qu'une fois, de façon
 * atomique, donc ni deux fermetures concurrentes ni une fermeture puis un « Tout fermer » ne
 * rendent deux fois la même référence.
 *
 * L'hôte d'un onglet suit les modifications faites dans la liste d'hôtes (libellé, thème...) : un
 * onglet vit des heures, son hôte ne peut pas rester l'instantané du jour de l'ouverture, voir
 * [startObservingHosts].
 *
 * Les mutations passent par [MutableStateFlow.update] : le callback de fin de shell arrive depuis
 * le Handler principal de Termux, la fermeture depuis l'interface ou depuis la notification.
 */
@Singleton
class TerminalTabStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sshSessionManager: SshSessionManager,
    private val settingsDataStore: SettingsDataStore,
    private val hostRepository: HostRepository,
    /** The wipe of the vault: while the app is closed no tab is added, see [addTab]. */
    private val wipeGate: VaultWipeGate,
) {

    /** Without the wipe gate of the app (tests). Not an injection point. */
    constructor(
        context: Context,
        sshSessionManager: SshSessionManager,
        settingsDataStore: SettingsDataStore,
        hostRepository: HostRepository,
    ) : this(context, sshSessionManager, settingsDataStore, hostRepository, VaultWipeGate())

    private val _state = MutableStateFlow(TerminalTabsState())
    val state: StateFlow<TerminalTabsState> = _state.asStateFlow()

    private val clipboardManager by lazy {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    private var clipboardClearJob: Job? = null

    /**
     * Scope de l'effacement différé du presse-papier. Il appartient à l'app et non à un écran :
     * quitter le terminal ne doit pas laisser un texte copié dans le presse-papier au-delà du délai
     * choisi dans les réglages. Remplaçable dans les tests.
     */
    @VisibleForTesting
    internal var clipboardScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Scope de la fermeture des onglets : le shell et la session SSH se ferment sur le réseau, donc
     * hors du fil principal, et jusqu'au bout quelle que soit la vie de l'écran. Remplaçable dans
     * les tests.
     */
    @VisibleForTesting
    internal var cleanupScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Scope de l'observation des hôtes ([startObservingHosts]) : celui de l'app, comme les onglets
     * eux-mêmes. Remplaçable dans les tests.
     */
    @VisibleForTesting
    internal var hostScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var hostObservation: Job? = null

    // ── Lecture ───────────────────────────────────────────────────────────────────

    /** Vrai si un onglet est déjà ouvert sur la session [sessionId]. */
    fun hasSession(sessionId: String): Boolean = _state.value.tabs.any { it.sessionId == sessionId }

    /**
     * Returns the terminal session of the currently focused pane.
     * In single-pane mode, returns the active tab's session.
     * Returns null if the focused pane is SFTP (SFTP does not receive terminal keys).
     */
    val focusedSession: SshTerminalSession?
        get() {
            val state = _state.value
            val split = state.splitState ?: return state.tabs.getOrNull(state.activeTabIndex)?.terminalSession
            val content = when (split.focusedPane) {
                PaneSlot.LEFT_OR_TOP -> split.leftOrTopPane
                PaneSlot.RIGHT_OR_BOTTOM -> split.rightOrBottomPane
            }
            return when (content) {
                is PaneContent.Terminal -> state.tabs.getOrNull(content.tabIndex)?.terminalSession
                is PaneContent.Sftp -> null
            }
        }

    // ── Onglets ───────────────────────────────────────────────────────────────────

    /**
     * Ajoute [tab], qui devient l'onglet actif, et en devient propriétaire : la référence que
     * l'appelant tenait sur `tab.sessionId` est celle de l'onglet, l'appelant ne la rend plus.
     * L'appelant s'assure qu'aucun onglet n'existe déjà pour cette session ([hasSession]).
     *
     * Le split est quitté : il survit à la sortie de l'écran, et l'onglet ajouté, actif, ne serait
     * dans aucun des deux panneaux, donc invisible.
     */
    fun addTab(tab: SessionTab) {
        // The wipe of the vault has the app closed: a connection that ended just now would leave a tab that
        // outlives the erase. The tab is closed as if it had been added and closed (its shell, then its
        // reference), so the one who opened it has nothing to release.
        if (wipeGate.isClosed()) {
            closeInBackground(listOf(tab))
            return
        }
        _state.update { state ->
            val newTabs = state.tabs + tab
            state.copy(tabs = newTabs, activeTabIndex = newTabs.lastIndex, splitState = null)
        }
    }

    /**
     * Remplace, à sa place, l'onglet de la session [oldSessionId] par [newTab] : la reconnexion d'un
     * onglet dont le shell est tombé. L'onglet garde son rang, l'onglet actif et le split ne
     * bougent pas.
     *
     * Comme [addTab], [newTab] devient propriétaire de la référence que l'appelant tenait sur sa
     * session. L'ancien onglet est fermé comme par [closeTab] : son shell, puis sa référence, rendue
     * une seule fois. Le remplacement est atomique, donc aucun état intermédiaire sans l'onglet.
     *
     * Un panneau SFTP du split qui emprunte la session de l'ancien onglet ([PaneContent.Sftp])
     * suit l'onglet : il pointe sur la session de [newTab], dans la même mise à jour. Sinon il
     * resterait branché sur la session morte, que la reconnexion de l'onglet ne ressuscite pas.
     *
     * Retourne faux, sans rien changer, si l'ancien onglet n'existe plus (l'utilisateur l'a fermé
     * pendant la reconnexion) : [newTab] n'a alors pas de place, l'appelant rend sa référence.
     */
    fun replaceTab(oldSessionId: String, newTab: SessionTab): Boolean {
        // Même schéma que closeTab : l'onglet remplacé est relevé dans la mise à jour même.
        var replaced: SessionTab? = null
        _state.update { state ->
            val index = state.tabs.indexOfFirst { it.sessionId == oldSessionId }
            replaced = state.tabs.getOrNull(index)
            if (replaced == null) return@update state
            state.copy(
                tabs = state.tabs.toMutableList().also { it[index] = newTab },
                splitState = state.splitState?.withSftpOnSession(oldSessionId, newTab.sessionId),
            )
        }
        return replaced?.let { closeInBackground(listOf(it)); true } ?: false
    }

    /**
     * Ce split avec, pour chaque panneau SFTP branché sur [oldSessionId], la session [newSessionId].
     * Le libellé d'hôte du panneau ne change pas : la reconnexion garde le même hôte. Le split est
     * rendu tel quel (la même instance) quand aucun panneau n'est concerné.
     */
    private fun SplitState.withSftpOnSession(oldSessionId: String, newSessionId: String): SplitState {
        fun follow(content: PaneContent): PaneContent =
            if (content is PaneContent.Sftp && content.sessionId == oldSessionId) {
                content.copy(sessionId = newSessionId)
            } else {
                content
            }
        val left = follow(leftOrTopPane)
        val right = follow(rightOrBottomPane)
        return if (left === leftOrTopPane && right === rightOrBottomPane) {
            this
        } else {
            copy(leftOrTopPane = left, rightOrBottomPane = right)
        }
    }

    /**
     * Ferme [tab], un onglet construit que le store n'a pas pris (la reconnexion dont l'onglet à
     * remplacer a été fermé entre-temps, voir [replaceTab]) : son shell, puis la référence qu'il
     * tient, une seule fois et hors du fil principal, comme [closeTab]. Rendre la seule référence
     * laisserait le shell ouvert si un autre utilisateur de la session la gardait vivante.
     */
    fun discard(tab: SessionTab) = closeInBackground(listOf(tab))

    /** Sélectionne l'onglet actif. */
    fun selectTab(index: Int) {
        _state.update { state ->
            if (index in state.tabs.indices) state.copy(activeTabIndex = index)
            else state
        }
    }

    /**
     * Affiche un onglet déjà ouvert sur l'hôte [hostId]. Retourne faux, sans rien changer, s'il n'y
     * en a aucun : l'appelant ouvre alors une connexion.
     *
     * Les onglets connectés de l'hôte passent avant les autres : la pastille de la liste d'hôtes dit
     * « connecté » dès qu'un l'est, le toucher ne doit pas atterrir sur un onglet tombé. L'onglet
     * actif est gardé s'il est de ceux-là, sinon le premier est choisi. En split, un panneau qui
     * montre déjà l'un d'eux prend le focus ; sinon le split est quitté, pour que l'onglet demandé
     * soit à l'écran et non caché derrière deux autres hôtes.
     */
    fun selectTabOfHost(hostId: String): Boolean {
        var found = false
        _state.update { state ->
            val ofHost = state.tabs.indices.filter { state.tabs[it].host.id == hostId }
            found = ofHost.isNotEmpty()
            if (!found) return@update state
            val matches = ofHost.filter { state.tabs[it].status == SessionStatus.CONNECTED }.ifEmpty { ofHost }

            val split = state.splitState
            if (split != null) {
                val shownSlot = listOf(
                    PaneSlot.LEFT_OR_TOP to split.leftOrTopPane,
                    PaneSlot.RIGHT_OR_BOTTOM to split.rightOrBottomPane,
                ).firstOrNull { (_, content) -> content is PaneContent.Terminal && content.tabIndex in matches }?.first
                if (shownSlot != null) {
                    return@update state.copy(splitState = split.copy(focusedPane = shownSlot))
                }
            }
            val index = if (state.activeTabIndex in matches) state.activeTabIndex else matches.first()
            state.copy(activeTabIndex = index, splitState = null)
        }
        return found
    }

    /**
     * Ferme un onglet : son shell, puis la référence qu'il tient sur la session. La session ne se
     * ferme qu'avec sa dernière référence : un tunnel, un explorateur SFTP ou un transfert qui
     * l'utilisent la gardent ouverte.
     *
     * L'onglet affiché reste le même quand l'onglet fermé le précède : il glisse d'un rang, l'index
     * actif aussi. Quand c'est l'onglet affiché qui est fermé, c'est celui qui prend sa place.
     */
    fun closeTab(index: Int) {
        // L'onglet retiré est relevé dans la mise à jour même, avec l'état qu'elle a vu : un index
        // lu avant, puis réappliqué après, pourrait viser un autre onglet si la liste a bougé entre-temps.
        var removed: SessionTab? = null
        _state.update { state ->
            val tab = state.tabs.getOrNull(index)
            removed = tab
            if (tab == null) return@update state

            val newTabs = state.tabs.toMutableList().also { it.removeAt(index) }
            val newActiveIndex = when {
                newTabs.isEmpty() -> 0
                // L'onglet affiché ne change pas parce qu'un onglet avant lui disparaît : il glisse d'un rang.
                index < state.activeTabIndex -> state.activeTabIndex - 1
                state.activeTabIndex >= newTabs.size -> newTabs.lastIndex
                else -> state.activeTabIndex
            }

            // Exit split if the closed tab was in a pane, and adjust tab indices
            val newSplitState = state.splitState?.let { split ->
                val leftContent = split.leftOrTopPane
                val rightContent = split.rightOrBottomPane
                val closedInLeft = leftContent is PaneContent.Terminal && leftContent.tabIndex == index
                val closedInRight = rightContent is PaneContent.Terminal && rightContent.tabIndex == index
                if (closedInLeft || closedInRight) {
                    null // Exit split mode
                } else {
                    // Adjust tab indices for remaining terminal panes
                    val adjustIndex = { content: PaneContent ->
                        when (content) {
                            is PaneContent.Terminal -> {
                                val adjusted = if (content.tabIndex > index) content.tabIndex - 1 else content.tabIndex
                                PaneContent.Terminal(adjusted)
                            }
                            is PaneContent.Sftp -> content
                        }
                    }
                    split.copy(
                        leftOrTopPane = adjustIndex(split.leftOrTopPane),
                        rightOrBottomPane = adjustIndex(split.rightOrBottomPane),
                    )
                }
            }

            state.copy(tabs = newTabs, activeTabIndex = newActiveIndex, splitState = newSplitState)
        }
        removed?.let { closeInBackground(listOf(it)) }
    }

    /**
     * Ferme tous les onglets, comme le bouton « Tout fermer » de la notification : mêmes garanties
     * que [closeTab] pour chacun, et l'état revient à zéro, split compris.
     */
    fun closeAll() {
        val removed = _state.getAndUpdate { TerminalTabsState() }.tabs
        if (removed.isNotEmpty()) closeInBackground(removed)
    }

    /**
     * Ferme le shell de chaque onglet puis rend sa référence, chacun dans sa propre coroutine : un
     * onglet dont la fermeture échoue n'empêche pas les autres.
     */
    private fun closeInBackground(tabs: List<SessionTab>) {
        tabs.forEach { tab ->
            cleanupScope.launch {
                try {
                    tab.terminalSession.disconnect()
                } catch (e: Exception) {
                    Timber.w("Error closing tab hostId=${LogId.of(tab.host.id)} (${errorClass(e)})")
                } finally {
                    // Même si le shell ne se ferme pas proprement : la référence de l'onglet se rend toujours.
                    sshSessionManager.release(tab.sessionId)
                }
            }
        }
    }

    /** Le shell de [sessionId] s'est terminé : l'onglet reste, avec l'overlay « Reconnecter ». */
    private fun markDisconnected(sessionId: String) {
        _state.update { state ->
            val idx = state.tabs.indexOfFirst { it.sessionId == sessionId }
            if (idx == -1) return@update state
            val updatedTabs = state.tabs.toMutableList()
            updatedTabs[idx] = updatedTabs[idx].copy(status = SessionStatus.DISCONNECTED)
            state.copy(tabs = updatedTabs)
        }
    }

    // ── Hôtes ─────────────────────────────────────────────────────────────────────

    /**
     * Fait suivre aux onglets les modifications de leurs hôtes. Appelé une fois, au démarrage de
     * l'app (`App.onCreate`), comme les autres observateurs de dépôts : l'observation dure autant
     * que l'app, dans [hostScope].
     *
     * Un onglet vit des heures. Sans cela, changer le thème ou le libellé d'un hôte depuis la liste
     * ne se verrait ni sur le terminal déjà ouvert, ni sur les puces d'onglets, ni dans la
     * notification du service, jusqu'à la fermeture de l'onglet. Appelé de nouveau, il ne fait rien.
     */
    fun startObservingHosts() {
        if (hostObservation != null) return
        hostObservation = hostScope.launch {
            hostRepository.observeAll().collect { hosts -> applyHosts(hosts) }
        }
    }

    /**
     * Remplace l'hôte des onglets par son état à jour dans [hosts], par identifiant. Un hôte
     * supprimé n'y est plus : son onglet garde le dernier instantané, il reste utilisable et se
     * reconnectera, ou non, selon ce que le dépôt sait encore. L'état n'est republié que si un hôte a
     * vraiment changé, pour que le moindre mouvement de la liste ne redessine pas les onglets.
     */
    @VisibleForTesting
    internal fun applyHosts(hosts: List<Host>) {
        val byId = hosts.associateBy { it.id }
        _state.update { state ->
            var changed = false
            val tabs = state.tabs.map { tab ->
                val fresh = byId[tab.host.id]
                if (fresh != null && fresh != tab.host) {
                    changed = true
                    tab.copy(host = fresh)
                } else {
                    tab
                }
            }
            if (changed) state.copy(tabs = tabs) else state
        }
    }

    // ── Split-screen ─────────────────────────────────────────────────────────────

    /**
     * Enters split-screen mode with two terminal tabs.
     * The current active tab goes to the left/top pane,
     * [secondTabIndex] goes to the right/bottom pane.
     */
    fun enterSplit(secondTabIndex: Int, orientation: SplitOrientation = SplitOrientation.HORIZONTAL) {
        _state.update { state ->
            val activeIdx = state.activeTabIndex
            if (activeIdx == secondTabIndex) return@update state
            if (state.tabs.getOrNull(activeIdx) == null || state.tabs.getOrNull(secondTabIndex) == null) return@update state
            state.copy(
                splitState = SplitState(
                    orientation = orientation,
                    leftOrTopPane = PaneContent.Terminal(activeIdx),
                    rightOrBottomPane = PaneContent.Terminal(secondTabIndex),
                )
            )
        }
    }

    /**
     * Enters split-screen mode with the current terminal tab + an SFTP browser pane.
     */
    fun enterSplitWithSftp(sessionId: String, hostLabel: String, orientation: SplitOrientation = SplitOrientation.HORIZONTAL) {
        _state.update { state ->
            val activeIdx = state.activeTabIndex
            if (state.tabs.getOrNull(activeIdx) == null) return@update state
            state.copy(
                splitState = SplitState(
                    orientation = orientation,
                    leftOrTopPane = PaneContent.Terminal(activeIdx),
                    rightOrBottomPane = PaneContent.Sftp(sessionId, hostLabel),
                )
            )
        }
    }

    /**
     * Exits split-screen mode. The focused pane's tab becomes the active tab.
     */
    fun exitSplit() {
        _state.update { state ->
            val split = state.splitState ?: return@update state
            val focusedContent = when (split.focusedPane) {
                PaneSlot.LEFT_OR_TOP -> split.leftOrTopPane
                PaneSlot.RIGHT_OR_BOTTOM -> split.rightOrBottomPane
            }
            val maxIndex = (state.tabs.size - 1).coerceAtLeast(0)
            val newActiveIndex = when (focusedContent) {
                is PaneContent.Terminal -> focusedContent.tabIndex.coerceIn(0, maxIndex)
                is PaneContent.Sftp -> state.activeTabIndex.coerceIn(0, maxIndex)
            }
            state.copy(splitState = null, activeTabIndex = newActiveIndex)
        }
    }

    /** Toggles split orientation between horizontal and vertical. */
    fun toggleSplitOrientation() {
        _state.update { state ->
            val split = state.splitState ?: return@update state
            val newOrientation = when (split.orientation) {
                SplitOrientation.HORIZONTAL -> SplitOrientation.VERTICAL
                SplitOrientation.VERTICAL -> SplitOrientation.HORIZONTAL
            }
            state.copy(splitState = split.copy(orientation = newOrientation))
        }
    }

    /** Updates the split ratio, clamped to 0.2..0.8. */
    fun updateSplitRatio(ratio: Float) {
        _state.update { state ->
            val split = state.splitState ?: return@update state
            state.copy(splitState = split.copy(splitRatio = ratio.coerceIn(0.2f, 0.8f)))
        }
    }

    /** Sets which pane has input focus. */
    fun setFocusedPane(slot: PaneSlot) {
        _state.update { state ->
            val split = state.splitState ?: return@update state
            if (split.focusedPane == slot) return@update state
            state.copy(splitState = split.copy(focusedPane = slot))
        }
    }

    /** Swaps the content of the two panes. Focus follows the content. */
    fun swapPanes() {
        _state.update { state ->
            val split = state.splitState ?: return@update state
            val newFocus = when (split.focusedPane) {
                PaneSlot.LEFT_OR_TOP -> PaneSlot.RIGHT_OR_BOTTOM
                PaneSlot.RIGHT_OR_BOTTOM -> PaneSlot.LEFT_OR_TOP
            }
            state.copy(
                splitState = split.copy(
                    leftOrTopPane = split.rightOrBottomPane,
                    rightOrBottomPane = split.leftOrTopPane,
                    focusedPane = newFocus,
                )
            )
        }
    }

    // ── Client Termux ─────────────────────────────────────────────────────────────

    /**
     * Le client Termux de l'onglet de la session [sessionId] : rafraîchissement de la vue,
     * presse-papier (avec son effacement automatique) et fin du shell. Il appartient au store et
     * non à un écran, puisque l'onglet lui survit.
     */
    fun buildSessionClient(sessionId: String): TerminalSessionClient {
        return object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {
                if (changedSession is SshTerminalSession) {
                    changedSession.onScreenUpdate?.invoke()
                }
            }

            override fun onTitleChanged(changedSession: TerminalSession) {}

            override fun onSessionFinished(finishedSession: TerminalSession) {
                Timber.i("SSH session finished: ${LogId.of(sessionId)}")
                markDisconnected(sessionId)
            }

            override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
                if (text.isNullOrEmpty()) return
                val clip = ClipData.newPlainText("NextSH", text)
                clipboardManager.setPrimaryClip(clip)
                scheduleClipboardClear()
            }

            override fun onPasteTextFromClipboard(session: TerminalSession?) {
                val clip = clipboardManager.primaryClip ?: return
                if (clip.itemCount == 0) return
                val text = clip.getItemAt(0).coerceToText(context)
                // A paste, not typed input: bracketed paste, ESC/C1 stripping and CR conversion.
                pasteClipboardText(session?.emulator, text)
            }
            override fun onBell(session: TerminalSession) {}
            override fun onColorsChanged(session: TerminalSession) {}
            override fun onTerminalCursorStateChange(state: Boolean) {}
            override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
            override fun getTerminalCursorStyle(): Int? = null

            // Termux text can carry what the remote host sent: scrubbed before it is logged.
            override fun logError(tag: String, message: String) = TermuxLog.error(tag, message)
            override fun logWarn(tag: String, message: String) = TermuxLog.warn(tag, message)
            override fun logInfo(tag: String, message: String) = TermuxLog.info(tag, message)
            override fun logDebug(tag: String, message: String) = TermuxLog.debug(tag, message)
            override fun logVerbose(tag: String, message: String) = TermuxLog.verbose(tag, message)
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) =
                TermuxLog.stackTraceWithMessage(tag, message, e)
            override fun logStackTrace(tag: String, e: Exception) = TermuxLog.stackTrace(tag, e)
        }
    }

    /**
     * Efface le presse-papier après le délai des réglages, un seul effacement en attente : une
     * nouvelle copie repart de zéro. Un délai de 0 le désactive. `clearPrimaryClip` existe depuis
     * l'API 28, minSdk est 29 : aucun garde de version.
     */
    private fun scheduleClipboardClear() {
        clipboardClearJob?.cancel()
        clipboardClearJob = clipboardScope.launch {
            val timeoutSeconds = settingsDataStore.settingsFlow.first().clipboardClearTimeout
            if (timeoutSeconds <= 0) return@launch
            delay(timeoutSeconds * 1000L)
            clipboardManager.clearPrimaryClip()
        }
    }
}
