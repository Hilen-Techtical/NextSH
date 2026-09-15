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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import fr.techtical.nextsh.MainActivity
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.ssh.SftpManager
import fr.techtical.nextsh.core.ssh.TransferDirection
import fr.techtical.nextsh.core.ssh.TransferRequest
import fr.techtical.nextsh.core.ssh.TransferTracker
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.domain.usecase.DownloadFileUseCase
import fr.techtical.nextsh.domain.usecase.UploadFileUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 */
@AndroidEntryPoint
class SftpTransferService : Service() {

    @Inject lateinit var downloadFileUseCase: DownloadFileUseCase
    @Inject lateinit var uploadFileUseCase: UploadFileUseCase
    @Inject lateinit var transferTracker: TransferTracker
    @Inject lateinit var sftpManager: SftpManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        when (intent?.action) {
            ACTION_CANCEL_TRANSFER -> {
                val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID) ?: return START_NOT_STICKY
                Timber.i("SftpTransferService: annulation du transfert $transferId")
                // Marquer d'abord : la boucle de copie voit l'annulation même si
                // elle se termine avant que le job ne soit interrompu.
                transferTracker.cancel(transferId)
                runningTransfers[transferId]?.cancel()
                // Cette branche n'appelle jamais startForeground : si le vidage
                // de file s'etait deja termine, le systeme aurait cree une
                // instance neuve qui resterait demarree en arriere-plan.
                if (runningTransfers.isEmpty() && !draining.get()) stopSelf()
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

                val request = TransferRequest(
                    sessionId   = sessionId,
                    remotePath  = remotePath,
                    localUri    = localUri,
                    direction   = direction,
                    fileSize    = fileSize,
                    displayName = displayName,
                )

                // Notification de départ (indéterminée)
                // Action Annuler des la premiere notification : un transfert qui
                // se bloque avant le premier octet est justement celui qu il
                // faut pouvoir annuler.
                startInForeground(buildProgressNotification(displayName, -1, direction, request.id))

                // Enqueue puis traiter
                transferTracker.enqueue(request)
                processQueue()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
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
        stopForegroundAndSelf()
    }

    // ── Traitement de la file ─────────────────────────────────────────────────────

    private fun processQueue() {
        if (!draining.compareAndSet(false, true)) return
        serviceScope.launch {
            try {
                while (true) {
                    val request = transferTracker.dequeue() ?: break
                    runTransfer(request)
                }
            } finally {
                draining.set(false)
                // Re-verifier la file avant de s'arreter : une requete peut
                // avoir ete ajoutee entre la sortie de boucle et la liberation
                // du verrou, auquel cas son appel a processQueue a ete ignore
                // et elle resterait en attente avec le service arrete.
                if (transferTracker.getQueued().isNotEmpty()) {
                    processQueue()
                } else {
                    stopForegroundAndSelf()
                }
            }
        }
    }

    private suspend fun runTransfer(request: TransferRequest) {
        Timber.i("SftpTransferService: démarrage ${request.direction} de ${request.displayName}")

        var lastNotifMs = 0L

        val progressCallback: (Long, Long) -> Unit = { bytes, total ->
            // Throttle : max 1 notification / 500ms
            val now = System.currentTimeMillis()
            if (now - lastNotifMs >= 500L) {
                lastNotifMs = now
                transferTracker.updateProgress(request.id, bytes, total)
                val percent = if (total > 0L) ((bytes.toDouble() / total) * 100).toInt() else -1
                val notif = buildProgressNotification(request.displayName, percent, request.direction, request.id)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notif)
            }
        }

        // Réserver le client SFTP pour toute la durée du transfert : sans cela,
        // quitter l'écran de l'explorateur fermait le client partagé et coupait
        // le téléchargement en cours.
        val acquired = sftpManager.openSftp(request.sessionId)
        if (acquired is SshResult.Error) {
            // Message maison : celui du gestionnaire expose l'identifiant
            // technique de session, illisible pour l'utilisateur. Cas courant :
            // le système a détruit l'activité pendant le sélecteur de fichiers,
            // ce qui a fermé les sessions SSH avec elle.
            transferTracker.fail(request.id, getString(R.string.sftp_transfer_session_lost))
            Timber.w("SftpTransferService: client SFTP indisponible : ${acquired.message}")
            return
        }

        // Job dédié : annuler ce transfert ne doit pas interrompre la file.
        // serviceScope repose sur un SupervisorJob, les frères sont isolés.
        val job = serviceScope.async {
            when (request.direction) {
                TransferDirection.DOWNLOAD -> downloadFileUseCase(
                    sessionId  = request.sessionId,
                    remotePath = request.remotePath,
                    localUri   = request.localUri,
                    onProgress = progressCallback,
                )
                TransferDirection.UPLOAD -> uploadFileUseCase(
                    sessionId  = request.sessionId,
                    remotePath = request.remotePath,
                    localUri   = request.localUri,
                    onProgress = progressCallback,
                )
            }
        }
        runningTransfers[request.id] = job

        val result = try {
            job.await()
        } catch (e: CancellationException) {
            // Distinguer l'annulation de CE transfert de celle de la boucle
            // elle-meme : quand le scope du service est annule (onDestroy),
            // await leve aussi CancellationException. L'avaler ferait tourner
            // la boucle a vide et marquerait toute la file comme annulee, en
            // ecrasant notamment les echecs poses par onTimeout.
            currentCoroutineContext().ensureActive()
            Timber.i("SftpTransferService: ${request.direction} annulé : ${request.displayName}")
            transferTracker.cancel(request.id)
            null
        } finally {
            runningTransfers.remove(request.id)
            // Libere la reservation : le client ne se ferme qu'au dernier
            // partant, l'explorateur peut donc rester ouvert ou non.
            withContext(NonCancellable) { sftpManager.closeSftp(request.sessionId) }
        }

        when (result) {
            is SshResult.Success -> {
                transferTracker.complete(request.id)
                Timber.i("SftpTransferService: ${request.direction} terminé : ${request.displayName}")
            }
            is SshResult.Error -> {
                transferTracker.fail(request.id, result.message)
                Timber.w("SftpTransferService: ${request.direction} échoué : ${result.message}")
            }
            null -> Unit // annulé, déjà consigné
        }
    }

    // ── Cycle de vie premier plan ─────────────────────────────────────────────────

    private fun startInForeground(notification: Notification) {
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
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Notifications ─────────────────────────────────────────────────────────────

    /**
     * Construit la notification de progression.
     *
     * @param displayName nom de fichier tronqué à [MAX_DISPLAY_NAME_LENGTH].
     * @param percent     0–100 pour une barre déterminée, -1 pour indéterminée.
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
        const val EXTRA_SESSION_ID   = "session_id"
        const val EXTRA_REMOTE_PATH  = "remote_path"
        const val EXTRA_LOCAL_URI    = "local_uri"
        const val EXTRA_DIRECTION    = "direction"
        const val EXTRA_FILE_SIZE    = "file_size"
        const val EXTRA_DISPLAY_NAME = "display_name"
    }
}
