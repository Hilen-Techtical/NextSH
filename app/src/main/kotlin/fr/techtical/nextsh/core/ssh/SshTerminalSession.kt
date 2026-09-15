// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import net.schmizz.sshj.connection.channel.direct.Session
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Pont entre un canal SSH SSHJ (Session.Shell) et le TerminalEmulator Termux.
 *
 * TerminalSession normalement fork un sous-processus local via JNI/PTY.
 * Ici, on surcharge initializeEmulator() pour bypasser ce fork et brancher
 * à la place les flux stdin/stdout du canal SSH.
 *
 * Architecture :
 *  - SSH stdout → thread lecteur → mProcessToTerminalIOQueue → Handler main thread → mEmulator.append()
 *  - Frappes utilisateur → write() → SSH stdin
 *  - Resize → updateSize() → session.changeWindowDimensions()
 */
class SshTerminalSession(
    private val sshShell: Session.Shell,
    private val sessionLabel: String,
    client: TerminalSessionClient,
    transcriptRows: Int = 2000,
) : TerminalSession(
    /* shellPath */ "/system/bin/sh",   // jamais exécuté, juste pour satisfaire le super()
    /* cwd       */ "/",
    /* args      */ emptyArray(),
    /* env       */ emptyArray(),
    /* transcriptRows */ transcriptRows,
    /* client    */ client,
) {

    private val sshRunning = AtomicBoolean(true)

    /** Single-thread executor pour les écritures SSH, évite NetworkOnMainThreadException */
    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SshWriter[$sessionLabel]").apply { isDaemon = true }
    }

    /**
     * Callback pour rafraîchir le TerminalView quand de nouvelles données arrivent.
     * Doit être set par le code UI après création de la vue.
     */
    var onScreenUpdate: (() -> Unit)? = null

    /**
     * Appelé quand le programme distant modifie la palette de l'émulateur.
     *
     * Le cas courant est une remise à zéro : ncurses, donc nano, htop ou vim,
     * émet la séquence de réinitialisation des couleurs à chaque redessin
     * complet, notamment après un redimensionnement du terminal. Sans réaction,
     * le thème choisi par l'utilisateur pour cet hôte est écrasé par la palette
     * par défaut de l'émulateur.
     *
     * Le code UI y rebranche l'application du thème.
     */
    var onPaletteOverridden: (() -> Unit)? = null

    init {
        mSessionName = sessionLabel
    }

    /**
     * Initialise l'émulateur terminal et démarre les threads I/O SSH.
     * Override complet : ne fait PAS appel à JNI.createSubprocess().
     */
    override fun initializeEmulator(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
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
     *  1. Initialiser l'émulateur si besoin (premier appel)
     *  2. Sinon, redimensionner l'émulateur ET envoyer le resize PTY au serveur SSH
     */
    override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels)
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels)
            // Envoyer le resize au serveur SSH sur un thread IO (évite NetworkOnMainThreadException)
            writeExecutor.execute {
                try {
                    sshShell.changeWindowDimensions(columns, rows, 0, 0)
                } catch (e: IOException) {
                    Timber.w("SSH resize failed: ${e.message}")
                }
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
        writeExecutor.execute {
            try {
                sshShell.outputStream.write(copy)
                sshShell.outputStream.flush()
            } catch (e: IOException) {
                Timber.w("SSH write error: ${e.message}")
                onSshDisconnected()
            }
        }
    }

    /** Déconnecte proprement le canal SSH. */
    fun disconnect() {
        if (sshRunning.compareAndSet(true, false)) {
            writeExecutor.shutdownNow()
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
        thread(name = "SshReader[$sessionLabel]", isDaemon = true) {
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
                    Timber.w("SSH read error: ${e.message}")
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
