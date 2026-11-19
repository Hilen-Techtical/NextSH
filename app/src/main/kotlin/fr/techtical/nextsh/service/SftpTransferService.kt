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
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import fr.techtical.nextsh.MainActivity
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.ssh.SftpManager
import fr.techtical.nextsh.core.ssh.SftpReservations
import fr.techtical.nextsh.core.ssh.SshSessionManager
import fr.techtical.nextsh.core.ssh.TransferDirection
import fr.techtical.nextsh.core.ssh.TransferRequest
import fr.techtical.nextsh.core.ssh.TransferTracker
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.domain.usecase.DownloadFileUseCase
import fr.techtical.nextsh.domain.usecase.UploadFileUseCase
import fr.techtical.nextsh.shared.util.LogId
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val CHANNEL_ID      = "sftp_transfer_channel"
private const val NOTIFICATION_ID = 1002

// Action et extra d annulation : exposes dans le companion pour que
// l explorateur puisse annuler depuis l app, pas seulement depuis la
// notification.



/** Nom d'affichage tronqué (sécurité). */
private const val MAX_DISPLAY_NAME_LENGTH = 20

/**
 * Service au premier plan pour les transferts SFTP (upload et download).
 *
 * Type `dataSync` : un transfert est fini et déclenché par l'utilisateur,
 * c'est exactement le cas d'usage documenté du type. Il est donc soumis au
 * quota de 6 h par tranche de 24 h en arrière-plan à partir de targetSdk 35,
 * d'où [onTimeout]. Le quota se recharge dès que l'utilisateur ramène l'app au
 * premier plan.
 *
 * Suit le pattern de [TunnelForegroundService] :
 * - Channel IMPORTANCE_LOW, VISIBILITY_SECRET
 * - Scope coroutine [SupervisorJob] + [Dispatchers.IO]
 * - S'arrête automatiquement quand la file est vide
 *
 * Il tient aussi l'app au premier plan pendant que l'utilisateur choisit un fichier dans le
 * sélecteur du système ([ACTION_HOLD_FOR_PICKER], voir [PickerHolds]) : le choix fait partie du
 * transfert que l'utilisateur a demandé, et sans service Android coupe la session SSH de
 * l'explorateur quelques secondes après le départ de l'app. Un maintien est fini lui aussi : rendu
 * au retour du sélecteur ([ACTION_RELEASE_PICKER_HOLD]), relâché par un délai de garde, ou rendu
 * quand l'utilisateur balaie l'app des récentes ([onTaskRemoved]). Le service ne s'arrête que sans
 * maintien, sans transfert en cours et sans demande en attente.
 *
 * Fils d'exécution. Les commandes arrivent sur le fil principal, le vidage de la file tourne sur
 * `Dispatchers.IO` : toute décision d'arrêt ou de notification du maintien se prend sur le fil
 * principal, sinon elle se croise avec une commande en cours de traitement (un maintien perdu, une
 * notification orpheline, ou le crash « startForegroundService() did not then call startForeground() »).
 * Un arrêt passe toujours par [stopSelfResult] avec le dernier identifiant de démarrage reçu
 * ([lastStartId]) : il est refusé si une commande plus récente attend d'être livrée.
 */
@AndroidEntryPoint
class SftpTransferService : Service() {

    @Inject lateinit var downloadFileUseCase: DownloadFileUseCase
    @Inject lateinit var uploadFileUseCase: UploadFileUseCase
    @Inject lateinit var transferTracker: TransferTracker
    @Inject lateinit var sftpManager: SftpManager
    @Inject lateinit var sessionManager: SshSessionManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Pour remettre au fil principal une décision prise ailleurs (le délai de garde d'un maintien). */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * L'identifiant de la dernière commande reçue. Lu et écrit sur le fil principal seulement
     * (`onStartCommand` et les décisions d'arrêt), `@Volatile` par prudence : il ne sert qu'à
     * [stopIfIdle], qui ne doit pas arrêter un service dont une commande plus récente attend.
     */
    @Volatile private var lastStartId = 0

    /**
     * Les sélecteurs de fichiers ouverts : chacun tient le service au premier plan et une référence
     * sur sa session. Un maintien expiré peut laisser le service sans rien à faire, d'où [stopIfIdle] :
     * le délai de garde tourne sur `Dispatchers.IO`, la décision est remise au fil principal.
     */
    private val holds = PickerHolds(
        scope = serviceScope,
        acquireSession = { sessionManager.acquire(it) },
        releaseSession = { sessionManager.release(it) },
        onExpired = { mainHandler.post { stopIfIdle() } },
    )

    /**
     * La dernière notification publiée, pour qu'un maintien demandé pendant un vidage ne remplace
     * pas la progression du transfert par la sienne.
     */
    @Volatile private var currentNotification: Notification? = null

    /**
     * Empêche deux vidages de file concurrents.
     *
     * Chaque demande de transfert redémarre le service, donc `onStartCommand`
     * peut être appelé pendant qu'un vidage est déjà en cours. Sans ce verrou,
     * deux boucles se partageaient la file et le `stopSelf` de la première
     * coupait la seconde en plein transfert.
     */
    private val draining = AtomicBoolean(false)

    /** Transferts en cours, pour pouvoir les annuler individuellement. */
    private val runningTransfers = ConcurrentHashMap<String, Deferred<SshResult<Unit>>>()

    // ── Cycle de vie ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_CANCEL_TRANSFER -> {
                val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID) ?: return START_NOT_STICKY
                Timber.i("SftpTransferService: cancellation of transfer ${LogId.of(transferId)}")
                // Marquer d'abord : la boucle de copie voit l'annulation même si
                // elle se termine avant que le job ne soit interrompu.
                transferTracker.cancel(transferId)
                runningTransfers[transferId]?.cancel()
                // Cette branche n'appelle jamais startForeground : si le vidage
                // de file s'etait deja termine, le systeme aurait cree une
                // instance neuve qui resterait demarree en arriere-plan. Par startId, comme le
                // rendu d'un maintien : une commande plus récente garde le service.
                stopIfIdle(startId)
            }
            ACTION_CANCEL_ALL_TRANSFERS -> {
                Timber.i("SftpTransferService: cancellation of every transfer")
                // Same order as above: the tracker first, so that the copy loops see the cancellation even
                // if they end before their job is interrupted, then every job. The waiting requests are
                // cancelled by the tracker and never dequeued. The holds go too: nobody picks a file while
                // the vault is being wiped, and their notification would stay up for ten minutes.
                transferTracker.cancelAll()
                runningTransfers.values.forEach { it.cancel() }
                holds.releaseAll()
                // Like ACTION_CANCEL_TRANSFER: no startForeground on this branch, so a fresh instance
                // stops by itself.
                stopIfIdle(startId)
            }
            ACTION_HOLD_FOR_PICKER -> {
                // startForegroundService promet un startForeground : il passe avant tout, quoi que
                // disent les extras. Pendant un vidage la notification de progression reste en place.
                val foreground = startInForeground(currentNotification.takeIf { draining.get() } ?: buildPickerNotification())
                val holdId = intent.getStringExtra(EXTRA_HOLD_ID)
                if (!foreground || holdId == null) {
                    // Le système refuse le premier plan (quota dataSync épuisé) : pas de maintien, le
                    // sélecteur s'ouvre sans, comme avant. Sans id, rien à tenir non plus.
                    stopIfIdle(startId)
                    return START_NOT_STICKY
                }
                holds.acquire(holdId, intent.getStringExtra(EXTRA_SESSION_ID))
            }
            ACTION_RELEASE_PICKER_HOLD -> {
                intent.getStringExtra(EXTRA_HOLD_ID)?.let { holds.release(it) }
                // Comme l'annulation, cette branche n'appelle jamais startForeground : sur une
                // instance neuve (le service avait été détruit), elle doit s'arrêter d'elle-même.
                // stopSelfResult(startId) et non stopSelf() : le transfert demandé juste après le
                // sélecteur porte un identifiant de démarrage plus récent, et garde le service.
                stopIfIdle(startId)
            }
            else -> {
                // Extraire les paramètres du transfert
                val sessionId   = intent?.getStringExtra(EXTRA_SESSION_ID)   ?: return START_NOT_STICKY
                val remotePath  = intent.getStringExtra(EXTRA_REMOTE_PATH)    ?: return START_NOT_STICKY
                val localUriStr = intent.getStringExtra(EXTRA_LOCAL_URI)      ?: return START_NOT_STICKY
                val directionStr= intent.getStringExtra(EXTRA_DIRECTION)      ?: return START_NOT_STICKY
                val fileSize    = intent.getLongExtra(EXTRA_FILE_SIZE, 0L)
                val rawName     = intent.getStringExtra(EXTRA_DISPLAY_NAME)   ?: remotePath.substringAfterLast('/')

                val localUri    = Uri.parse(localUriStr)
                val direction   = runCatching { TransferDirection.valueOf(directionStr) }
                    .getOrElse { TransferDirection.DOWNLOAD }
                val displayName = truncateDisplayName(rawName)
                // Absent or false unless the user confirmed the replacement of a file: an intent that
                // lacks the extra never overwrites. Read for an upload only, a download has no use for it.
                val replaceExisting = direction == TransferDirection.UPLOAD &&
                    intent.getBooleanExtra(EXTRA_REPLACE_EXISTING, false)

                val request = TransferRequest(
                    sessionId       = sessionId,
                    remotePath      = remotePath,
                    localUri        = localUri,
                    direction       = direction,
                    fileSize        = fileSize,
                    displayName     = displayName,
                    replaceExisting = replaceExisting,
                )

                // Notification de départ (indéterminée)
                // Action Annuler des la premiere notification : un transfert qui
                // se bloque avant le premier octet est justement celui qu il
                // faut pouvoir annuler.
                if (!startInForeground(buildProgressNotification(displayName, -1, direction, request.id))) {
                    // Le système refuse le premier plan (quota dataSync épuisé, app en arrière-plan) :
                    // le transfert ne peut pas tourner, l'explorateur le dit au lieu de planter l'app.
                    transferTracker.enqueue(request)
                    transferTracker.fail(request.id, getString(R.string.sftp_transfer_start_refused))
                    stopIfIdle(startId)
                    return START_NOT_STICKY
                }

                // Enqueue puis traiter
                transferTracker.enqueue(request)
                processQueue()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Les références des maintiens ne se perdent pas avec le service : sinon une session
        // resterait ouverte, sans personne pour la fermer, jusqu'à la fin du processus.
        holds.releaseAll()
        // Une décision remise au fil principal (délai de garde) ne doit pas toucher un service détruit.
        mainHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * L'utilisateur a balayé l'app des récentes. Le sélecteur de fichiers (une autre activité de la
     * tâche) disparaît avec elle et son retour ne viendra jamais : les maintiens sont rendus tout de
     * suite, avec leurs références sur les sessions, au lieu d'attendre le délai de garde de dix
     * minutes avec une notification orpheline. Les transferts en cours continuent (le service ne
     * s'arrête pas avec la tâche) et le service s'arrête s'il n'a plus rien à faire.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        holds.releaseAll()
        stopIfIdle()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Appelé sur Android 15+ quand le quota `dataSync` de 6 h est épuisé.
     *
     * Il reste quelques secondes pour s'arrêter, sinon le système tue le
     * process avec une exception fatale comptée dans les vitals Play. Les
     * transferts non terminés sont marqués en échec avec un message explicite :
     * repasser l'app au premier plan recharge le quota.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Timber.w("SftpTransferService: quota de durée atteint, arrêt des transferts")
        runningTransfers.values.forEach { it.cancel() }
        transferTracker.failAllUnfinished(getString(R.string.sftp_transfer_timeout))
        holds.releaseAll()
        // Un stopSelf() sans condition, le seul du service : le système laisse quelques secondes pour
        // s'arrêter, et une commande en attente (stopSelfResult le refuserait) ne doit pas les prendre.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Traitement de la file ─────────────────────────────────────────────────────

    private fun processQueue() {
        if (!draining.compareAndSet(false, true)) return
        serviceScope.launch {
            // Une réservation par session pour toute la durée du vidage, pas une par transfert : entre
            // deux transferts d'une même session il n'y aurait sinon plus personne pour la tenir
            // (explorateur quitté, onglet fermé), et la fin du premier la fermerait sous le second.
            val reservations = SftpReservations(sftpManager)
            try {
                while (true) {
                    val request = transferTracker.dequeue() ?: break
                    runTransfer(request, reservations)
                    // Rendue dès qu'aucune demande en attente ne vise plus la session, pour toutes les
                    // sessions tenues : une demande annulée pendant le transfert d'une autre session
                    // ne doit pas garder la sienne ouverte jusqu'à la fin du vidage.
                    reservations.releaseIdle(transferTracker.getQueued().map { it.sessionId }.toSet())
                }
            } finally {
                // Même vidage annulé (service détruit) : aucune session ne reste tenue par un
                // vidage qui n'existe plus.
                withContext(NonCancellable) { reservations.releaseAll() }
                draining.set(false)
                // La suite se décide sur le fil principal, là où les commandes sont traitées : sur le
                // fil IO elle se croisait avec une commande en cours (un maintien perdu, ou une
                // notification de maintien posée après l'arrêt). Vidage annulé (service détruit) :
                // rien à décider, le service n'existe plus.
                if (currentCoroutineContext().isActive) {
                    withContext(Dispatchers.Main) { onDrainEnded() }
                }
            }
        }
    }

    /**
     * Fin d'un vidage de file, sur le fil principal.
     *
     * Re-vérifie la file avant de s'arrêter : une requête peut avoir été ajoutée entre la sortie de
     * boucle et la libération du verrou, auquel cas son appel à [processQueue] a été ignoré et elle
     * resterait en attente avec le service arrêté. Un vidage que cette requête (ou une commande
     * traitée entre-temps) a relancé décide lui-même à sa fin.
     */
    private fun onDrainEnded() {
        when {
            transferTracker.getQueued().isNotEmpty() -> processQueue()
            draining.get()                           -> Unit
            holds.isEmpty()                          -> stopIfIdle()
            // Un sélecteur de fichiers est encore ouvert : le service reste au premier plan, avec la
            // notification du maintien et non celle du dernier transfert. Le dernier des deux (fin du
            // vidage, rendu du maintien) arrête le service, voir stopIfIdle.
            else                                     -> showNotification(buildPickerNotification())
        }
    }

    /**
     * Vrai quand le service n'a plus rien à servir : aucun sélecteur de fichiers ouvert, aucun
     * vidage de file en cours, aucun transfert en cours et aucune demande en attente.
     */
    private fun isIdle(): Boolean {
        return holds.isEmpty() && !draining.get() && runningTransfers.isEmpty() && transferTracker.getQueued().isEmpty()
    }

    /**
     * Arrête le service s'il est au repos ([isIdle]). À appeler sur le fil principal.
     *
     * Par [stopSelfResult] avec [startId], par défaut le dernier identifiant reçu ([lastStartId]) :
     * l'arrêt est refusé si une commande plus récente attend d'être livrée, un transfert demandé au
     * retour du sélecteur par exemple, ou un maintien dont le `startForegroundService` n'a pas encore
     * son `startForeground` (le système tuerait l'app). Une commande qu'on vient de traiter passe son
     * propre identifiant.
     */
    private fun stopIfIdle(startId: Int = lastStartId) {
        if (!isIdle()) return
        if (stopSelfResult(startId)) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    private suspend fun runTransfer(request: TransferRequest, reservations: SftpReservations) {
        Timber.i("SftpTransferService: starting ${request.direction} transfer ${LogId.of(request.id)}")

        var lastNotifMs = 0L

        val progressCallback: (Long, Long) -> Unit = { bytes, total ->
            // Throttle : max 1 notification / 500ms
            val now = System.currentTimeMillis()
            if (now - lastNotifMs >= 500L) {
                lastNotifMs = now
                transferTracker.updateProgress(request.id, bytes, total)
                val percent = if (total > 0L) ((bytes.toDouble() / total) * 100).toInt() else -1
                showNotification(buildProgressNotification(request.displayName, percent, request.direction, request.id))
            }
        }

        // Réserver la session SSH et son client SFTP pour toute la durée du transfert : sans cela,
        // quitter l'écran de l'explorateur fermait le client partagé, et fermer l'onglet terminal
        // fermait la session, ce qui coupait le téléchargement en cours. Le transfert est un
        // utilisateur de la session comme un autre : le vidage prend la sienne au premier transfert
        // de la session et la rend avec le dernier (voir SftpReservations).
        when (val reserved = reservations.obtain(request.sessionId)) {
            is SshResult.Success -> Unit
            is SshResult.Error -> {
                // Message maison : celui du gestionnaire expose l'identifiant
                // technique de session, illisible pour l'utilisateur. Cas courant :
                // le système a détruit l'activité pendant le sélecteur de fichiers,
                // ce qui a fermé les sessions SSH avec elle.
                transferTracker.fail(request.id, getString(R.string.sftp_transfer_session_lost))
                Timber.w("SftpTransferService: SFTP client unavailable (${reserved.code})")
                return
            }
        }

        // The reservation above waits for the network: a cancellation of everything (the wipe of the vault)
        // that came meanwhile found no job to cancel. The tracker says it: the copy does not start.
        if (transferTracker.isCancelled(request.id)) {
            Timber.i("SftpTransferService: ${request.direction} transfer ${LogId.of(request.id)} cancelled before its copy")
            return
        }

        val result = try {
            // Job dédié : annuler ce transfert ne doit pas interrompre la file.
            // serviceScope repose sur un SupervisorJob, les frères sont isolés.
            transferTracker.copyStarted(request.id)
            val job = serviceScope.async {
                when (request.direction) {
                    TransferDirection.DOWNLOAD -> downloadFileUseCase(
                        sessionId  = request.sessionId,
                        remotePath = request.remotePath,
                        localUri   = request.localUri,
                        onProgress = progressCallback,
                    )
                    TransferDirection.UPLOAD -> uploadFileUseCase(
                        sessionId       = request.sessionId,
                        remotePath      = request.remotePath,
                        localUri        = request.localUri,
                        replaceExisting = request.replaceExisting,
                        onProgress      = progressCallback,
                    )
                }
            }
            // The end of the copy itself, not of the wait for it: a cancelled job ends a moment after its
            // await does (see TransferTracker.copying).
            job.invokeOnCompletion { transferTracker.copyEnded(request.id) }
            runningTransfers[request.id] = job
            // A cancellation of everything that fell between the check above and the registration of the
            // job found nothing to cancel: look again now that the job can be found, and cancel it.
            if (transferTracker.isCancelled(request.id)) job.cancel()
            job.await()
        } catch (e: CancellationException) {
            // Distinguer l'annulation de CE transfert de celle de la boucle
            // elle-meme : quand le scope du service est annule (onDestroy),
            // await leve aussi CancellationException. L'avaler ferait tourner
            // la boucle a vide et marquerait toute la file comme annulee, en
            // ecrasant notamment les echecs poses par onTimeout.
            currentCoroutineContext().ensureActive()
            Timber.i("SftpTransferService: ${request.direction} transfer ${LogId.of(request.id)} cancelled")
            transferTracker.cancel(request.id)
            null
        } finally {
            // La reservation de la session reste au vidage, qui la rend, voir processQueue.
            runningTransfers.remove(request.id)
        }

        when (result) {
            is SshResult.Success -> {
                transferTracker.complete(request.id)
                Timber.i("SftpTransferService: ${request.direction} transfer ${LogId.of(request.id)} completed")
            }
            is SshResult.Error -> {
                transferTracker.fail(request.id, result.message)
                Timber.w("SftpTransferService: ${request.direction} transfer ${LogId.of(request.id)} failed (${result.code})")
            }
            null -> Unit // annulé, déjà consigné
        }
    }

    // ── Cycle de vie premier plan ─────────────────────────────────────────────────

    /**
     * Passe le service au premier plan avec [notification].
     *
     * Faux quand le système refuse : à partir d'Android 15, `startForeground` d'un service `dataSync`
     * lève une `ForegroundServiceStartNotAllowedException` (sous-classe de [IllegalStateException],
     * API 35) une fois le quota de 6 h épuisé, et l'app plantait. Le service n'est alors pas au
     * premier plan et n'a pas de notification : l'appelant ne prend pas de maintien et ne lance pas
     * de transfert.
     */
    private fun startInForeground(notification: Notification): Boolean {
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
            currentNotification = notification
            true
        } catch (e: IllegalStateException) {
            // Texte fixe : le message de l'exception n'est pas journalisé.
            Timber.w("SftpTransferService: foreground refused (${errorClass(e)})")
            false
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────────

    /** Remplace la notification du service au premier plan, qui garde le même identifiant. */
    private fun showNotification(notification: Notification) {
        currentNotification = notification
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    /**
     * Notification du maintien pendant le choix d'un fichier : même canal, même identifiant et même
     * allure que celle d'un transfert, qui la remplace dès que le transfert démarre. Sans barre de
     * progression ni action, il n'y a rien à annuler : le retour du sélecteur la fait disparaître.
     */
    private fun buildPickerNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.sftp_picker_notification_title))
            .setContentText(getString(R.string.sftp_transfer_notification_title))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    /**
     * Construit la notification de progression.
     *
     * @param displayName nom de fichier tronqué à [MAX_DISPLAY_NAME_LENGTH].
     * @param percent     de 0 à 100 pour une barre déterminée, -1 pour indéterminée.
     * @param direction   sens du transfert pour le titre.
     * @param transferId  identifiant du transfert, pour l'action Annuler. Null
     *                    tant que la requête n'est pas encore enregistrée.
     */
    private fun buildProgressNotification(
        displayName: String,
        percent: Int,
        direction: TransferDirection,
        transferId: String?,
    ): Notification {
        val titleRes = when (direction) {
            TransferDirection.DOWNLOAD -> R.string.sftp_transfer_downloading
            TransferDirection.UPLOAD   -> R.string.sftp_transfer_uploading
        }
        val title = getString(titleRes, displayName)

        val contentText = if (percent in 0..100) {
            getString(R.string.sftp_transfer_progress, percent)
        } else {
            getString(R.string.sftp_transfer_notification_title)
        }

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setProgress(
                100,
                percent.coerceAtLeast(0),
                percent < 0,  // indeterminate si -1
            )

        // Action Annuler : le service savait déjà traiter ACTION_CANCEL_TRANSFER
        // mais rien ne l'émettait, l'annulation était donc inatteignable.
        if (transferId != null) {
            val cancelIntent = PendingIntent.getService(
                this,
                transferId.hashCode(),
                Intent(this, SftpTransferService::class.java).apply {
                    action = ACTION_CANCEL_TRANSFER
                    putExtra(EXTRA_TRANSFER_ID, transferId)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(
                R.drawable.ic_stop,
                getString(R.string.sftp_transfer_cancel_action),
                cancelIntent,
            )
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.sftp_transfer_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // ── Utilitaires ───────────────────────────────────────────────────────────────

    /**
     * Tronque le nom d'affichage à [MAX_DISPLAY_NAME_LENGTH] caractères (sécurité :
     * évite d'exposer des noms de chemins longs dans les notifications).
     */
    private fun truncateDisplayName(name: String): String =
        if (name.length > MAX_DISPLAY_NAME_LENGTH) {
            name.take(MAX_DISPLAY_NAME_LENGTH) + "…"
        } else {
            name
        }

    companion object {
        const val ACTION_CANCEL_TRANSFER = "fr.techtical.nextsh.CANCEL_TRANSFER"
        const val EXTRA_TRANSFER_ID      = "transfer_id"

        /** Cancels every transfer and releases the holds: the closing of the "Wipe the vault" action. */
        const val ACTION_CANCEL_ALL_TRANSFERS = "fr.techtical.nextsh.CANCEL_ALL_TRANSFERS"

        /** Garde le service au premier plan pendant le sélecteur de fichiers, voir [PickerHolds]. */
        const val ACTION_HOLD_FOR_PICKER      = "fr.techtical.nextsh.HOLD_FOR_PICKER"
        const val ACTION_RELEASE_PICKER_HOLD  = "fr.techtical.nextsh.RELEASE_PICKER_HOLD"
        const val EXTRA_HOLD_ID               = "hold_id"

        const val EXTRA_SESSION_ID   = "session_id"
        const val EXTRA_REMOTE_PATH  = "remote_path"
        const val EXTRA_LOCAL_URI    = "local_uri"
        const val EXTRA_DIRECTION    = "direction"
        const val EXTRA_FILE_SIZE    = "file_size"
        const val EXTRA_DISPLAY_NAME = "display_name"

        /**
         * Boolean extra of an upload: true only after the user confirmed the replacement of a file
         * that has the same name. Absent means false, and then the server refuses a name already taken.
         * True, the file is replaced through a hidden temporary file of its folder, which takes its place
         * once complete: a transfer that is cut or cancelled leaves the old file as it was (see
         * [fr.techtical.nextsh.core.ssh.SftpManager.writeFile]).
         */
        const val EXTRA_REPLACE_EXISTING = "replace_existing"
    }
}
