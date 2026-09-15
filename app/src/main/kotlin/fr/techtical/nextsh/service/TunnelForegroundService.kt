// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.service

import android.app.Notification
import android.app.NotificationChannel
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
import fr.techtical.nextsh.core.network.NetworkMonitor
import fr.techtical.nextsh.core.ssh.SshTunnelManager
import fr.techtical.nextsh.domain.model.TunnelState
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.usecase.ReconnectTunnelsUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

private const val CHANNEL_ID        = "tunnel_channel"
private const val NOTIFICATION_ID   = 1001
internal const val ACTION_STOP_ALL  = "fr.techtical.nextsh.STOP_ALL_TUNNELS"

/**
 * Service au premier plan qui maintient les tunnels SSH vivants quand l'app
 * passe en arrière-plan, et qui héberge la boucle de reconnexion automatique.
 *
 * Type de service : `specialUse` et non `dataSync`. Un tunnel SSH n'est pas un
 * transfert de données fini : il reste ouvert tant que l'utilisateur le veut,
 * parfois des heures. Le quota `dataSync` (6 h par tranche de 24 h en
 * arrière-plan depuis targetSdk 35) le tuerait en pleine utilisation, avec
 * chute de toutes les connexions multiplexées dedans. Voir
 * `docs/play-foreground-service-justification.md` pour la justification
 * soumise à la revue Google Play.
 *
 * Démarrage : jamais directement. Passer par [TunnelServiceController], qui
 * rend l'appel idempotent et intercepte le refus système.
 *
 * Pourquoi `START_NOT_STICKY` : si le système tue le process, les tunnels
 * meurent avec lui, car ils vivent dans ce même process. Les relancer
 * demanderait de rouvrir le vault, donc une authentification de l'utilisateur,
 * impossible sans interface. Un redémarrage automatique ne pourrait donc que
 * réafficher une notification mensongère annonçant des tunnels qui n'existent
 * plus. L'utilisateur rouvre l'app et relance ses tunnels lui-même.
 */
@AndroidEntryPoint
class TunnelForegroundService : Service() {

    @Inject
    lateinit var tunnelManager: SshTunnelManager

    @Inject
    lateinit var networkMonitor: NetworkMonitor

    @Inject
    lateinit var reconnectTunnels: ReconnectTunnelsUseCase

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var reconnectStarted = false
    private var observeStarted = false

    /**
     * Passe à true à la première émission non vide de `tunnelStates`.
     *
     * Sans ce garde-fou, le collecteur voit la map vide initiale et appelle
     * `stopSelf()` avant même que le premier tunnel n'ait été enregistré : le
     * service se suicide au démarrage et, sur redémarrage automatique du
     * système, il ne se relance jamais utilement.
     */
    private var hadTunnels = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            Timber.i("Action STOP_ALL_TUNNELS reçue")
            tunnelManager.stopAllTunnels()
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        // `ensureRunning` est appelé à CHAQUE démarrage de tunnel, donc aussi
        // quand le service tourne déjà et affiche la liste complète. Ne
        // repartir sur la notification mono-tunnel que s'il n'y en a qu'un,
        // sinon démarrer un quatrième tunnel effacerait la liste des trois
        // autres.
        val states = tunnelManager.tunnelStates.value
        val tunnelLabel = intent?.getStringExtra(EXTRA_TUNNEL_LABEL)
        if (states.size > 1) {
            startInForeground(buildListNotification(states))
        } else {
            startInForeground(buildNotification(tunnelLabel))
        }
        Timber.i("TunnelForegroundService démarré : ${tunnelLabel ?: "tunnels"}")

        if (!reconnectStarted) {
            reconnectStarted = true
            serviceScope.launch { reconnectTunnels.observe() }
        }
        if (!observeStarted) {
            observeStarted = true
            serviceScope.launch {
                tunnelManager.tunnelStates.collect { states ->
                    if (states.isEmpty()) {
                        // Ne s'arrêter que si des tunnels ont réellement existé :
                        // la map est vide juste avant l'enregistrement du premier.
                        if (hadTunnels) stopForegroundAndSelf()
                    } else {
                        hadTunnels = true
                        updateNotification(states)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Ne coupe PAS les tunnels.
     *
     * `onDestroy` est appelé aussi bien sur arrêt volontaire que sur
     * destruction par le système (pression mémoire). Y couper tous les tunnels
     * rendrait le service nuisible : il tuerait ce qu'il est censé protéger.
     * L'arrêt global n'a lieu que sur [ACTION_STOP_ALL], une action explicite
     * de l'utilisateur.
     */
    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Appelé sur Android 15+ quand le système reprend la main sur un service au
     * premier plan qui a dépassé sa durée autorisée. Il reste quelques secondes
     * pour s'arrêter, sinon le système tue le process avec une exception fatale.
     *
     * Le type `specialUse` n'a aujourd'hui aucun quota documenté, donc ce
     * chemin ne devrait pas se produire. Il est implémenté par sécurité : une
     * évolution de la plateforme ne doit pas se traduire par un crash.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        // Ne pas marquer les tunnels en erreur avant l'arret : stopAllTunnels
        // vide la table des etats, le message serait donc ecrit puis
        // immediatement efface sans jamais etre visible.
        Timber.w("TunnelForegroundService: durée maximale atteinte, arrêt des tunnels")
        tunnelManager.stopAllTunnels()
        stopForegroundAndSelf()
    }

    // ── Cycle de vie premier plan ─────────────────────────────────────────────────

    private fun startInForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Notifications ─────────────────────────────────────────────────────────────

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopAllIntent(): PendingIntent = PendingIntent.getService(
        this, 1,
        Intent(this, TunnelForegroundService::class.java).apply { action = ACTION_STOP_ALL },
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun baseNotification(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tunnel)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)

    private fun buildNotification(tunnelLabel: String?): Notification {
        val title = tunnelLabel
            ?.let { getString(R.string.tunnel_notification_title_single, it) }
            ?: getString(R.string.tunnel_notification_active_count, 1)

        return baseNotification()
            .setContentTitle(title)
            .setContentText(getString(R.string.tunnel_notification_text))
            // Le bouton coupe TOUS les tunnels : le libeller « Arreter » a cote
            // d'un seul nom laisserait croire qu'il ne coupe que celui-la.
            .addAction(R.drawable.ic_stop, getString(R.string.tunnel_notification_stop_all), stopAllIntent())
            .build()
    }

    private fun updateNotification(states: Map<String, TunnelState>) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildListNotification(states))
    }

    private fun buildListNotification(states: Map<String, TunnelState>): Notification {
        val activeTunnels = states.values.count { it.status == TunnelStatus.ACTIVE }
        val reconnecting  = states.values.count { it.status == TunnelStatus.RECONNECTING }
        val errors        = states.values.count { it.status == TunnelStatus.ERROR }

        val title = when {
            reconnecting > 0 -> getString(R.string.tunnel_notification_reconnecting, reconnecting)
            errors > 0       -> getString(R.string.tunnel_notification_mixed, activeTunnels, errors)
            else             -> getString(R.string.tunnel_notification_active_count, activeTunnels)
        }

        val style = NotificationCompat.InboxStyle()
        states.values.take(MAX_NOTIFICATION_LINES).forEach { state ->
            val statusIcon = when (state.status) {
                TunnelStatus.ACTIVE       -> "●"
                TunnelStatus.STARTING     -> "◐"
                TunnelStatus.RECONNECTING -> "↻"
                TunnelStatus.ERROR        -> "✖"
                TunnelStatus.STOPPED      -> "○"
            }
            style.addLine("$statusIcon ${state.config.label}")
        }
        if (states.size > MAX_NOTIFICATION_LINES) {
            style.setSummaryText(
                getString(R.string.tunnel_notification_more, states.size - MAX_NOTIFICATION_LINES)
            )
        }

        return baseNotification()
            .setContentTitle(title)
            .setStyle(style)
            .addAction(R.drawable.ic_stop, getString(R.string.tunnel_notification_stop_all), stopAllIntent())
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.tunnel_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.tunnel_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    companion object {
        const val EXTRA_TUNNEL_LABEL = "tunnel_label"
        private const val MAX_NOTIFICATION_LINES = 5
    }
}
