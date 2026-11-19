// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import fr.techtical.nextsh.shared.util.errorClass
import net.schmizz.sshj.connection.channel.direct.Session
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Pont entre un canal SSH SSHJ (Session.Shell) et le TerminalEmulator Termux.
 *
 * TerminalSession normalement fork un sous-processus local via JNI/PTY.
 * Ici, le constructeur monte lui-même l'émulateur (initializeEmulator() n'est
 * plus qu'un garde-fou) et branche à la place les flux stdin/stdout du canal SSH.
 *
 * Architecture :
 *  - SSH stdout → thread lecteur → mProcessToTerminalIOQueue → Handler main thread → mEmulator.append()
 *  - Frappes utilisateur → write() → SSH stdin
 *  - Resize → updateSize() → session.changeWindowDimensions()
 *
 * L'émulateur et le thread lecteur démarrent à la construction, à la taille du PTY que
 * `SshSessionManager.openShell` vient d'ouvrir ([initialColumns] x [initialRows], sans dimension en
 * pixels comme le PTY), et non au premier `updateSize` : une session vit plus longtemps que sa vue,
 * et peut n'en avoir jamais (un onglet connecté pendant que l'app est en arrière-plan). Sans
 * émulateur, la fin du shell ferait planter le Handler de Termux (`mEmulator.append` sur null) et
 * sans lecteur, la fin du transport ne serait jamais vue. La première vue qui mesure le terminal
 * passe alors par la branche de redimensionnement de [updateSize], qui envoie sa taille au serveur.
 *
 * À construire sur le thread principal : le Handler de Termux prend la boucle du fil qui construit.
 */
class SshTerminalSession(
    private val sshShell: Session.Shell,
    private val sessionLabel: String,
    client: TerminalSessionClient,
    transcriptRows: Int,
    initialColumns: Int = DEFAULT_PTY_COLUMNS,
    initialRows: Int = DEFAULT_PTY_ROWS,
) : TerminalSession(
    /* shellPath */ "/system/bin/sh",   // jamais exécuté, juste pour satisfaire le super()
    /* cwd       */ "/",
    /* args      */ emptyArray(),
    /* env       */ emptyArray(),
    /* transcriptRows */ transcriptRows,
    /* client    */ client,
) {

    private val sshRunning = AtomicBoolean(true)

    /**
     * Single-thread executor pour les écritures SSH, évite NetworkOnMainThreadException.
     * Le nom du thread ne porte pas le libellé de l'hôte : il apparaît dans les journaux de
     * crash (« FATAL EXCEPTION: <thread> ») et dans les traces ANR.
     */
    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SshWriter").apply { isDaemon = true }
    }

    /**
     * Callback pour rafraîchir le TerminalView quand de nouvelles données arrivent.
     * Posé par [bindView] quand une vue affiche la session, retiré par [unbindView] quand elle
     * est libérée. Lu et appelé sur le thread principal uniquement.
     */
    var onScreenUpdate: (() -> Unit)? = null
        private set

    /**
     * Appelé quand le programme distant modifie la palette de l'émulateur.
     *
     * Le cas courant est une remise à zéro : ncurses, donc nano, htop ou vim,
     * émet la séquence de réinitialisation des couleurs à chaque redessin
     * complet, notamment après un redimensionnement du terminal. Sans réaction,
     * le thème choisi par l'utilisateur pour cet hôte est écrasé par la palette
     * par défaut de l'émulateur.
     *
     * Le code UI y rebranche l'application du thème, par [bindView].
     */
    var onPaletteOverridden: (() -> Unit)? = null
        private set

    /** La vue qui a posé [onScreenUpdate] et [onPaletteOverridden], null si aucune ne les tient. */
    private var viewOwner: Any? = null

    /**
     * Une vue affiche cette session : elle en reçoit les mises à jour d'écran et les remises à zéro
     * de la palette. [owner] est la vue elle-même, la clé de [unbindView].
     *
     * Une session survit à sa vue (l'onglet reste ouvert quand l'écran du terminal est quitté, et
     * le passage plein écran vers split recrée la vue). Ces callbacks capturent la vue, donc son
     * Activity : un callback jamais retiré la retiendrait en mémoire jusqu'à la fermeture de
     * l'onglet. Une seconde vue qui se lie avant que la première soit libérée prend la place de la
     * première, et c'est la libération de celle-ci qui ne doit alors rien défaire.
     */
    fun bindView(owner: Any, onScreenUpdate: () -> Unit, onPaletteOverridden: () -> Unit) {
        viewOwner = owner
        this.onScreenUpdate = onScreenUpdate
        this.onPaletteOverridden = onPaletteOverridden
    }

    /**
     * La vue [owner] ne montre plus cette session (libérée, ou passée à une autre session). Ne
     * retire les callbacks que si c'est encore elle qui les possède : une vue plus récente, liée
     * entre-temps, les garde. Sans effet sinon.
     */
    fun unbindView(owner: Any) {
        if (viewOwner !== owner) return
        viewOwner = null
        onScreenUpdate = null
        onPaletteOverridden = null
    }

    /**
     * Démarre l'émulateur et le lecteur. Ce bloc vient après tous les champs de la classe et doit y
     * rester : le lecteur démarre ici et utilise [sshRunning] et les champs de Termux, qui doivent
     * tous exister (Kotlin initialise les champs et les blocs `init` dans l'ordre du texte).
     */
    init {
        mSessionName = sessionLabel
        startEmulator(initialColumns, initialRows, 0, 0)
    }

    /**
     * Initialise l'émulateur terminal et démarre les threads I/O SSH.
     * Override complet : ne fait PAS appel à JNI.createSubprocess().
     *
     * Le constructeur l'a déjà fait, [updateSize] ne l'appelle plus que par prudence, si l'émulateur
     * manquait. Un second appel ne recrée rien.
     */
    override fun initializeEmulator(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        if (mEmulator == null) startEmulator(columns, rows, cellWidthPixels, cellHeightPixels)
    }

    private fun startEmulator(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        mEmulator = TerminalEmulator(
            /* session      */ this,
            /* columns      */ columns,
            /* rows         */ rows,
            /* cellWidthPx  */ cellWidthPixels,
            /* cellHeightPx */ cellHeightPixels,
            /* transcriptRows */ mTranscriptRows,
            /* client       */ mClient,
        )

        mShellPid = 1
        mClient.setTerminalShellPid(this, mShellPid)

        startSshReaderThread()
    }

    /**
     * Override updateSize pour :
     *  1. Initialiser l'émulateur s'il manquait (par prudence : le constructeur le démarre)
     *  2. Sinon, redimensionner l'émulateur ET envoyer le resize PTY au serveur SSH. C'est la voie
     *     du premier appel d'une vue, qui apprend ainsi au serveur la vraie taille du terminal.
     */
    override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels)
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels)
            // Un shell fini n'a plus personne à prévenir, et son écriture est fermée par disconnect().
            if (!sshRunning.get()) return
            // Envoyer le resize au serveur SSH sur un thread IO (évite NetworkOnMainThreadException)
            try {
                writeExecutor.execute {
                    try {
                        sshShell.changeWindowDimensions(columns, rows, 0, 0)
                    } catch (e: IOException) {
                        Timber.w("SSH resize failed (${errorClass(e)})")
                    }
                }
            } catch (_: RejectedExecutionException) {
                // disconnect() a fermé l'exécuteur entre le test ci-dessus et l'appel.
            }
        }
    }

    /**
     * Override pour ne PAS appeler JNI.close() : aucun file descriptor PTY n'a été créé.
     */
    override fun cleanupResources(exitStatus: Int) {
        synchronized(this) {
            mShellPid = -1
            mShellExitStatus = exitStatus
        }
        mTerminalToProcessIOQueue.close()
        mProcessToTerminalIOQueue.close()
        // JNI.close() intentionnellement NON appelé, pas de PTY ouvert
    }

    /**
     * Envoi de l'input utilisateur vers le canal SSH (stdin du shell distant).
     */
    override fun write(data: ByteArray, offset: Int, count: Int) {
        if (!sshRunning.get()) return
        // Copier les données car le buffer appelant peut être réutilisé
        val copy = data.copyOfRange(offset, offset + count)
        try {
            writeExecutor.execute {
                try {
                    sshShell.outputStream.write(copy)
                    sshShell.outputStream.flush()
                } catch (e: IOException) {
                    Timber.w("SSH write error (${errorClass(e)})")
                    onSshDisconnected()
                }
            }
        } catch (_: RejectedExecutionException) {
            // disconnect() a fermé l'exécuteur entre le test ci-dessus et l'appel : la frappe est perdue
            // avec le shell, comme toute frappe après la fin.
        }
    }

    /**
     * Déconnecte proprement le canal SSH.
     *
     * Le thread d'écriture s'arrête même quand le shell a déjà fini seul ([onSshDisconnected] a pris
     * la main) : fermer un onglet déconnecté, ou le remplacer à la reconnexion, ne doit pas laisser
     * son thread `SshWriter` derrière lui.
     */
    fun disconnect() {
        writeExecutor.shutdownNow()
        if (sshRunning.compareAndSet(true, false)) {
            try {
                sshShell.close()
            } catch (_: Exception) {}
            mMainThreadHandler.sendMessage(
                mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, 0)
            )
        }
    }

    // isRunning() n'est plus overridé : le parent utilise mShellPid != -1
    // ce qui est correctement géré par cleanupResources()

    // ── Callbacks émulateur → client SSH ─────────────────────────────────────────

    override fun titleChanged(oldTitle: String?, newTitle: String?) {
        mClient.onTitleChanged(this)
    }

    override fun onCopyTextToClipboard(text: String?) {
        mClient.onCopyTextToClipboard(this, text)
    }

    override fun onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this)
    }

    override fun onBell() {
        mClient.onBell(this)
    }

    override fun onColorsChanged() {
        mClient.onColorsChanged(this)
        onPaletteOverridden?.invoke()
    }

    // ── Thread lecteur SSH ────────────────────────────────────────────────────────

    private fun startSshReaderThread() {
        thread(name = "SshReader", isDaemon = true) {
            val buffer = ByteArray(4096)
            try {
                val inputStream = sshShell.inputStream
                while (sshRunning.get()) {
                    val read = inputStream.read(buffer)
                    if (read == -1) break
                    if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) break
                    mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT)
                }
            } catch (e: IOException) {
                if (sshRunning.get()) {
                    Timber.w("SSH read error (${errorClass(e)})")
                }
            } finally {
                onSshDisconnected()
            }
        }
    }

    private fun onSshDisconnected() {
        if (sshRunning.compareAndSet(true, false)) {
            mMainThreadHandler.sendMessage(
                mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, 0)
            )
        }
    }
}
