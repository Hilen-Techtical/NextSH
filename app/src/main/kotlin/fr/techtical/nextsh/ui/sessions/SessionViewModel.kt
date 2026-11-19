// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.termux.terminal.TerminalSessionClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.AppVisibility
import fr.techtical.nextsh.core.ssh.SessionHandoff
import fr.techtical.nextsh.core.ssh.SshSessionManager
import fr.techtical.nextsh.core.ssh.SshTerminalSession
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.data.preferences.nearestScrollbackStep
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SessionStatus
import fr.techtical.nextsh.domain.model.Snippet
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SnippetRepository
import fr.techtical.nextsh.domain.usecase.ConnectSessionUseCase
import fr.techtical.nextsh.service.TerminalServiceController
import fr.techtical.nextsh.shared.util.LogId
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.schmizz.sshj.connection.channel.direct.Session
import timber.log.Timber
import javax.inject.Inject

// ── Modèles d'état ─────────────────────────────────────────────────────────────

/**
 * Ce que l'écran du terminal affiche : les onglets, l'onglet actif et le split viennent de
 * [TerminalTabStore] (ils survivent à l'écran), [isConnecting] et [error] sont propres au
 * [SessionViewModel] (transitoires, liés à l'écran).
 */
data class SessionUiState(
    val tabs: List<SessionTab> = emptyList(),
    val activeTabIndex: Int = 0,
    val isConnecting: Boolean = false,
    val error: String? = null,
    val splitState: SplitState? = null,
)

/** La part de [SessionUiState] qui n'appartient qu'à l'écran. */
private data class ScreenState(
    val isConnecting: Boolean = false,
    val error: String? = null,
)

private fun mergeUiState(tabs: TerminalTabsState, screen: ScreenState) = SessionUiState(
    tabs           = tabs.tabs,
    activeTabIndex = tabs.activeTabIndex,
    isConnecting   = screen.isConnecting,
    error          = screen.error,
    splitState     = tabs.splitState,
)

/**
 * [SessionUiState] assemblé depuis le store et l'état de l'écran. Sa [value] se calcule à la
 * lecture : elle est à jour dès que l'appel qui vient de changer l'un des deux est rendu, ce
 * qu'un `combine(...).stateIn(...)` ne garantit pas (la propagation passe par un canal et par le
 * dispatcher du collecteur).
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class SessionUiStateFlow(
    private val tabs: StateFlow<TerminalTabsState>,
    private val screen: StateFlow<ScreenState>,
) : StateFlow<SessionUiState> {

    override val value: SessionUiState
        get() = mergeUiState(tabs.value, screen.value)

    override val replayCache: List<SessionUiState>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<SessionUiState>): Nothing {
        combine(tabs, screen, ::mergeUiState).distinctUntilChanged().collect(collector)
        // Les deux sources sont des StateFlow, elles ne se terminent jamais.
        awaitCancellation()
    }
}

/**
 * État du dialogue de vérification de clé hôte.
 *
 * @param hostname          Hôte concerné (ex: "example.com:22")
 * @param algorithm         Algorithme de la clé (ex: "ssh-ed25519")
 * @param fingerprint       Empreinte SHA-256 reçue (ex: "SHA256:…")
 * @param isMismatch        true = avertissement MITM, false = première connexion
 * @param storedFingerprint Empreinte stockée (uniquement si [isMismatch] == true)
 */
data class HostKeyDialogState(
    val hostname: String,
    val algorithm: String,
    val fingerprint: String,
    val isMismatch: Boolean,
    val storedFingerprint: String? = null,
)

// ── ViewModel ──────────────────────────────────────────────────────────────────

/**
 * Façade de l'écran du terminal. Les onglets, le split et leurs références SSH appartiennent à
 * [TerminalTabStore], un singleton d'app : ce ViewModel ouvre les connexions et les onglets, puis
 * les lui remet. Il meurt avec l'écran (entrée de navigation `Sessions`) sans rien fermer, voir
 * [onCleared].
 */
@HiltViewModel
class SessionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sshSessionManager: SshSessionManager,
    private val hostRepository: HostRepository,
    private val snippetRepository: SnippetRepository,
    private val customThemeRepository: fr.techtical.nextsh.domain.repository.CustomTerminalThemeRepository,
    private val connectSession: ConnectSessionUseCase,
    private val settingsDataStore: SettingsDataStore,
    private val sessionHandoff: SessionHandoff,
    private val tabStore: TerminalTabStore,
    private val terminalService: TerminalServiceController,
    private val appVisibility: AppVisibility,
) : ViewModel() {

    private val screenState = MutableStateFlow(ScreenState())
    val uiState: StateFlow<SessionUiState> = SessionUiStateFlow(tabStore.state, screenState)

    val terminalFontSize: StateFlow<Int> = settingsDataStore.terminalFontSizeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 24)

    /** Liste des hôtes exposée pour le host picker in-terminal. */
    val hosts: StateFlow<List<Host>> = hostRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Thèmes terminal personnalisés, utilisés pour résoudre les palettes par hôte. */
    val customThemes: StateFlow<List<fr.techtical.nextsh.domain.model.CustomTerminalTheme>> =
        customThemeRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Note : `hostKeyDialog` (state + accept/reject) déplacés vers
    // `HostKeyPromptCoordinator` (Singleton) : le dialogue est rendu au
    // niveau MainActivity pour fonctionner depuis n'importe quel écran.
    // Même chose pour la demande de clé FIDO2 : `Fido2PromptCoordinator`
    // (Singleton), feuille rendue par MainActivity (`Fido2PromptHost`).

    /** Sessions en cours de reprise par [attachToSession] (thread principal uniquement). */
    private val attachingSessionIds = mutableSetOf<String>()

    /** Sessions des onglets en cours de reconnexion par [reconnectTab] (thread principal uniquement). */
    private val reconnectingSessionIds = mutableSetOf<String>()

    /**
     * Construit la [SshTerminalSession] d'un shell ouvert. Remplaçable dans les tests : la vraie
     * s'appuie sur un Handler Android, absent d'un test unitaire.
     */
    @androidx.annotation.VisibleForTesting
    internal var terminalSessionFactory: (Session.Shell, String, TerminalSessionClient, Int) -> SshTerminalSession =
        { shell, label, client, scrollbackRows ->
            SshTerminalSession(
                sshShell       = shell,
                sessionLabel   = label,
                client         = client,
                transcriptRows = scrollbackRows,
            )
        }

    // Note : les callbacks interactifs du SshSessionManager (clé hôte, signature FIDO2, empreinte)
    // sont installés une seule fois par `App.onCreate`, via `HostKeyPromptCoordinator`,
    // `Fido2PromptCoordinator` et `BiometricPromptCoordinator`. Ce ViewModel n'en installe aucun :
    // une connexion lancée depuis la liste d'hôtes, le SFTP ou un tunnel ne le crée jamais, et un
    // callback accroché à un ViewModel détruit pointerait dans le vide.

    // ── API publique ──────────────────────────────────────────────────────────────

    /**
     * Connecte à un hôte et crée un nouvel onglet terminal.
     * Enchaîne : connexion SSH → ouverture shell → création SshTerminalSession.
     * Si le shell ne s'ouvre pas, l'erreur est affichée et la session SSH est fermée.
     *
     * Les invites de l'authentification (clé hôte inconnue, clé de sécurité FIDO2, empreinte d'une
     * clé biométrique) ne sont pas l'affaire de ce ViewModel : voir la note plus haut. Toute erreur
     * de connexion, y compris une authentification expirée, est affichée comme les autres.
     *
     * Une connexion en cours s'annule avec l'écran. Une fois l'onglet ajouté au [TerminalTabStore],
     * il ne dépend plus de l'écran.
     */
    fun connectToHost(hostId: String) {
        viewModelScope.launch {
            screenState.update { it.copy(isConnecting = true, error = null) }
            connectInto(hostId) { tab ->
                tabStore.addTab(tab)
                true
            }
        }
    }

    /**
     * Les étapes communes à [connectToHost] et [reconnectTab] : connexion SSH, ouverture du shell,
     * création de la [SshTerminalSession], puis [place] met l'onglet construit dans le
     * [TerminalTabStore] et dit si elle l'a pris (faux : il n'a pas trouvé sa place). Une erreur,
     * quelle que soit l'étape, est affichée par le canal `error` et ne touche à aucun onglet.
     *
     * La connexion rend la session avec une référence, la nôtre, jusqu'à ce qu'un onglet la porte.
     * Sans onglet à la fin (le shell ne s'ouvre pas, ou l'écran est quitté et la coroutine annulée
     * pendant l'ouverture), personne d'autre ne la possède : on la rend au lieu de laisser la
     * session ouverte, invisible, jusqu'à la fin du processus. La libération ne suspend pas, elle
     * va au bout même dans une coroutine déjà annulée. Un onglet construit qui n'a pas été pris
     * part au store pour être fermé ([TerminalTabStore.discard]) : son shell, puis la référence.
     *
     * Aucune suspension entre la construction de l'onglet et [place] : l'annulation de l'écran ne
     * peut pas tomber entre les deux.
     */
    private suspend fun connectInto(hostId: String, place: (SessionTab) -> Boolean) {
        val host = hostRepository.getById(hostId)
        if (host == null) {
            screenState.update {
                it.copy(isConnecting = false, error = "Hôte introuvable : $hostId")
            }
            return
        }

        // 1. Connexion SSH
        val sshResult = connectSession(host)
        if (sshResult is SshResult.Error) {
            screenState.update {
                it.copy(isConnecting = false, error = sshResult.message)
            }
            return
        }
        val sshSession = (sshResult as SshResult.Success).data

        // 2 + 3. Ouverture du shell et création de l'onglet
        // Vrai dès que la référence a un autre propriétaire que cette fonction : l'onglet placé, ou
        // l'onglet sans place confié au store pour fermeture.
        var referenceHandedOver = false
        try {
            when (val built = buildTab(sshSession.id, host)) {
                is SshResult.Error -> screenState.update {
                    it.copy(isConnecting = false, error = built.message)
                }
                is SshResult.Success -> {
                    if (place(built.data)) {
                        referenceHandedOver = true
                        onTabAdded()
                    } else {
                        // Pas de place : le shell construit se ferme avant que la référence soit
                        // rendue, comme à la fermeture d'un onglet.
                        tabStore.discard(built.data)
                        referenceHandedOver = true
                        screenState.update { it.copy(isConnecting = false) }
                    }
                }
            }
        } finally {
            if (!referenceHandedOver) sshSessionManager.release(sshSession.id)
        }
    }

    /**
     * Ouvre un onglet terminal sur la session [sessionId] que la liste d'hôtes vient de connecter
     * pour [hostId], sans se reconnecter : une seule authentification par tap, et la session de la
     * liste devient celle de l'onglet au lieu de rester ouverte, orpheline, à côté d'une seconde.
     *
     * La liste a proposé la session à [SessionHandoff] avant d'annoncer l'événement : la reprendre
     * ([SessionHandoff.claim]) donne ce terminal propriétaire de la référence de la liste, qu'il
     * garde pour son onglet et qu'il rend à la fermeture de l'onglet. Rien à reprendre (le délai est
     * passé, la session n'a jamais été proposée) : il ne possède rien et ne rend rien.
     *
     * Si la session n'existe plus ou n'est plus connectée, ou si l'ouverture du shell échoue, la
     * référence reprise est rendue puis le terminal se connecte lui-même, une seule fois
     * ([connectToHost] ne reprend jamais de session, il n'y a donc aucune boucle possible).
     *
     * Un second appel pour une session déjà affichée ou en cours de reprise est sans effet : jamais
     * deux onglets ni deux shells pour la même session.
     */
    fun attachToSession(sessionId: String, hostId: String) {
        if (tabStore.hasSession(sessionId)) return
        if (!attachingSessionIds.add(sessionId)) return

        viewModelScope.launch {
            screenState.update { it.copy(isConnecting = true, error = null) }

            val claimed = sessionHandoff.claim(sessionId)
            var attached = false
            try {
                attached = claimed && tryAttach(sessionId, hostId)
            } finally {
                attachingSessionIds.remove(sessionId)
                if (claimed && !attached) {
                    // Échec, session disparue, ou écran quitté pendant la reprise (coroutine annulée) :
                    // la référence reprise n'a plus de propriétaire, on la rend. L'appel ne suspend
                    // pas, il tourne jusqu'au bout même dans une coroutine déjà annulée.
                    sshSessionManager.release(sessionId)
                }
            }
            if (!attached) connectToHost(hostId)
        }
    }

    /** Vrai si un onglet a été ouvert sur la session [sessionId] de l'hôte [hostId]. */
    private suspend fun tryAttach(sessionId: String, hostId: String): Boolean {
        val host = hostRepository.getById(hostId)
        if (host == null) {
            Timber.w("Host of the session to take over is gone: ${LogId.of(hostId)}")
            return false
        }
        val session = sshSessionManager.getSession(sessionId)
        if (session == null || session.host.id != hostId || !sshSessionManager.isConnected(sessionId)) {
            Timber.w("Session to take over is gone: ${LogId.of(sessionId)}, connecting again")
            return false
        }
        val shellError = openTab(sessionId, host)
        if (shellError != null) {
            Timber.w("Shell on the session to take over failed: ${LogId.of(sessionId)}, connecting again")
            return false
        }
        return true
    }

    /**
     * Ouvre le shell sur la session connectée [sessionId], crée la [SshTerminalSession] et
     * l'onglet de [host], qu'il remet au [TerminalTabStore]. Retourne l'erreur d'ouverture du
     * shell, null si l'onglet est affiché. Étapes de [attachToSession], celles de [connectToHost]
     * passent par [connectInto].
     *
     * Une fois l'onglet dans le store, il tient la référence SSH de l'appelant : plus aucune
     * suspension ne sépare l'ajout du retour de cette fonction, donc l'annulation de l'écran ne
     * peut pas tomber entre les deux et laisser la référence sans propriétaire.
     */
    private suspend fun openTab(sessionId: String, host: Host): SshResult.Error? {
        val built = buildTab(sessionId, host)
        if (built is SshResult.Error) return built
        tabStore.addTab((built as SshResult.Success).data)
        onTabAdded()
        return null
    }

    /**
     * Ouvre le shell sur la session connectée [sessionId] et construit l'onglet de [host] sur sa
     * [SshTerminalSession] (le pont terminal), sans le donner au store : l'appelant choisit s'il
     * l'ajoute ou s'il remplace un onglet. Retourne l'erreur d'ouverture du shell.
     *
     * L'onglet construit tient déjà la référence de l'appelant sur la session, mais tant que le
     * store ne l'a pas pris c'est à l'appelant de la rendre.
     */
    private suspend fun buildTab(sessionId: String, host: Host): SshResult<SessionTab> {
        // 2. Ouverture du shell
        val shellResult = sshSessionManager.openShell(sessionId)
        if (shellResult is SshResult.Error) return shellResult
        val shell = (shellResult as SshResult.Success).data

        // 3. Création de la SshTerminalSession (bridge terminal)
        // Le réglage vaut pour les nouveaux onglets et les reconnexions, pas pour les onglets ouverts.
        val scrollbackRows = nearestScrollbackStep(settingsDataStore.settingsFlow.first().scrollbackLines)
        val terminalSession = terminalSessionFactory(shell, host.label, tabStore.buildSessionClient(sessionId), scrollbackRows)

        return SshResult.Success(
            SessionTab(
                sessionId       = sessionId,
                host            = host,
                terminalSession = terminalSession,
                status          = SessionStatus.CONNECTED,
            )
        )
    }

    /**
     * Après l'ajout, ou le remplacement, d'un onglet connecté : le service au premier plan est
     * demandé, et la connexion est terminée.
     *
     * Un refus du système est annoncé par le canal d'erreur : l'onglet est ouvert, mais la session
     * risque de se couper si l'utilisateur quitte NextSH. Il ne l'est que si l'app est visible : la
     * connexion a pu durer plus longtemps que le séjour de l'utilisateur dans l'app, et le refus
     * est alors normal (le système interdit ce démarrage en arrière-plan) et sans personne pour
     * lire le message, qui apparaîtrait à son retour sans rapport avec ce qu'il vient de faire.
     * `MainActivity` redemande le service à ce retour (voir `resumeTerminalService`).
     */
    private fun onTabAdded() {
        val serviceRefused = !terminalService.ensureRunning()
        val warn = serviceRefused && appVisibility.isVisible()
        screenState.update {
            it.copy(
                isConnecting = false,
                error = if (warn) context.getString(R.string.terminal_service_start_refused) else it.error,
            )
        }
    }

    /** Sélectionne l'onglet actif. */
    fun selectTab(index: Int) = tabStore.selectTab(index)

    /**
     * Affiche l'onglet déjà ouvert sur l'hôte [hostId], s'il y en a un. Faux si l'hôte n'a aucun
     * onglet : l'appelant se connecte alors.
     */
    fun selectTabOfHost(hostId: String): Boolean = tabStore.selectTabOfHost(hostId)

    /**
     * Ferme un onglet : son shell, puis la référence qu'il tient sur la session. La session ne se
     * ferme qu'avec sa dernière référence : un tunnel, un explorateur SFTP ou un transfert qui
     * l'utilisent la gardent ouverte. C'est l'action explicite de l'utilisateur, la seule avec
     * « Tout fermer » de la notification : quitter l'écran ne ferme aucun onglet.
     */
    fun closeTab(index: Int) {
        tabStore.closeTab(index)
        // La commande en attente visait la session focalisée, qui peut être celle qui vient de fermer.
        _pendingSnippet.value = null
    }

    private val _pendingSnippet = MutableStateFlow<Snippet?>(null)

    /**
     * Snippet dont l'utilisateur doit confirmer l'exécution. Tenu ici et non dans l'écran : il doit
     * survivre à une rotation, mais pas à la mort du processus (les onglets meurent avec lui), et un
     * [Snippet] n'a pas à entrer dans un `Bundle`.
     */
    val pendingSnippet: StateFlow<Snippet?> = _pendingSnippet

    /** Demande la confirmation d'exécution de [snippet] (le dialogue s'affiche). */
    fun requestSnippet(snippet: Snippet) {
        _pendingSnippet.value = snippet
    }

    /** Exécute le snippet en attente dans la session focalisée, puis vide la demande. */
    fun confirmSnippet() {
        val snippet = _pendingSnippet.value ?: return
        _pendingSnippet.value = null
        executeSnippet(snippet.command)
    }

    /** Abandonne la demande de confirmation en cours. */
    fun cancelSnippet() {
        _pendingSnippet.value = null
    }

    /** Efface le message d'erreur affiché. */
    fun clearError() {
        screenState.update { it.copy(error = null) }
    }

    /**
     * Retourne un StateFlow des snippets globaux + spécifiques à l'hôte.
     * Si hostId est null, retourne uniquement les snippets globaux.
     */
    fun snippetsForHost(hostId: String?): StateFlow<List<Snippet>> {
        val flow = if (hostId != null) {
            snippetRepository.observeForHost(hostId)
        } else {
            snippetRepository.observeAll()
        }
        return flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    /**
     * Envoie une commande snippet + saut de ligne à la session focalisée.
     * En mode split, envoie au pane ayant le focus (ignoré si pane SFTP).
     */
    fun executeSnippet(command: String) {
        val session = focusedSession ?: return
        session.write(command + "\n")
    }

    /**
     * Reconnecte un onglet déconnecté ou en erreur : une nouvelle connexion vers le même hôte
     * (connexion, shell, nouvelle [SshTerminalSession]), puis l'onglet est remplacé À SA PLACE par
     * [TerminalTabStore.replaceTab], qui ferme l'ancien (shell, puis sa référence, une seule fois).
     *
     * L'ancien onglet n'est touché qu'une fois la reconnexion réussie : si l'hôte est injoignable ou
     * l'authentification expirée, l'erreur s'affiche par le canal `error` et l'onglet reste tel quel,
     * son historique avec lui. Pendant la reconnexion l'écran montre la connexion en cours
     * (`isConnecting`), comme pour une connexion, et un second appui sur le même onglet ne lance
     * rien. Si l'écran est quitté pendant la reconnexion, la coroutine est annulée : la nouvelle
     * référence est rendue et l'ancien onglet reste. Si l'ancien onglet a été fermé entre-temps, la
     * nouvelle session n'a plus de place et est rendue.
     */
    fun reconnectTab(index: Int) {
        val tab = tabStore.state.value.tabs.getOrNull(index) ?: return
        if (tab.status != SessionStatus.DISCONNECTED && tab.status != SessionStatus.ERROR) return
        if (!reconnectingSessionIds.add(tab.sessionId)) return

        viewModelScope.launch {
            screenState.update { it.copy(isConnecting = true, error = null) }
            try {
                connectInto(tab.host.id) { newTab -> tabStore.replaceTab(tab.sessionId, newTab) }
            } finally {
                reconnectingSessionIds.remove(tab.sessionId)
            }
        }
    }

    // acceptHostKey / rejectHostKey : déplacés vers HostKeyPromptCoordinator
    // (rendu global au niveau MainActivity).

    // ── Split-screen ─────────────────────────────────────────────────────────────

    /**
     * Returns the terminal session of the currently focused pane.
     * In single-pane mode, returns the active tab's session.
     * Returns null if the focused pane is SFTP (SFTP does not receive terminal keys).
     */
    val focusedSession: SshTerminalSession?
        get() = tabStore.focusedSession

    /** See [TerminalTabStore.enterSplit]. */
    fun enterSplit(secondTabIndex: Int, orientation: SplitOrientation = SplitOrientation.HORIZONTAL) =
        tabStore.enterSplit(secondTabIndex, orientation)

    /** See [TerminalTabStore.enterSplitWithSftp]. */
    fun enterSplitWithSftp(sessionId: String, hostLabel: String, orientation: SplitOrientation = SplitOrientation.HORIZONTAL) =
        tabStore.enterSplitWithSftp(sessionId, hostLabel, orientation)

    /** Exits split-screen mode. The focused pane's tab becomes the active tab. */
    fun exitSplit() = tabStore.exitSplit()

    /** Toggles split orientation between horizontal and vertical. */
    fun toggleSplitOrientation() = tabStore.toggleSplitOrientation()

    /** Updates the split ratio, clamped to 0.2..0.8. */
    fun updateSplitRatio(ratio: Float) = tabStore.updateSplitRatio(ratio)

    /** Sets which pane has input focus. */
    fun setFocusedPane(slot: PaneSlot) = tabStore.setFocusedPane(slot)

    /** Swaps the content of the two panes. Focus follows the content. */
    fun swapPanes() = tabStore.swapPanes()

    // ── Cycle de vie ──────────────────────────────────────────────────────────────

    /**
     * Ne ferme rien et ne rend aucune référence : les onglets, le split et leurs références SSH
     * sont au [TerminalTabStore], qui survit à l'écran. Seul ce qui est propre à l'écran s'arrête
     * ici, avec `viewModelScope` : une connexion ou une reprise en cours, dont le `finally` rend la
     * référence qu'aucun onglet ne porte encore.
     *
     * hostKeyResponse et réponse FIDO2 retirées : gérées par les coordinators (Singleton). Le
     * presse-papier aussi est au store, son effacement différé continue après la sortie de l'écran.
     */
    override fun onCleared() {
        super.onCleared()
    }
}
