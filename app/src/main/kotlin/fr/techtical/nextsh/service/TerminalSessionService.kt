// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import fr.techtical.nextsh.MainActivity
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.shortcut.ShortcutRequest
import fr.techtical.nextsh.core.shortcut.ShortcutTokenStore
import fr.techtical.nextsh.core.shortcut.ShortcutUpdater
import fr.techtical.nextsh.domain.model.SessionStatus
import fr.techtical.nextsh.ui.sessions.SessionTab
import fr.techtical.nextsh.ui.sessions.TerminalTabStore
import fr.techtical.nextsh.ui.sessions.TerminalTabsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

private const val TERMINAL_NOTIFICATION_ID = 1004
private const val CONTENT_REQUEST_CODE = 10
private const val CLOSE_ALL_REQUEST_CODE = 11
internal const val ACTION_CLOSE_ALL_TERMINAL_SESSIONS = "fr.techtical.nextsh.CLOSE_ALL_TERMINAL_SESSIONS"

/** Nombre maximal de lignes (un hôte par ligne) dans la notification déployée. */
internal const val TERMINAL_NOTIFICATION_MAX_LINES = 5

/**
 * Délai laissé à un service démarré pour voir passer son premier onglet connecté, voir
 * [TerminalSessionService.stopIfNoSessionComes].
 */
internal const val NO_SESSION_GRACE_MS = 10_000L

/**
 * Les changements de la liste des onglets, et d'eux seuls : le service n'a rien à faire du glissement
 * du séparateur de split, qui republie l'état du store à chaque image.
 */
internal fun Flow<TerminalTabsState>.tabChanges(): Flow<List<SessionTab>> =
    map { it.tabs }.distinctUntilChanged()

/** Les onglets dont le shell tourne : ce que le service maintient en vie, et seulement cela. */
internal fun connectedTabs(tabs: List<SessionTab>): List<SessionTab> =
    tabs.filter { it.status == SessionStatus.CONNECTED }

/**
 * Vrai quand le service n'a plus rien à servir : un onglet a été connecté ([hadConnectedTab]) et il
 * n'en reste aucun. Les onglets déconnectés restent dans le store, avec leur overlay « Reconnecter »,
 * mais une session morte ne justifie plus de garder le processus au premier plan : le service
 * repart, par `TerminalServiceController.ensureRunning`, à la prochaine connexion.
 *
 * `hadConnectedTab` : la liste est vide, ou ne porte aucun onglet connecté, avant que le premier
 * ne soit enregistré, ce qui ne doit pas faire s'arrêter un service qui vient de démarrer.
 */
internal fun shouldStopTerminalService(tabs: List<SessionTab>, hadConnectedTab: Boolean): Boolean =
    hadConnectedTab && connectedTabs(tabs).isEmpty()

/**
 * Ce que dit la notification : le nombre de sessions ouvertes (onglets connectés), les libellés
 * d'hôtes des [maxLines] premières, et combien d'autres tiennent dans le résumé « et N autres ».
 * Les libellés viennent de la notification seule : jamais dans un journal.
 */
internal data class TerminalNotificationContent(
    val sessionCount: Int,
    val lines: List<String>,
    val moreCount: Int,
)

internal fun terminalNotificationContent(
    tabs: List<SessionTab>,
    maxLines: Int = TERMINAL_NOTIFICATION_MAX_LINES,
): TerminalNotificationContent {
    val labels = connectedTabs(tabs).map { it.host.label }
    return TerminalNotificationContent(
        sessionCount = labels.size,
        lines = labels.take(maxLines),
        moreCount = (labels.size - maxLines).coerceAtLeast(0),
    )
}

/**
 * Service au premier plan qui garde le processus en vie tant qu'au moins un onglet terminal est
 * connecté.
 *
 * Sans lui, Android détruit les sockets TCP de l'app environ six secondes après son passage en
 * arrière-plan : le lecteur de chaque shell reçoit une `SocketException` et l'onglet tombe en
 * « déconnecté ». Le service ne maintient rien lui-même, il empêche le système de couper : les
 * sessions vivent dans [TerminalTabStore], ce singleton d'app. Il observe le store, s'arrête quand
 * le dernier onglet connecté a disparu ou est tombé (voir [shouldStopTerminalService]), et tient sa
 * notification à jour.
 *
 * Un onglet ne se ferme que sur une action explicite de l'utilisateur : sa croix, ou le bouton
 * « Tout fermer » de la notification ([ACTION_CLOSE_ALL_TERMINAL_SESSIONS]). Rien d'autre ne ferme
 * un onglet ici. Balayer l'app des applications récentes ne coupe donc rien non plus (pas
 * d'`onTaskRemoved`), c'est un choix de produit : la notification reste là pour tout fermer.
 *
 * Type de service : `specialUse`, comme pour les tunnels, et pour la même raison : une session
 * terminal n'est pas un transfert fini, elle reste ouverte tant que l'utilisateur le veut, parfois
 * des heures. Voir `docs/play-foreground-service-justification.md` pour la justification soumise à
 * la revue Google Play.
 *
 * Démarrage : jamais directement. Passer par [TerminalServiceController], qui rend l'appel
 * idempotent et intercepte le refus système. Il est demandé à l'ouverture d'un onglet, puis de
 * nouveau à chaque retour de l'app à l'écran tant qu'un onglet est connecté ([resumeTerminalService]) :
 * une connexion qui aboutit alors que l'app est en arrière-plan ne peut pas démarrer le service, le
 * système le refuse.
 *
 * Il ne réagit qu'aux changements de la liste des onglets ([tabChanges]), pas aux mouvements du
 * split, qui republient l'état du store à chaque image.
 *
 * Pourquoi `START_NOT_STICKY` : si le système tue le processus, les sessions meurent avec lui. Les
 * rouvrir demanderait de rouvrir le vault, donc une authentification de l'utilisateur, impossible
 * sans interface : un redémarrage automatique ne ferait que réafficher une notification qui
 * annonce des sessions qui n'existent plus.
 */
@AndroidEntryPoint
class TerminalSessionService : Service() {

    @Inject
    lateinit var tabStore: TerminalTabStore

    @Inject
    lateinit var shortcutTokens: ShortcutTokenStore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeStarted = false

    /**
     * Passe à true à la première émission qui porte un onglet connecté. Sans ce garde-fou, une
     * liste qui n'en porte pas encore ferait appeler `stopSelf()` à un service qui vient de démarrer.
     */
    private var hadConnectedTab = false

    /** Vrai de l'arrêt demandé à la destruction : plus aucune notification ne doit être publiée. */
    private var stopping = false

    private var lastContent: TerminalNotificationContent? = null

    override fun onCreate() {
        super.onCreate()
        ensureTerminalNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE_ALL_TERMINAL_SESSIONS) {
            Timber.i("Action CLOSE_ALL_TERMINAL_SESSIONS reçue")
            tabStore.closeAll()
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        stopping = false
        // `ensureRunning` est appelé à CHAQUE ouverture d'onglet, donc aussi quand le service
        // tourne déjà : on repart du contenu à jour du store, pas d'une notification vide.
        val content = terminalNotificationContent(tabStore.state.value.tabs)
        lastContent = content
        startInForeground(buildNotification(content))
        // Texte fixe : le libellé d'un hôte est une donnée privée.
        Timber.i("TerminalSessionService démarré")

        if (!observeStarted) {
            observeStarted = true
            serviceScope.launch { observeTabs() }
            serviceScope.launch { stopIfNoSessionComes() }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Ne ferme PAS les sessions.
     *
     * `onDestroy` est appelé aussi bien sur arrêt volontaire que sur destruction par le système.
     * Y fermer les onglets rendrait le service nuisible : il tuerait ce qu'il est censé protéger.
     * La fermeture n'a lieu que sur [ACTION_CLOSE_ALL_TERMINAL_SESSIONS], une action explicite de
     * l'utilisateur, et sur [onTimeout].
     */
    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Appelé sur Android 15+ quand le système reprend la main sur un service au premier plan qui a
     * dépassé sa durée autorisée. Il reste quelques secondes pour s'arrêter, sinon le système tue
     * le processus avec une exception fatale.
     *
     * Le type `specialUse` n'a aujourd'hui aucun quota documenté, donc ce chemin ne devrait pas se
     * produire. Il est implémenté par sécurité : une évolution de la plateforme ne doit pas se
     * traduire par un crash. Sans le service, les sockets ne survivent pas à l'arrière-plan : on
     * ferme les sessions proprement plutôt que de les laisser tomber une à une.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Timber.w("TerminalSessionService: durée maximale atteinte, fermeture des sessions")
        tabStore.closeAll()
        stopForegroundAndSelf()
    }

    // ── Observation du store ──────────────────────────────────────────────────────

    private suspend fun observeTabs() {
        tabStore.state.tabChanges().collect { tabs ->
            when {
                shouldStopTerminalService(tabs, hadConnectedTab) -> stopForegroundAndSelf()
                connectedTabs(tabs).isNotEmpty() -> {
                    hadConnectedTab = true
                    updateNotification(terminalNotificationContent(tabs))
                }
            }
        }
    }

    /**
     * Un service demandé pour un onglet qui est tombé, ou fermé, avant qu'il ne démarre ne verra
     * jamais d'onglet connecté, donc [shouldStopTerminalService] ne le fera jamais s'arrêter : la
     * notification « 0 session » resterait, impossible à balayer. Passé [NO_SESSION_GRACE_MS] sans
     * l'avoir vu, il s'arrête.
     */
    private suspend fun stopIfNoSessionComes() {
        delay(NO_SESSION_GRACE_MS)
        if (!hadConnectedTab) stopForegroundAndSelf()
    }

    // ── Cycle de vie premier plan ─────────────────────────────────────────────────

    private fun startInForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            TERMINAL_NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    private fun stopForegroundAndSelf() {
        stopping = true
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Notification ──────────────────────────────────────────────────────────────

    /**
     * Ouvre le terminal sur ses onglets. `MainActivity` est exportée : n'importe quelle app peut lui
     * envoyer des extras, donc l'intent prend la filière des raccourcis du lanceur
     * ([ShortcutRequest.OpenTerminal]), qui n'agit que sur le jeton de cette installation. Le jeton
     * est un secret : il ne va que dans cet intent, jamais dans un journal.
     *
     * `FLAG_IMMUTABLE` : une autre app ne peut pas remplir l'intent. `FLAG_UPDATE_CURRENT` : si le
     * jeton a changé depuis la publication d'une notification encore là, l'intent le reprend. Le code
     * de requête est propre à cette notification, l'identité d'un `PendingIntent` ignorant les extras.
     */
    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, CONTENT_REQUEST_CODE,
        ShortcutUpdater.openTerminalIntent(Intent(this, MainActivity::class.java), shortcutTokens.token()),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun closeAllIntent(): PendingIntent = PendingIntent.getService(
        this, CLOSE_ALL_REQUEST_CODE,
        Intent(this, TerminalSessionService::class.java).apply { action = ACTION_CLOSE_ALL_TERMINAL_SESSIONS },
        PendingIntent.FLAG_IMMUTABLE,
    )

    /** Republie la notification si son contenu a changé, pas à chaque mouvement du split ou d'onglet. */
    private fun updateNotification(content: TerminalNotificationContent) {
        if (stopping || content == lastContent) return
        lastContent = content
        getSystemService(NotificationManager::class.java)
            .notify(TERMINAL_NOTIFICATION_ID, buildNotification(content))
    }

    private fun buildNotification(content: TerminalNotificationContent): Notification {
        val style = NotificationCompat.InboxStyle()
        content.lines.forEach { style.addLine(it) }
        if (content.moreCount > 0) {
            style.setSummaryText(
                resources.getQuantityString(R.plurals.terminal_notification_more, content.moreCount, content.moreCount)
            )
        }

        return NotificationCompat.Builder(this, TERMINAL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle(
                resources.getQuantityString(
                    R.plurals.terminal_notification_title, content.sessionCount, content.sessionCount,
                )
            )
            .setContentText(content.lines.joinToString(", "))
            .setStyle(style)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .addAction(R.drawable.ic_stop, getString(R.string.terminal_notification_close_all), closeAllIntent())
            .build()
    }
}
