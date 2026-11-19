// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.domain.model.SftpFile
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.util.LogId
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.xfer.FilePermission
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import timber.log.Timber
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a background transfer holds for as long as it runs: one reference on the SSH session and one
 * user of its SFTP client, both taken by [SftpManager.reserve]. Closing the terminal tab, or leaving
 * the browser, then no longer closes the session or the client under a running transfer.
 */
class SftpReservation internal constructor(
    private val sftpManager: SftpManager,
    private val sessionManager: SshSessionManager,
    val sessionId: String,
) {
    private val released = AtomicBoolean(false)

    /**
     * Gives back what [SftpManager.reserve] took, the SFTP client first (it closes on a session that
     * is still open) and the session after, which closes with its last reference. Idempotent: a
     * reservation is given back once, whoever calls.
     */
    suspend fun release() {
        if (!released.compareAndSet(false, true)) return
        try {
            sftpManager.closeSftp(sessionId)
        } finally {
            sessionManager.release(sessionId)
        }
    }
}

/**
 * A failure of the source of an upload (the document the system file picker handed over: a provider
 * that fails, an access that was revoked, a pipe closed with an error), not of the connection. It only
 * exists to tell such an [IOException] apart from a lost SFTP channel, which is an [IOException] too:
 * [LocalSource] throws it and [SftpManager.writeFile] reports it as [SFTP_SOURCE_UNAVAILABLE_MESSAGE].
 * Its message is fixed: the cause (which can quote a document name or a path) is only kept for
 * debugging and never shown nor logged.
 */
private class LocalSourceException(cause: IOException) : IOException("local source failed", cause)

/** Input stream handed to an upload: its failures are the device's, see [LocalSourceException]. Never closes [delegate]. */
private class LocalSource(private val delegate: InputStream) : InputStream() {
    override fun read(): Int = marking { delegate.read() }
    override fun read(b: ByteArray): Int = marking { delegate.read(b) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = marking { delegate.read(b, off, len) }

    private inline fun <T> marking(block: () -> T): T = try {
        block()
    } catch (e: LocalSourceException) {
        throw e
    } catch (e: IOException) {
        throw LocalSourceException(e)
    }
}

/**
 * The source of an upload ended before the size its provider announced (a pipe closed without an error
 * by a provider that failed): what arrived is shorter than the file that was chosen. Not an
 * [IOException], so that it is never taken for a lost connection. Reported as
 * [SFTP_SOURCE_TRUNCATED_MESSAGE], or [SFTP_SOURCE_TRUNCATED_IN_PLACE_MESSAGE] when an in-place write
 * had already rewritten part of the remote file; the upload is cancelled, never completed.
 */
private class SourceTruncatedException(
    /** True when the file was written in place: the old file was emptied and is only partly rewritten. */
    val inPlace: Boolean = false,
) : Exception("source ended before the announced size")

/**
 * Gère les connexions SFTP au-dessus des sessions SSH actives.
 *
 * Chaque session SSH peut avoir un SFTPClient associé, récupéré depuis
 * [SshSessionManager.getClient]. Toutes les opérations I/O sont exécutées
 * sur [Dispatchers.IO]. Retourne [SshResult], jamais d'exception vers l'UI.
 *
 * Sécurité :
 * - [sanitizePath] rejette les null-bytes et les traversées `..` hors de la racine.
 *   Les chemins sont littéraux : aucun décodage URL (`+` et `%XX` sont des
 *   caractères de nom de fichier ordinaires).
 * - Un nom saisi (nouveau dossier, nouveau fichier, renommage) est refusé par [isValidEntryName]
 *   quand ce n'est pas un nom nu, jamais corrigé : `a/b` ne devient pas `ab`. Le nom est accolé tel
 *   quel au dossier parent, seul le parent passe par [sanitizePath], et un nom déjà pris est cherché
 *   sur le serveur avant la création ou le renommage.
 * - [sanitizeFilename] supprime les caractères dangereux et les séparateurs : il ne sert plus de
 *   validateur aux noms saisis.
 */
@Singleton
class SftpManager @Inject constructor(
    private val sessionManager: SshSessionManager,
) {

    private val sftpClients = ConcurrentHashMap<String, SFTPClient>()
    private val openMutex = Mutex()

    /** Guards [tempNamesUnderWay] and [tempNamesGone], which change together. */
    private val tempNamesLock = Any()

    /**
     * The NAMES (last segment, never the path: a folder may be reached through a link, and a replacement
     * may follow a link) of the temporary files that a replacement of this app is writing right now (all
     * sessions): added once the exclusive open succeeded, moved out when the replacement ends.
     */
    private val tempNamesUnderWay = HashSet<String>()

    /**
     * The names of the last [TEMP_NAMES_GONE_KEPT] temporary files of this app that are known to be gone
     * from the server: renamed over their target, or deleted with success. Without them, a listing that
     * started while a temporary file was being written and ended after it vanished would still hold it.
     * A temporary file left behind (channel lost, delete refused) is NOT here: it is a real leftover.
     */
    private val tempNamesGone = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > TEMP_NAMES_GONE_KEPT
    }

    /**
     * The names of the temporary files of this app that are being written or are known to be gone, as of
     * now. The browser reads it before a listing and after it, and leaves out of the leftovers of
     * interrupted uploads a file of that listing whose name is in the union.
     */
    fun knownUploadTempNames(): Set<String> = synchronized(tempNamesLock) { tempNamesUnderWay + tempNamesGone.keys }

    private fun tempStarted(name: String) {
        synchronized(tempNamesLock) { tempNamesUnderWay.add(name) }
    }

    /** The replacement ended. [gone]: the file no longer exists (renamed or deleted), else it is left behind. */
    private fun tempEnded(name: String, gone: Boolean) {
        synchronized(tempNamesLock) {
            // Both in one step: a reader never sees the name in neither set while the file is vanishing.
            if (gone) tempNamesGone[name] = true
            tempNamesUnderWay.remove(name)
        }
    }

    init {
        // A session that ends takes its SFTP channel with it. Without this, [openSftp] handed out
        // the client of a dead session as if it were open, and the browser never noticed.
        sessionManager.addSessionEndListener { sessionId, _ -> dropClient(sessionId) }
    }

    /**
     * Nombre d'utilisateurs actifs par session SFTP.
     *
     * Le client SFTP est partagé entre l'explorateur et le service de
     * transfert. Sans ce comptage, quitter l'écran de l'explorateur fermait le
     * client sous les pieds d'un transfert en cours : le telechargement mourait
     * des que l'utilisateur naviguait ailleurs, ce qui vide de son sens le
     * service au premier plan.
     *
     * [openSftp] incremente, [closeSftp] decremente et ne ferme reellement
     * qu'au dernier partant.
     */
    private val sessionUsers = ConcurrentHashMap<String, Int>()

    // ── Cycle de vie ──────────────────────────────────────────────────────────────

    /**
     * Ouvre un SFTPClient pour la session donnée.
     * Sans effet (et succès) si un client est déjà ouvert.
     * Thread-safe : un [Mutex] empêche l'ouverture concurrente du même sessionId.
     */
    suspend fun openSftp(sessionId: String): SshResult<Unit> = withContext(Dispatchers.IO) {
        openMutex.withLock {
            if (sftpClients.containsKey(sessionId)) {
                sessionUsers[sessionId] = (sessionUsers[sessionId] ?: 0) + 1
                return@withContext SshResult.Success(Unit)
            }
            val client = sessionManager.getClient(sessionId)
                ?: return@withContext SshResult.Error(
                    SshErrorCode.UNKNOWN,
                    SFTP_SESSION_NOT_FOUND_MESSAGE
                )
            return@withContext try {
                val sftp = client.newSFTPClient()
                sftpClients[sessionId] = sftp
                sessionUsers[sessionId] = 1
                // The session may have ended while the channel was being opened, after which no
                // end notification will come for this client: do not keep it.
                if (sessionManager.getClient(sessionId) == null) {
                    dropClient(sessionId)
                    return@withContext SshResult.Error(SshErrorCode.UNKNOWN, SFTP_SESSION_NOT_FOUND_MESSAGE)
                }
                Timber.i("SFTP opened for session ${LogId.of(sessionId)}")
                SshResult.Success(Unit)
            } catch (e: Exception) {
                // The class of the exception is logged, never its message (SSHJ or the system, in
                // English, it can name a host) and the screen says that the channel did not open.
                Timber.e("Cannot open the SFTP client for session ${LogId.of(sessionId)} (${errorClass(e)})")
                SshResult.Error(SshErrorCode.UNKNOWN, SFTP_OPEN_FAILED_MESSAGE)
            }
        }
    }

    /**
     * Signale qu'un utilisateur du client SFTP a terminé.
     *
     * Le client n'est réellement fermé qu'au dernier partant : un transfert en
     * arrière-plan doit survivre à la fermeture de l'écran de l'explorateur.
     */
    suspend fun closeSftp(sessionId: String): SshResult<Unit> = withContext(Dispatchers.IO) {
        openMutex.withLock {
            val remaining = (sessionUsers[sessionId] ?: 0) - 1
            if (remaining > 0) {
                sessionUsers[sessionId] = remaining
                Timber.d("SFTP kept for session ${LogId.of(sessionId)}, $remaining user(s) left")
                return@withContext SshResult.Success(Unit)
            }
            sessionUsers.remove(sessionId)
            return@withContext try {
                sftpClients.remove(sessionId)?.close()
                Timber.i("SFTP closed for session ${LogId.of(sessionId)}")
                SshResult.Success(Unit)
            } catch (e: Exception) {
                Timber.w("Error closing SFTP for session ${LogId.of(sessionId)} (${errorClass(e)})")
                SshResult.Error(SshErrorCode.UNKNOWN, SFTP_UNKNOWN_MESSAGE)
            }
        }
    }

    /**
     * Reserves [sessionId] for a transfer that outlives the screen which asked for it: takes a
     * reference on the SSH session ([SshSessionManager.acquire]) and a user of its SFTP client
     * ([openSftp]). The caller gives both back with [SftpReservation.release], once the transfer is
     * over, however it ended. An error when the session is gone or the SFTP channel does not open,
     * and then nothing is held.
     */
    suspend fun reserve(sessionId: String): SshResult<SftpReservation> {
        if (!sessionManager.acquire(sessionId)) {
            return SshResult.Error(SshErrorCode.UNKNOWN, SFTP_SESSION_NOT_FOUND_MESSAGE)
        }
        val opened = try {
            openSftp(sessionId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancelled while the channel was being opened: the reference goes back at once. A user
            // of the SFTP client may have been counted, which is not undone here (the count of
            // another user could be taken for it), and goes away with the session.
            sessionManager.release(sessionId)
            throw e
        }
        if (opened is SshResult.Error) {
            sessionManager.release(sessionId)
            return opened
        }
        return SshResult.Success(SftpReservation(this, sessionManager, sessionId))
    }

    /** Vérifie si un SFTPClient est ouvert pour cette session. */
    fun isOpen(sessionId: String): Boolean = sftpClients.containsKey(sessionId)

    /**
     * Forgets the SFTP client and the users of [sessionId], without closing the client: its session
     * ended, so its channel went with it and closing it would only talk to a dead transport.
     */
    private fun dropClient(sessionId: String) {
        sftpClients.remove(sessionId)
        sessionUsers.remove(sessionId)
    }

    // ── Navigation ────────────────────────────────────────────────────────────────

    /**
     * Retourne le répertoire home de l'utilisateur distant (`~`).
     */
    suspend fun getHomeDirectory(sessionId: String): SshResult<String> = withContext(Dispatchers.IO) {
        val sftp = sftpClients[sessionId]
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_NO_SESSION_MESSAGE
            )
        return@withContext try {
            val home = sftp.canonicalize(".")
            Timber.d("SFTP home resolved for session ${LogId.of(sessionId)}")
            SshResult.Success(home)
        } catch (e: Exception) {
            Timber.w("Cannot resolve the SFTP home for session ${LogId.of(sessionId)} (${errorClass(e)})")
            // Fallback vers la racine en cas d'échec
            SshResult.Success("/")
        }
    }

    /**
     * Liste le contenu d'un répertoire.
     *
     * @param sessionId identifiant de la session SSH active.
     * @param path chemin absolu du répertoire à lister (sanitisé avant usage).
     * @return liste de [SftpFile], ou [SshResult.Error].
     */
    suspend fun listDirectory(
        sessionId: String,
        path: String,
    ): SshResult<List<SftpFile>> = withContext(Dispatchers.IO) {
        val sftp = sftpClients[sessionId]
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_NO_SESSION_MESSAGE
            )

        val safePath = sanitizePath(path)
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_INVALID_PATH_MESSAGE
            )

        return@withContext try {
            val entries: List<RemoteResourceInfo> = sftp.ls(safePath)
            val files = entries.map { info -> info.toSftpFile() }
            SshResult.Success(files)
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            Timber.w("SFTP ls failed status=${sftpStatus(e)}")
            val (code, msg) = when {
                e.message?.contains("permission", ignoreCase = true) == true ->
                    SshErrorCode.AUTH_FAILED to SFTP_PERMISSION_DENIED_MESSAGE
                e.message?.contains("no such", ignoreCase = true) == true ->
                    SshErrorCode.UNKNOWN to SFTP_NOT_FOUND_MESSAGE
                else -> SshErrorCode.UNKNOWN to SFTP_UNKNOWN_MESSAGE
            }
            SshResult.Error(code, msg)
        } catch (e: java.io.IOException) {
            Timber.e("SFTP connection lost during ls (${errorClass(e)})")
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_CONNECTION_LOST_MESSAGE)
        } catch (e: Exception) {
            Timber.e("Unexpected error during SFTP ls (${errorClass(e)})")
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_UNKNOWN_MESSAGE)
        }
    }

    // ── Opérations CRUD ───────────────────────────────────────────────────────────

    /**
     * Crée un répertoire distant, sans jamais en remplacer un.
     *
     * Le nom est un nom nu, tel que l'utilisateur l'a tapé : il n'est jamais corrigé, décodé ni résolu.
     * Seul le dossier parent passe par [sanitizePath] (qui normalise les `.` et `..` sans rien décoder),
     * le nom lui est accolé tel quel. Un `+`, un `%` ou un `%2F` dans le nom sont donc des caractères du
     * nom, pas un chemin, et `..` est refusé au lieu de viser le parent du parent.
     *
     * Le nom est d'abord cherché sur le serveur : le `SSH_FXP_MKDIR` d'un serveur SFTP v3 refuse un
     * dossier déjà là par un « Failure » que rien ne distingue d'un autre refus. Cette recherche ne
     * sert qu'à dire pourquoi : `mkdir` ne remplace jamais rien. Si le serveur la refuse pour une autre
     * raison que l'absence du nom (une permission, par exemple sur un dossier en écriture seule), la
     * recherche ne conclut pas et `mkdir` est envoyé quand même. Une perte du canal ou un serveur muet
     * pendant la recherche n'est pas une réponse : tout s'arrête avec le message de connexion perdue et
     * `mkdir` n'est pas envoyé.
     *
     * @param sessionId identifiant de la session SSH active.
     * @param parentDir chemin absolu du dossier où créer le répertoire.
     * @param name nom nu du répertoire : ni séparateur, ni caractère de contrôle, ni espace en bordure,
     *   ni `.` ni `..` (voir [isValidEntryName]). Un nom qui ne passe pas est refusé, pas corrigé.
     * @return [SshResult.Success], ou [SshResult.Error] (nom invalide, élément déjà là, refus du serveur).
     */
    suspend fun createDirectory(
        sessionId: String,
        parentDir: String,
        name: String,
    ): SshResult<Unit> = withContext(Dispatchers.IO) {
        val sftp = sftpClients[sessionId]
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_NO_SESSION_MESSAGE
            )

        if (!isValidEntryName(name)) {
            return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_FOLDER_NAME_INVALID_MESSAGE
            )
        }

        val safeParent = sanitizePath(parentDir)
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_INVALID_PATH_MESSAGE
            )
        // The root comes back as "/": trimmed, it joins the name with a single slash.
        val safePath = "${safeParent.trimEnd('/')}/$name"

        return@withContext try {
            // The lookup only serves the message, mkdir never replaces anything: a lookup the server
            // refuses (permission, failure) proves nothing, and mkdir is sent all the same.
            val taken = try {
                sftp.statExistence(safePath) != null
            } catch (e: net.schmizz.sshj.sftp.SFTPException) {
                // A lost channel or a silent server is no answer about the name: mkdir would only wait for
                // the timeout once more on a dead channel. It goes up as a lost connection (caught below).
                if (faultOf(e) == UploadFault.CHANNEL) throw lookupChannelLost(e)
                Timber.w("SFTP mkdir lookup failed status=${sftpStatus(e)}")
                false
            }
            if (taken) {
                return@withContext SshResult.Error(
                    SshErrorCode.UNKNOWN,
                    SFTP_NAME_EXISTS_MESSAGE
                )
            }
            sftp.mkdir(safePath)
            Timber.i("SFTP mkdir done")
            SshResult.Success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            // The status code only: the text of the server can quote the path, and so can the exception.
            Timber.w("SFTP mkdir failed status=${sftpStatus(e)}")
            val msg = when {
                e.message?.contains("permission", ignoreCase = true) == true -> SFTP_PERMISSION_DENIED_MESSAGE
                e.message?.contains("exist", ignoreCase = true) == true -> SFTP_FOLDER_EXISTS_MESSAGE
                else -> SFTP_MKDIR_FAILED_MESSAGE
            }
            SshResult.Error(SshErrorCode.UNKNOWN, msg)
        } catch (e: java.io.IOException) {
            Timber.e("SFTP connection lost during mkdir (${errorClass(e)})")
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_CONNECTION_LOST_MESSAGE)
        } catch (e: Exception) {
            // Not the server's doing: the class only, and the fixed text of a folder that was not made.
            Timber.e("Unexpected error during SFTP mkdir (${errorClass(e)})")
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_MKDIR_FAILED_MESSAGE)
        }
    }

    /**
     * Crée un fichier distant vide, sans jamais en remplacer un.
     *
     * Le nom doit être libre : le chemin est d'abord cherché sur le serveur, puis le fichier est ouvert
     * avec [OpenMode.EXCL], de sorte qu'un fichier apparu entre les deux est refusé par le serveur au
     * lieu d'être tronqué (c'est la différence avec [writeFile] quand il remplace, qui passe alors par
     * un fichier temporaire ; sans remplacement, [writeFile] ouvre lui aussi en [OpenMode.EXCL]). Il est
     * refermé aussitôt. Les droits sont ceux que le serveur donne à un nouveau fichier (son umask).
     * Une perte du canal ou un serveur muet pendant la recherche donne le message de connexion perdue,
     * et l'ouverture n'est pas envoyée.
     *
     * Le nom est un nom nu, tel que l'utilisateur l'a tapé : il n'est jamais décodé ni résolu. Seul le
     * dossier parent passe par [sanitizePath] (qui normalise les `.` et `..` sans rien décoder), le nom
     * lui est accolé tel quel. Un `+`, un `%` ou un `%2F` dans le nom sont donc des caractères du nom,
     * pas un chemin.
     *
     * @param sessionId identifiant de la session SSH active.
     * @param parentDir chemin absolu du dossier où créer le fichier.
     * @param name nom nu du fichier : ni séparateur, ni caractère de contrôle, ni espace en bordure,
     *   ni `.` ni `..` (voir [isValidEntryName]). Un nom qui ne passe pas est refusé, pas corrigé.
     * @return [SshResult.Success], ou [SshResult.Error] (nom invalide, élément déjà là, refus du serveur).
     */
    suspend fun createFile(
        sessionId: String,
        parentDir: String,
        name: String,
    ): SshResult<Unit> = withContext(Dispatchers.IO) {
        val sftp = sftpClients[sessionId]
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_NO_SESSION_MESSAGE
            )

        // Strict: the name is refused, never corrected, so nothing is dropped, trimmed or cut without the
        // user knowing. An empty or blank name is refused as well, and so are "." and "..", which name
        // the folder itself or its parent: a folder that exists, and a path that leaves the folder.
        if (!isValidEntryName(name)) {
            return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_NAME_INVALID_MESSAGE
            )
        }

        val safeParent = sanitizePath(parentDir)
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_INVALID_PATH_MESSAGE
            )
        // The root comes back as "/": trimmed, it joins the name with a single slash.
        val safePath = "${safeParent.trimEnd('/')}/$name"

        var remoteFile: net.schmizz.sshj.sftp.RemoteFile? = null
        return@withContext try {
            val existing = try {
                sftp.statExistence(safePath)
            } catch (e: net.schmizz.sshj.sftp.SFTPException) {
                if (faultOf(e) == UploadFault.CHANNEL) throw lookupChannelLost(e)
                throw e
            }
            if (existing != null) {
                return@withContext SshResult.Error(
                    SshErrorCode.UNKNOWN,
                    SFTP_NAME_EXISTS_MESSAGE
                )
            }
            remoteFile = sftp.open(safePath, setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL))
            Timber.i("SFTP createFile done")
            SshResult.Success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            // The status code only: the text of the server can quote the path, and so can the exception.
            Timber.w("SFTP createFile failed status=${sftpStatus(e)}")
            val msg = when {
                e.message?.contains("permission", ignoreCase = true) == true -> SFTP_PERMISSION_DENIED_MESSAGE
                else -> SFTP_CREATE_FILE_FAILED_MESSAGE
            }
            SshResult.Error(SshErrorCode.UNKNOWN, msg)
        } catch (e: java.io.IOException) {
            Timber.e("SFTP connection lost during createFile (${errorClass(e)})")
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_CONNECTION_LOST_MESSAGE)
        } catch (e: Exception) {
            Timber.e("Unexpected error during SFTP createFile (${errorClass(e)})")
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_CREATE_FILE_FAILED_MESSAGE)
        } finally {
            try {
                remoteFile?.close()
            } catch (e: Exception) {
                Timber.w("Error closing the new remote file (${errorClass(e)})")
            }
        }
    }

    /**
     * Supprime un fichier distant (non récursif).
     *
     * Pour les répertoires, utilisez [deleteRecursive].
     *
     * @param sessionId identifiant de la session SSH active.
     * @param path chemin absolu du fichier à supprimer.
     */
    suspend fun delete(
        sessionId: String,
        path: String,
    ): SshResult<Unit> = withContext(Dispatchers.IO) {
        val sftp = sftpClients[sessionId]
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_NO_SESSION_MESSAGE
            )

        val safePath = sanitizePath(path)
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_INVALID_PATH_MESSAGE
            )

        return@withContext try {
            sftp.rm(safePath)
            Timber.i("SFTP rm done")
            SshResult.Success(Unit)
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            Timber.w("SFTP rm failed status=${sftpStatus(e)}")
            // A folder that rm refuses (the browser sends those to deleteRecursive) is one more
            // deletion the server did not do: it has the same text.
            val msg = when {
                e.message?.contains("permission", ignoreCase = true) == true -> SFTP_PERMISSION_DENIED_MESSAGE
                else -> SFTP_DELETE_FAILED_MESSAGE
            }
            SshResult.Error(SshErrorCode.UNKNOWN, msg)
        } catch (e: java.io.IOException) {
            Timber.e("SFTP connection lost during rm (${errorClass(e)})")
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_CONNECTION_LOST_MESSAGE)
        }
    }

    /**
     * Supprime récursivement un répertoire et tout son contenu.
     *
     * Supprime d'abord les fichiers, puis récurse dans les sous-dossiers,
     * puis supprime le répertoire vide.
     *
     * @param sessionId identifiant de la session SSH active.
     * @param path chemin absolu du répertoire à supprimer.
     */
    suspend fun deleteRecursive(
        sessionId: String,
        path: String,
    ): SshResult<Unit> = withContext(Dispatchers.IO) {
        val sftp = sftpClients[sessionId]
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_NO_SESSION_MESSAGE
            )

        val safePath = sanitizePath(path)
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_INVALID_PATH_MESSAGE
            )

        // Interdire la suppression de la racine
        if (safePath == "/") {
            Timber.w("SFTP deleteRecursive: tentative de suppression de la racine refusée")
            return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_DELETE_FAILED_MESSAGE
            )
        }

        return@withContext try {
            deleteRecursiveInternal(sftp, safePath)
            Timber.i("SFTP deleteRecursive done")
            SshResult.Success(Unit)
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            Timber.w("SFTP deleteRecursive failed status=${sftpStatus(e)}")
            val msg = when {
                e.message?.contains("permission", ignoreCase = true) == true -> SFTP_PERMISSION_DENIED_MESSAGE
                e.message?.contains("no such", ignoreCase = true) == true -> SFTP_NOT_FOUND_MESSAGE
                else -> SFTP_DELETE_FAILED_MESSAGE
            }
            SshResult.Error(SshErrorCode.UNKNOWN, msg)
        } catch (e: java.io.IOException) {
            Timber.e("SFTP connection lost during deleteRecursive (${errorClass(e)})")
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_CONNECTION_LOST_MESSAGE)
        }
    }

    /**
     * Implémentation récursive interne (sans gestion d'erreur, propagée par [deleteRecursive]).
     */
    private fun deleteRecursiveInternal(sftp: SFTPClient, path: String) {
        val entries: List<RemoteResourceInfo> = sftp.ls(path)
        for (entry in entries) {
            if (entry.name == "." || entry.name == "..") continue
            val isSymlink = entry.attributes.type == FileMode.Type.SYMLINK
            if (isSymlink) {
                // Supprimer le lien symbolique sans suivre la cible
                sftp.rm(entry.path)
            } else if (entry.isDirectory) {
                deleteRecursiveInternal(sftp, entry.path)
                // L'appel récursif retire lui-même le sous-dossier : un second rmdir
                // échouerait sur un vrai serveur et interromprait la suppression.
            } else {
                sftp.rm(entry.path)
            }
        }
        sftp.rmdir(path)
    }

    /**
     * Renomme un fichier ou un dossier, dans le dossier où il se trouve, sans jamais en remplacer un.
     *
     * Le nouveau nom est un nom nu, tel que l'utilisateur l'a tapé : il est refusé s'il ne l'est pas
     * ([isValidEntryName]), jamais corrigé (`a/b` ne devient pas `ab`) ni résolu (`..` ne désigne pas
     * le parent). Il est accolé au dossier parent de l'entrée, qui seul passe par [sanitizePath]. Le
     * nom est d'abord cherché sur le serveur : le `SSH_FXP_RENAME` d'un serveur SFTP v3 refuse une cible
     * déjà là par un « Failure » que rien ne distingue d'un autre refus, et un serveur non conforme la
     * remplacerait. Un lien symbolique cassé à la place du nouveau nom compte comme un nom pris (la
     * recherche ne suit pas les liens), et une recherche que le serveur refuse pour une autre raison que
     * l'absence du nom bloque le renommage. Une perte du canal pendant la recherche donne le message de
     * connexion perdue, et le renommage n'est pas envoyé. Un renommage vers le nom actuel réussit sans requête, un
     * changement de casse seule (`Rapport.docx` vers `rapport.docx`) n'est pas cherché, car un serveur
     * qui ignore la casse y trouverait l'entrée elle-même : le serveur décide. La racine n'a pas de nom.
     *
     * @param sessionId identifiant de la session SSH active.
     * @param oldPath chemin absolu de l'entrée à renommer.
     * @param newName nouveau nom nu : ni séparateur, ni caractère de contrôle, ni espace en bordure,
     *   ni `.` ni `..`.
     * @return [SshResult.Success], ou [SshResult.Error] (nom invalide, élément déjà là, refus du serveur).
     */
    suspend fun rename(
        sessionId: String,
        oldPath: String,
        newName: String,
    ): SshResult<Unit> = withContext(Dispatchers.IO) {
        val sftp = sftpClients[sessionId]
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_NO_SESSION_MESSAGE
            )

        if (!isValidEntryName(newName)) {
            return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_ENTRY_NAME_INVALID_MESSAGE
            )
        }

        val safeOldPath = sanitizePath(oldPath)
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_INVALID_PATH_MESSAGE
            )
        // The root has no name to change, and no parent to put a new one in.
        if (safeOldPath == "/") {
            return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_INVALID_PATH_MESSAGE
            )
        }

        // Joined, not resolved: the new name is a bare name, so the new path stays in the parent folder.
        val parentDir = safeOldPath.substringBeforeLast('/', "/").ifEmpty { "/" }
        val newPath = "${parentDir.trimEnd('/')}/$newName"

        // Same name: nothing to ask the server, and it would answer that the target exists (itself).
        if (newPath == safeOldPath) {
            return@withContext SshResult.Success(Unit)
        }
        // A change of case only (Rapport.docx to rapport.docx): on a server that ignores the case, the
        // target found is the entry itself, so the lookup would refuse a rename that is perfectly fine.
        // The server decides, as it does for any rename.
        val caseChangeOnly = newPath.equals(safeOldPath, ignoreCase = true)

        return@withContext try {
            if (!caseChangeOnly && lstatTarget(sftp, newPath) != null) {
                return@withContext SshResult.Error(
                    SshErrorCode.UNKNOWN,
                    SFTP_NAME_EXISTS_MESSAGE
                )
            }
            sftp.rename(safeOldPath, newPath)
            Timber.i("SFTP rename done")
            SshResult.Success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            // The status code only: the text of the server can quote the path, and so can the exception.
            Timber.w("SFTP rename failed status=${sftpStatus(e)}")
            val msg = when {
                e.message?.contains("permission", ignoreCase = true) == true -> SFTP_PERMISSION_DENIED_MESSAGE
                e.message?.contains("exist", ignoreCase = true) == true -> SFTP_NAME_EXISTS_MESSAGE
                else -> SFTP_RENAME_FAILED_MESSAGE
            }
            SshResult.Error(SshErrorCode.UNKNOWN, msg)
        } catch (e: java.io.IOException) {
            Timber.e("SFTP connection lost during rename (${errorClass(e)})")
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_CONNECTION_LOST_MESSAGE)
        } catch (e: Exception) {
            // Not the server's doing: the class only, and the fixed text of an entry that was not renamed.
            Timber.e("Unexpected error during SFTP rename (${errorClass(e)})")
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_RENAME_FAILED_MESSAGE)
        }
    }

    /**
     * The attributes of whatever sits at [path], a symbolic link included (lstat, which does not follow
     * it), or null when nothing is there.
     *
     * [net.schmizz.sshj.sftp.SFTPClient.statExistence] follows links, so a broken link in the place of a
     * new name looked free and a server that replaces on rename would have replaced it. Only the two
     * statuses that say "nothing here" (no such file, no such path) give null: any other answer, a
     * permission refused for example, proves nothing about the name and is rethrown for the caller. A
     * lost channel or a silent server ([UploadFault.CHANNEL]) goes up as an [IOException] of lost
     * connection ([lookupChannelLost]).
     */
    private fun lstatTarget(sftp: SFTPClient, path: String): net.schmizz.sshj.sftp.FileAttributes? =
        try {
            sftp.lstat(path)
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            val status = e.statusCode
            if (status == net.schmizz.sshj.sftp.Response.StatusCode.NO_SUCH_FILE ||
                status == net.schmizz.sshj.sftp.Response.StatusCode.NO_SUCH_PATH
            ) {
                null
            } else if (faultOf(e) == UploadFault.CHANNEL) {
                // A lost channel or a silent server is no answer: a lost connection, the rename is not sent.
                throw lookupChannelLost(e)
            } else {
                throw e
            }
        }

    /**
     * Modifie les permissions POSIX d'un fichier ou dossier.
     *
     * @param sessionId identifiant de la session SSH active.
     * @param path chemin absolu de l'entrée cible.
     * @param permissions entier de permissions POSIX (de 0 à 511, soit de 0o000 à 0o777).
     */
    suspend fun chmod(
        sessionId: String,
        path: String,
        permissions: Int,
    ): SshResult<Unit> = withContext(Dispatchers.IO) {
        if (permissions < 0 || permissions > 511) {
            return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_PERMISSIONS_INVALID_MESSAGE
            )
        }

        val sftp = sftpClients[sessionId]
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_NO_SESSION_MESSAGE
            )

        val safePath = sanitizePath(path)
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_INVALID_PATH_MESSAGE
            )

        return@withContext try {
            sftp.chmod(safePath, permissions)
            Timber.i("SFTP chmod %o done", permissions)
            SshResult.Success(Unit)
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            Timber.w("SFTP chmod failed status=${sftpStatus(e)}")
            val msg = when {
                e.message?.contains("permission", ignoreCase = true) == true -> SFTP_PERMISSION_DENIED_MESSAGE
                else -> SFTP_CHMOD_FAILED_MESSAGE
            }
            SshResult.Error(SshErrorCode.UNKNOWN, msg)
        } catch (e: java.io.IOException) {
            Timber.e("SFTP connection lost during chmod (${errorClass(e)})")
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_CONNECTION_LOST_MESSAGE)
        }
    }

    /**
     * Lit les premiers octets d'un fichier pour un aperçu.
     * Limité à [maxBytes] pour éviter les problèmes de mémoire (DoS).
     *
     * @param sessionId identifiant de la session SSH active.
     * @param path chemin absolu du fichier à lire.
     * @param maxBytes taille maximale autorisée (rejet si dépassée).
     * @return [SshResult.Success] avec le contenu en [ByteArray], ou [SshResult.Error].
     */
    suspend fun readPreview(
        sessionId: String,
        path: String,
        maxBytes: Long,
    ): SshResult<ByteArray> = withContext(Dispatchers.IO) {
        val sftp = sftpClients[sessionId]
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_NO_SESSION_MESSAGE
            )

        val safePath = sanitizePath(path)
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_INVALID_PATH_MESSAGE
            )

        var remoteFile: net.schmizz.sshj.sftp.RemoteFile? = null
        return@withContext try {
            remoteFile = sftp.open(safePath)
            val fileSize = remoteFile.length()
            if (fileSize > maxBytes) {
                return@withContext SshResult.Error(
                    SshErrorCode.UNKNOWN,
                    SFTP_PREVIEW_TOO_LARGE_MESSAGE
                )
            }
            val buffer = ByteArray(fileSize.toInt())
            remoteFile.read(0, buffer, 0, buffer.size)
            Timber.d("SFTP readPreview done ($fileSize bytes)")
            SshResult.Success(buffer)
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            Timber.w("SFTP readPreview failed status=${sftpStatus(e)}")
            val msg = when {
                e.message?.contains("permission", ignoreCase = true) == true -> SFTP_PERMISSION_DENIED_MESSAGE
                e.message?.contains("no such", ignoreCase = true) == true -> SFTP_NOT_FOUND_MESSAGE
                else -> SFTP_READ_FAILED_MESSAGE
            }
            SshResult.Error(SshErrorCode.UNKNOWN, msg)
        } catch (e: java.io.IOException) {
            Timber.e("SFTP connection lost during readPreview (${errorClass(e)})")
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_CONNECTION_LOST_MESSAGE)
        } catch (e: Exception) {
            Timber.e("Unexpected error during SFTP readPreview (${errorClass(e)})")
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_READ_FAILED_MESSAGE)
        } finally {
            closeQuietly(remoteFile)
        }
    }

    /**
     * Télécharge un fichier distant vers un OutputStream local.
     * Progress callback appelé après chaque chunk de 32 Ko.
     *
     * @param sessionId   identifiant de la session SSH active.
     * @param remotePath  chemin absolu du fichier distant à lire.
     * @param outputStream flux de sortie local (SAF OutputStream).
     * @param onProgress  lambda appelée avec (bytesTransferred, totalBytes).
     */
    suspend fun readFile(
        sessionId: String,
        remotePath: String,
        outputStream: OutputStream,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ): SshResult<Unit> = withContext(Dispatchers.IO) {
        val sftp = sftpClients[sessionId]
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_NO_SESSION_MESSAGE
            )

        val safePath = sanitizePath(remotePath)
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_INVALID_PATH_MESSAGE
            )

        var remoteFile: net.schmizz.sshj.sftp.RemoteFile? = null
        return@withContext try {
            remoteFile = sftp.open(safePath)
            val size = remoteFile.length()
            var offset = 0L
            val buffer = ByteArray(32768)
            while (offset < size) {
                // Point d'annulation : sans lui, annuler un transfert ne faisait
                // que changer un libellé pendant que la copie continuait jusqu'au
                // bout.
                currentCoroutineContext().ensureActive()
                val toRead = minOf(buffer.size.toLong(), size - offset).toInt()
                val read = remoteFile.read(offset, buffer, 0, toRead)
                if (read <= 0) break
                outputStream.write(buffer, 0, read)
                offset += read
                onProgress(offset, size)
            }
            Timber.i("SFTP readFile done ($size bytes)")
            SshResult.Success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Doit remonter : le catch generique ci-dessous la transformerait en
            // erreur ordinaire et le service croirait le transfert echoue.
            throw e
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            Timber.w("SFTP readFile failed status=${sftpStatus(e)}")
            val msg = when {
                e.message?.contains("permission", ignoreCase = true) == true -> SFTP_PERMISSION_DENIED_MESSAGE
                e.message?.contains("no such", ignoreCase = true) == true -> SFTP_NOT_FOUND_MESSAGE
                else -> SFTP_DOWNLOAD_FAILED_MESSAGE
            }
            SshResult.Error(SshErrorCode.UNKNOWN, msg)
        } catch (e: java.io.IOException) {
            Timber.e("SFTP connection lost during readFile (${errorClass(e)})")
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_CONNECTION_LOST_MESSAGE)
        } catch (e: Exception) {
            Timber.e("Unexpected error during SFTP readFile (${errorClass(e)})")
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_DOWNLOAD_FAILED_MESSAGE)
        } finally {
            closeQuietly(remoteFile)
        }
    }

    /**
     * Upload un fichier depuis un InputStream vers le serveur distant.
     * Progress callback appelé après chaque chunk de 32 Ko.
     *
     * Règle : un fichier n'est jamais remplacé sans confirmation. Sans [replaceExisting], le fichier
     * est ouvert en [OpenMode.EXCL] : c'est le serveur qui refuse un nom pris, au moment même de
     * l'ouverture, donc aussi quand le fichier est apparu après la vérification de
     * [checkUploadTarget]. OpenSSH (SFTP v3) répond alors `FAILURE` et non `FILE_ALREADY_EXISTS` :
     * le refus ne se reconnaît pas à son seul statut, il est confirmé en cherchant le nom avec
     * `lstat` (qui voit aussi un lien cassé, que `EXCL` refuse comme un fichier).
     *
     * Avec [replaceExisting], l'ancien fichier n'est jamais vidé avant que le nouveau soit complet. Le
     * contenu est écrit dans un fichier temporaire caché du même dossier (`.nom.nextsh-part-<8 hex>`,
     * créé en [OpenMode.EXCL] et en 0600 dès l'ouverture : un lien posé à l'avance n'est pas suivi, et
     * le contenu partiel n'est lisible de personne d'autre), puis le temporaire prend la place de
     * l'ancien d'un seul coup par `posix-rename@openssh.com`, qui est atomique. Tant que ce renommage
     * n'a pas eu lieu, l'ancien fichier n'est pas touché : une coupure, une annulation, un disque plein
     * ou une app tuée le laissent intact. Le déroulé :
     *
     * 1. La cible est lue sans suivre les liens (`lstat`). Absente, l'envoi la crée comme pour un nom
     *    libre ([OpenMode.EXCL]). Un dossier, un tube, une socket, un périphérique ou un lien cassé :
     *    [SFTP_UPLOAD_TARGET_CHANGED_MESSAGE], rien n'est ouvert. Un lien vers un fichier est résolu par
     *    le serveur (`realpath`) et c'est la vraie cible qui est remplacée, comme `sftp put` qui écrit à
     *    travers le lien : le temporaire est alors dans le dossier de la vraie cible. Le lien n'est suivi
     *    que s'il mène directement à la cible : ce que dit `readlink`, résolu par rapport au dossier du
     *    lien tel qu'il est écrit et normalisé, doit être exactement le chemin de `realpath`. Une chaîne
     *    de liens, un lien de dossier sur le chemin de la cible, un `readlink` refusé, une cible qui
     *    contient un segment `..` (le texte et le disque ne disent pas la même chose à travers un lien
     *    de dossier) retombent sur l'écriture en place. Ce repli garantit le comportement de la 1.1.0
     *    (le noyau décide de l'ouverture à travers le lien), pas davantage : `realpath` résout tout en
     *    espace utilisateur, et l'application ne refait pas les contrôles du noyau sur les liens
     *    (`protected_symlinks` et le reste), elle ne suit que ce qu'elle peut établir elle-même. Le
     *    lien n'est suivi non plus que s'il appartient à l'utilisateur qui envoie (son uid est
     *    celui du temporaire, qui vient d'être créé) : `realpath` suit n'importe quel lien, alors que le
     *    noyau refuse de suivre le lien d'un autre dans un dossier sticky (`protected_symlinks`, actif
     *    par défaut sous Linux, root compris). Un lien d'un autre, ou dont le propriétaire est inconnu,
     *    retombe sur l'écriture en place à travers le lien, où le noyau décide comme en 1.1.0 (le
     *    temporaire est supprimé).
     * 2. Le chemin atomique ne doit changer que le contenu. Quand il est impossible, l'envoi retombe
     *    sur l'écriture en place ([OpenMode.TRUNC] : l'ancien fichier est vidé dès l'ouverture, mais
     *    l'ouverture n'a lieu qu'après la lecture du premier bloc de la source : une source illisible
     *    laisse l'ancien fichier intact), et le journal en dit la raison : serveur sans `posix-rename@openssh.com`,
     *    dossier où le temporaire ne peut pas être créé, propriétaire ou groupe de l'ancien fichier
     *    impossibles à reproduire, fichier protégé en écriture par son propriétaire (un renommage le
     *    remplacerait en silence alors que l'ouverture en place le refuse), lien que le serveur ne
     *    résout pas ou qui n'est pas à l'utilisateur. Un serveur qui ne montre pas les droits ou le
     *    propriétaire de l'ancien fichier (ou du temporaire), ou qui ne prend pas `lstat` en charge,
     *    ne permet pas non plus de savoir ce que le remplacement garde ou change : l'écriture en place
     *    n'est jamais pire que la 1.1.0. Ces décisions se prennent toutes avant le premier octet lu du
     *    flux source, qui ne se rembobine pas. Une annulation est vue juste avant l'ouverture en place,
     *    qui vide l'ancien fichier : un job annulé pendant le plan, pendant un repli ou pendant la lecture
     *    du premier bloc n'y arrive pas. Restent des fenêtres que rien ne ferme sans renoncer à
     *    [OpenMode.TRUNC] : une annulation pendant la requête d'ouverture (le premier bloc est écrit quand
     *    même), une perte du canal entre l'ouverture et la première écriture, une première écriture
     *    refusée, une coupure en cours d'envoi (le fichier est alors tronqué).
     * 3. À la fin, les droits de l'ancien fichier sont recopiés (masqués par 0777 : le setuid, le setgid
     *    et le sticky ne passent jamais à un contenu nouveau), le temporaire est fermé, et un échec de
     *    la fermeture fait échouer l'envoi (un quota ou un montage réseau ne le signalent parfois qu'à
     *    ce moment). Une recopie des droits refusée est journalisée, sans arrêter l'envoi : le fichier
     *    reste en 0600, plus strict que l'ancien pour les bits de permission (pas pour les ACL et les
     *    contextes SELinux, voir les limites). Après le dernier point d'annulation, la cible est relue
     *    (type, taille, date de modification, droits, propriétaire et groupe) : si elle ne montre plus ce
     *    que le plan a vu, quelqu'un d'autre l'a remplacée ou en a changé les droits pendant l'envoi, et
     *    le renommage écraserait son fichier (ou lui rendrait les anciens droits), ce que l'écriture en
     *    place n'aurait pas fait. Elle est alors refusée avec
     *    [SFTP_UPLOAD_TARGET_CHANGED_MESSAGE], sans renommage. Puis vient le renommage.
     * 4. Un échec avant le renommage supprime le temporaire au mieux (jamais après une perte de canal
     *    avérée, la suppression resterait bloquée jusqu'au délai du serveur) et rend
     *    [SFTP_REPLACE_FAILED_KEPT_MESSAGE], ou le message de permission ou de connexion perdue. Une
     *    cible devenue un dossier (ou autre chose qu'un fichier) rend [SFTP_UPLOAD_TARGET_CHANGED_MESSAGE].
     *
     * Un envoi vers un nom libre ([OpenMode.EXCL]), qui échoue ou qu'on annule, supprime de même, au
     * mieux, le fichier partiel qu'il vient de créer sous le vrai nom : sans cela il passerait pour un
     * fichier complet. Ce fichier est celui de l'envoi, créé par lui, donc rien de l'utilisateur n'est
     * supprimé, à condition que le nom mène encore à lui : sa taille, sa date de modification et son
     * propriétaire, relevés par le descripteur avant la fermeture, doivent être montrés par `lstat` et
     * égaux, et le nom ne doit pas être un lien (un attribut absent d'un côté, un nom qui montre autre
     * chose ou plus rien, un serveur sans `lstat`, qui répondrait par `stat` en suivant les liens :
     * rien n'est supprimé). Une perte de canal vue à ce relevé suffit à ne plus rien demander : ni
     * fermeture, ni recherche du nom, ni suppression. L'écriture en
     * place ne supprime jamais rien : son fichier existait avant l'envoi.
     *
     * Limites, qui ne se détectent pas à l'avance (SFTP v3 ne donne ni le nombre de liens, ni les ACL) :
     * - le renommage donne un nouvel inode : les liens durs, les ACL, les attributs étendus et le
     *   contexte SELinux de l'ancien fichier ne sont pas conservés. Le nouveau fichier prend ceux du
     *   dossier : une ACL par défaut du dossier lui donne des droits que l'ancien fichier n'avait pas
     *   (le `chmod` final de la recopie des droits rouvre le masque de cette ACL), et un contexte
     *   SELinux hérité du dossier peut différer de celui de l'ancien fichier. « Plus strict » ne vaut
     *   donc que pour les bits de permission ;
     * - `readlink` d'un serveur de protocole SFTP antérieur à v3 (ou dont la réponse est illisible pour
     *   SSHJ) : SSHJ lève alors une exception sans statut (« READLINK is not supported in SFTPv... »,
     *   « Unexpected data in NAME »), que la classification range parmi les pertes de canal. Le
     *   remplacement d'un lien y finit en connexion perdue au lieu de retomber sur l'écriture en place,
     *   sans que rien ne la distingue d'une vraie perte ;
     * - la cible est relue juste avant le renommage, pas au moment du renommage : un autre écrivain qui
     *   remplace le fichier dans cet intervalle d'une requête gagne quand même ;
     * - un fichier monté seul (bind mount) ne se remplace pas : le serveur refuse le renommage, l'envoi
     *   échoue et l'ancien fichier reste ;
     * - un serveur qui ne prend pas `lstat` en charge n'est interrogé que par `stat`, qui suit les liens :
     *   un lien y passe pour le fichier lui-même, et un renommage le remplacerait par un fichier
     *   ordinaire. Un remplacement y retombe donc sur l'écriture en place, qui passe à travers le lien
     *   (un dossier, un tube, une socket ou un périphérique que `stat` montre sont refusés comme avec
     *   `lstat` : l'ouverture en place d'un tube sans lecteur bloquerait le serveur) ;
     * - l'ancien et le nouveau coexistent pendant l'envoi : il faut la place des deux ;
     * - si l'app ou son processus est tué, ou si le service est coupé, le temporaire reste sur le
     *   serveur (caché, en 0600, incomplet). Rien ne le balaie : on ne supprime jamais un fichier que
     *   cet envoi n'a pas créé dans cette exécution, un autre appareil peut en être à envoyer le sien.
     *
     * @param sessionId   identifiant de la session SSH active.
     * @param remotePath  chemin absolu de destination sur le serveur.
     * @param inputStream flux d'entrée local (SAF InputStream).
     * @param size        taille totale du fichier (pour le calcul de progression). Quand elle est
     *   positive, c'est la taille annoncée par le fournisseur de la source : une source qui livre moins
     *   d'octets fait échouer l'envoi avec [SFTP_SOURCE_TRUNCATED_MESSAGE], sur les trois chemins (nom
     *   libre : le fichier partiel est supprimé ; temporaire : aucun renommage, le temporaire est
     *   supprimé, l'ancien fichier est intact ; en place : refus avant l'ouverture si la source finit
     *   dès le premier bloc, sinon [SFTP_SOURCE_TRUNCATED_IN_PLACE_MESSAGE] signale que le fichier du
     *   serveur, déjà réécrit en partie, est incomplet). Plus
     *   d'octets que la taille annoncée est accepté. Nulle ou inconnue : aucune vérification.
     * @param replaceExisting vrai seulement quand l'utilisateur a confirmé le remplacement d'un
     *   fichier qui porte déjà ce nom : le fichier est alors remplacé par un temporaire (voir plus
     *   haut). Faux, il est ouvert en [OpenMode.EXCL] et le serveur refuse un nom déjà pris, ce qui
     *   rend [SFTP_UPLOAD_TARGET_EXISTS_MESSAGE] sans rien avoir écrit. Sans valeur par défaut, pour
     *   qu'aucun appelant ne remplace un fichier par omission.
     * @param onProgress  lambda appelée avec (bytesTransferred, totalBytes).
     */
    suspend fun writeFile(
        sessionId: String,
        remotePath: String,
        inputStream: InputStream,
        size: Long,
        replaceExisting: Boolean,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ): SshResult<Unit> = withContext(Dispatchers.IO) {
        val sftp = sftpClients[sessionId]
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_NO_SESSION_MESSAGE
            )

        val safePath = sanitizePath(remotePath)
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_INVALID_PATH_MESSAGE
            )

        // Whatever fails while reading the source is the device's, not the connection's: it is marked
        // as such, and reported as a source that cannot be read (see [LocalSourceException]).
        val source: InputStream = LocalSource(inputStream)

        // A free name costs no round trip more than the open: only a confirmed replacement looks at
        // what is there.
        if (!replaceExisting) {
            return@withContext writeDirect(sftp, sessionId, safePath, source, size, inPlace = false, onProgress)
        }

        val plan = try {
            planReplacement(sftp, safePath)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing is written yet: the file that is there is untouched.
            return@withContext uploadFailure(sessionId, e, kept = true)
        }
        return@withContext when (plan) {
            ReplacePlan.Exclusive ->
                writeDirect(sftp, sessionId, safePath, source, size, inPlace = false, onProgress)
            is ReplacePlan.InPlace ->
                writeInPlace(sftp, sessionId, safePath, plan.reason, source, size, onProgress)
            is ReplacePlan.Refused ->
                SshResult.Error(SshErrorCode.UNKNOWN, plan.message)
            is ReplacePlan.Atomic ->
                when (val outcome = replaceThroughTemp(sftp, sessionId, plan, source, size, onProgress)) {
                    is TempOutcome.Finished -> outcome.result
                    is TempOutcome.InPlace ->
                        writeInPlace(sftp, sessionId, safePath, outcome.reason, source, size, onProgress)
                }
        }
    }

    /**
     * Why a confirmed replacement is written in place and not through a temporary file. The names are
     * what the log says (fixed texts: never a path, a name or a text of the server).
     *
     * - [NO_POSIX_RENAME]: the server did not announce `posix-rename@openssh.com`.
     * - [DIR_NOT_WRITABLE]: the temporary file cannot be created in the folder.
     * - [OWNER_NOT_KEPT]: the owner and the group of the old file cannot be given to the new one, or
     *   are not shown by the server (for the old file, or for the new one), or the server refuses to
     *   show the attributes of the temporary file.
     * - [MODE_UNKNOWN]: the server does not show the permissions of the old file: it cannot be told
     *   whether the file is protected against writing, nor what to copy.
     * - [READ_ONLY]: the owner of the old file protected it against writing.
     * - [LINK_NOT_RESOLVED]: a link the server does not resolve, one that is not the user's, one that
     *   does not lead straight to the file (a chain of links, a link of a folder on the way, a target
     *   with a `..` segment, a `readlink` that fails), or a server that does not tell a link from a
     *   file (no `lstat`).
     */
    private enum class InPlaceReason {
        NO_POSIX_RENAME,
        DIR_NOT_WRITABLE,
        OWNER_NOT_KEPT,
        MODE_UNKNOWN,
        READ_ONLY,
        LINK_NOT_RESOLVED,
    }

    /** What [planReplacement] decided to do with a confirmed replacement, before any byte is sent. */
    private sealed interface ReplacePlan {
        /** Nothing is there any more: the file is created like one with a free name. */
        object Exclusive : ReplacePlan

        /** The atomic way is not possible: the old file is overwritten in place, as in 1.1.0. */
        class InPlace(val reason: InPlaceReason) : ReplacePlan

        /**
         * The atomic way: [target] (the real file behind a link) is replaced, [old] are its attributes.
         * [link] is the link itself (its own attributes, from `lstat`) when [target] was reached through
         * one, null otherwise: whose link it is decides whether it may be followed at all.
         */
        class Atomic(val target: String, val old: FileAttributes, val link: FileAttributes?) : ReplacePlan

        /** What is there cannot be replaced: [message] is the fixed text of the refusal. */
        class Refused(val message: String) : ReplacePlan
    }

    /** What [replaceThroughTemp] came to: an end result, or the decision to write in place after all. */
    private sealed interface TempOutcome {
        class Finished(val result: SshResult<Unit>) : TempOutcome
        class InPlace(val reason: InPlaceReason) : TempOutcome
    }

    /** A temporary file of an upload that is open: its path and its handle. */
    private class TempFile(val path: String, val handle: RemoteFile)

    /**
     * The first block of the source of an upload in place, read before the target is opened ([length]
     * bytes of [buffer], a value that is not positive when the source is at its end).
     */
    private class SourceHead(val buffer: ByteArray, val length: Int)

    /** What stopped an upload, for the text it says and for the clean-up it allows. */
    private enum class UploadFault { PERMISSION, NO_SPACE, CHANNEL, IO, SOURCE, TRUNCATED, TRUNCATED_IN_PLACE, OTHER }

    /**
     * Looks at what is at [safePath] and decides how a confirmed replacement is written (see
     * [writeFile]). Everything the server is asked here is asked before the first byte of the source is
     * read. An error that goes up (a lost channel, a refused lookup) stops the upload: nothing was written.
     */
    private fun planReplacement(sftp: SFTPClient, safePath: String): ReplacePlan {
        val own = try {
            sftp.lstat(safePath)
        } catch (e: SFTPException) {
            when (e.statusCode) {
                Response.StatusCode.NO_SUCH_FILE, Response.StatusCode.NO_SUCH_PATH -> return ReplacePlan.Exclusive
                // A server without lstat answers stat, which follows links: a link passes there for the
                // file behind it, and renaming over it would replace the link itself. Not told apart,
                // so a file gets the old way, which goes through the link. What stat shows that is not
                // a file (a folder, a pipe, a socket, a device) is refused here, as with lstat: the open
                // in place of a pipe nobody reads would hold the server.
                Response.StatusCode.OP_UNSUPPORTED -> {
                    val seen = sftp.statExistence(safePath)
                    return when {
                        seen == null -> ReplacePlan.Exclusive
                        seen.type == FileMode.Type.REGULAR || seen.type == FileMode.Type.UNKNOWN ->
                            ReplacePlan.InPlace(InPlaceReason.LINK_NOT_RESOLVED)
                        else -> ReplacePlan.Refused(SFTP_UPLOAD_TARGET_CHANGED_MESSAGE)
                    }
                }
                else -> throw e
            }
        }
        var target = safePath
        var old = own
        when (own.type) {
            FileMode.Type.REGULAR, FileMode.Type.UNKNOWN -> Unit
            FileMode.Type.SYMLINK -> {
                // The link is followed by the server (realpath), nothing is made up here. A link that
                // went broken is refused, a server that does not resolve it leaves the old way.
                val canonical = try {
                    sftp.canonicalize(safePath)
                } catch (e: SFTPException) {
                    return when {
                        e.statusCode == Response.StatusCode.NO_SUCH_FILE ||
                            e.statusCode == Response.StatusCode.NO_SUCH_PATH ->
                            ReplacePlan.Refused(SFTP_UPLOAD_TARGET_CHANGED_MESSAGE)
                        isChannelFailure(e) -> throw e
                        else -> ReplacePlan.InPlace(InPlaceReason.LINK_NOT_RESOLVED)
                    }
                }
                val real = sanitizePath(canonical) ?: return ReplacePlan.InPlace(InPlaceReason.LINK_NOT_RESOLVED)
                val behind = lstatExistence(sftp, real)
                    ?: return ReplacePlan.Refused(SFTP_UPLOAD_TARGET_CHANGED_MESSAGE)
                when (behind.type) {
                    FileMode.Type.SYMLINK -> return ReplacePlan.InPlace(InPlaceReason.LINK_NOT_RESOLVED)
                    FileMode.Type.REGULAR, FileMode.Type.UNKNOWN -> {
                        // `realpath` follows the whole chain in user space. Only a link that leads straight
                        // to the file is followed, never a chain or a path the text and the disk read
                        // differently. The fallback is the 1.1.0 behaviour, nothing more: the kernel decides.
                        if (!linkLeadsStraightTo(sftp, safePath, real)) {
                            return ReplacePlan.InPlace(InPlaceReason.LINK_NOT_RESOLVED)
                        }
                        target = real
                        old = behind
                    }
                    else -> return ReplacePlan.Refused(SFTP_UPLOAD_TARGET_CHANGED_MESSAGE)
                }
            }
            else -> return ReplacePlan.Refused(SFTP_UPLOAD_TARGET_CHANGED_MESSAGE)
        }
        if (!sftp.sftpEngine.supportsServerExtension("posix-rename", "openssh.com")) {
            return ReplacePlan.InPlace(InPlaceReason.NO_POSIX_RENAME)
        }
        // Nothing is replaced without knowing what it keeps: a server that does not give the permissions
        // of the file cannot say whether its owner protected it against writing, nor what to copy; one
        // that does not give its owner cannot say whom it belongs to. The old way is never worse than 1.1.0.
        if (!old.has(FileAttributes.Flag.MODE)) return ReplacePlan.InPlace(InPlaceReason.MODE_UNKNOWN)
        if (!old.has(FileAttributes.Flag.UIDGID)) return ReplacePlan.InPlace(InPlaceReason.OWNER_NOT_KEPT)
        // A file its owner protected against writing: a rename would replace it without a word, where
        // the open in place decides (refused, or allowed to root), exactly as in 1.1.0.
        if ((old.mode.permissionsMask and OWNER_WRITE_BIT) == 0) return ReplacePlan.InPlace(InPlaceReason.READ_ONLY)
        return ReplacePlan.Atomic(target, old, link = if (own.type == FileMode.Type.SYMLINK) own else null)
    }

    /**
     * Whether the link at [linkPath] leads directly to [real], the path that `realpath` gave for it. What
     * the link says (`readlink`) is resolved against the folder of the link as it is written in
     * [linkPath], without resolving anything (an absolute target is taken as it is), then normalized by
     * [sanitizePath]: that must be exactly [real]. A chain of links, a link of a folder on the way (the
     * target or the folder of the link), a `readlink` that is refused or not supported all give a path
     * that differs, or no answer: false, and the replacement is written in place. A channel that is lost
     * goes up.
     *
     * Limit: SSHJ raises, for `readlink`, an [SFTPException] without a status ("READLINK is not
     * supported in SFTPv...", "Unexpected data in NAME"). [isChannelFailure] reads it as a lost channel,
     * and the upload ends as a lost connection instead of being written in place. It cannot be told from
     * a real loss, and the code does not try. It concerns servers of an SFTP protocol older than v3, or
     * that answer `readlink` with a reply SSHJ cannot read.
     *
     * A target with a `..` segment, relative or absolute, is never followed. [sanitizePath] resolves `..`
     * by the text, while the kernel and `realpath` resolve it by the folders as they are: through a link
     * of a folder the two differ, and they can differ in a way that makes them equal by chance. Example:
     * `~/conf` is a link to `/tmp/c`, `/tmp/c/out` a link of the user to `../x`, and a third party made
     * `/tmp/x` a link to `/home/t/x`. For `/home/t/conf/out`, `realpath` says `/home/t/x` and so does the
     * text `/home/t/conf/../x`, while the open in place would have gone to `/tmp/x`, which the kernel
     * would have refused. A name that holds two dots without being a segment (`a..b`) is followed.
     */
    private fun linkLeadsStraightTo(sftp: SFTPClient, linkPath: String, real: String): Boolean {
        val written = try {
            sftp.readlink(linkPath)
        } catch (e: SFTPException) {
            if (isChannelFailure(e)) throw e
            return false
        }
        if (written.split('/').any { it == ".." }) return false
        val folder = linkPath.substringBeforeLast('/', "/").ifEmpty { "/" }
        val candidate = if (written.startsWith("/")) written else "${folder.trimEnd('/')}/$written"
        return sanitizePath(candidate) == real
    }

    /**
     * The replacement written in place, under the real name and with [OpenMode.TRUNC]: the old file is
     * emptied by the open, which only comes after the first block of the source was read (see
     * [writeDirect]). Kept for what the atomic way cannot do (see [writeFile]).
     * The file is the user's, not this upload's: it is never deleted, whatever happens.
     */
    private suspend fun writeInPlace(
        sftp: SFTPClient,
        sessionId: String,
        safePath: String,
        reason: InPlaceReason,
        inputStream: InputStream,
        size: Long,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ): SshResult<Unit> {
        Timber.i("SFTP replace written in place for session ${LogId.of(sessionId)} (reason=$reason)")
        return writeDirect(sftp, sessionId, safePath, inputStream, size, inPlace = true, onProgress)
    }

    /**
     * Writes [inputStream] under the real name [safePath], without a temporary file: with
     * [OpenMode.EXCL] for a name that is free (the server refuses a name that is taken), with
     * [OpenMode.TRUNC] for an upload that overwrites in place ([inPlace]).
     *
     * An exclusive upload that fails or is cancelled deletes, at best, the partial file it just made
     * under the real name, unless the channel is lost (the delete would wait for the server), and only
     * if the name still leads to that file (see [removeIfStillOurs]). The file of an upload in place
     * was there before: it is never deleted.
     *
     * An upload in place reads the first block of the source BEFORE the open, because the open empties
     * the old file: a source that cannot be read, or a job cancelled while it is read, leaves the old
     * file untouched. Once the target is open, that block is written at offset 0 with no point of
     * cancellation in between, and the copy goes on from the next offset. A source that is really empty
     * (end of file at once, no size announced) still empties the file, which is what it holds.
     * An exclusive upload keeps its order (open, then read): the file it opens is its own.
     */
    private suspend fun writeDirect(
        sftp: SFTPClient,
        sessionId: String,
        safePath: String,
        inputStream: InputStream,
        size: Long,
        inPlace: Boolean,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ): SshResult<Unit> {
        var remoteFile: RemoteFile? = null
        // Whether this upload made the file under the real name (its exclusive open succeeded), whether
        // it went through to the end, and whether the channel was lost: what the clean-up of the
        // finally block depends on.
        var created = false
        var completed = false
        var lostChannel = false
        return try {
            val modes = if (inPlace) {
                setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
            } else {
                setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)
            }
            // An upload in place reads the first block of the source before the open, which empties the
            // old file at once: a source that cannot be read leaves the old file as it was, and a slow
            // first read (a provider that downloads the document) does not leave the file empty meanwhile.
            // A job already cancelled does not start that read.
            var head: SourceHead? = null
            if (inPlace) {
                currentCoroutineContext().ensureActive()
                // A failure of the source here is a [LocalSourceException]: nothing is open yet, and the
                // failure below reports it as a source that cannot be read.
                val buffer = ByteArray(COPY_BLOCK_SIZE)
                val first = SourceHead(buffer, inputStream.read(buffer))
                // A source that ends at once while its provider announces a size is not an empty file:
                // refused before the open, which would empty the old file for nothing.
                if (first.length <= 0 && size > 0) throw SourceTruncatedException()
                head = first
            }
            // The last point of cancellation before the open: an open in place empties the old file at
            // once, so a job that was cancelled while the plan was made, while a temporary file was
            // made and removed, or while the first block was read, must not get there.
            currentCoroutineContext().ensureActive()
            val opened = try {
                sftp.open(safePath, modes)
            } catch (e: SFTPException) {
                // Without a confirmed replacement, a refused open may be the server saying that the
                // name is taken. The status alone does not tell: OpenSSH answers FAILURE, so the name
                // is looked up (lstat sees a broken link as well, which EXCL refuses like a file). An open
                // that lost the channel is no answer, and the lookup would only wait for a second timeout.
                if (!inPlace && faultOf(e) != UploadFault.CHANNEL &&
                    (e.statusCode == Response.StatusCode.FILE_ALREADY_EXISTS || entryExists(sftp, safePath))
                ) {
                    Timber.w("SFTP writeFile refused, the name is taken (status=${sftpStatus(e)})")
                    return SshResult.Error(
                        SshErrorCode.UNKNOWN,
                        SFTP_UPLOAD_TARGET_EXISTS_MESSAGE
                    )
                }
                throw e
            }
            remoteFile = opened
            created = !inPlace
            // No point of cancellation between the open and the write of the block already read: the old
            // file is empty from the open on, so the block goes in even if the job was cancelled meanwhile.
            val written = copyInto(opened, inputStream, size, onProgress, head)
            completed = true
            Timber.i("SFTP writeFile done ($written bytes written)")
            SshResult.Success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            lostChannel = faultOf(e) == UploadFault.CHANNEL
            uploadFailure(sessionId, e, kept = false)
        } finally {
            // A file this upload made under the real name and did not finish would pass for a complete
            // one: it is removed, at best. A file that was there before (in place) is never removed.
            // What it looks like is read through the handle, before the close, so that the delete only
            // goes to the file this upload made and not to one that took its name meanwhile.
            // A look that shows the channel is lost is not swallowed: nothing more is asked of the server,
            // no close, no lookup of the name, no delete.
            var partial: FileAttributes? = null
            if (created && !completed && !lostChannel) {
                try {
                    partial = remoteFile?.fetchAttributes()
                } catch (e: Exception) {
                    lostChannel = faultOf(e) == UploadFault.CHANNEL
                    Timber.w("Cannot read the attributes of a remote file (${errorClass(e)})")
                }
            }
            if (!lostChannel) closeQuietly(remoteFile)
            if (partial != null && !lostChannel) removeIfStillOurs(sftp, sessionId, safePath, partial)
        }
    }

    /**
     * The replacement through a temporary file (steps 2 to 4 of [writeFile]). Returns [TempOutcome.InPlace]
     * when the temporary file turns out not to be possible (folder not writable, owner that cannot be
     * kept), before a byte of the source is read, and the temporary file is then gone.
     */
    private suspend fun replaceThroughTemp(
        sftp: SFTPClient,
        sessionId: String,
        plan: ReplacePlan.Atomic,
        inputStream: InputStream,
        size: Long,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ): TempOutcome {
        val target = plan.target
        val old = plan.old
        val parentDir = target.substringBeforeLast('/', "/").ifEmpty { "/" }
        val targetName = target.substringAfterLast('/')

        var temp: TempFile? = null
        var closed = false
        var renamed = false
        var lostChannel = false
        return try {
            // Exclusive (a link laid in advance is not followed) and private from the open itself, in
            // a single request: the umask of the server can only take bits away.
            val ownerOnly = FileAttributes.Builder().withPermissions(TEMP_PERMISSIONS).build()
            var attempts = 0
            while (temp == null && attempts < TEMP_CREATE_ATTEMPTS) {
                attempts++
                val candidate = uploadTempPath(parentDir, targetName, newUploadTempId())
                try {
                    temp = TempFile(candidate, sftp.open(candidate, TEMP_OPEN_MODES, ownerOnly))
                    tempStarted(candidate.substringAfterLast('/'))
                } catch (e: SFTPException) {
                    val fault = faultOf(e)
                    // A folder where nothing can be created: the old way, which writes the file itself.
                    if (fault == UploadFault.PERMISSION) return TempOutcome.InPlace(InPlaceReason.DIR_NOT_WRITABLE)
                    // The drawn name is taken (FAILURE for OpenSSH, FILE_ALREADY_EXISTS for a server that
                    // says so): another one is drawn. Any other refusal stops the upload.
                    val taken = fault == UploadFault.OTHER &&
                        (e.statusCode == Response.StatusCode.FILE_ALREADY_EXISTS ||
                            e.statusCode == Response.StatusCode.FAILURE) &&
                        entryExists(sftp, candidate)
                    if (!taken) throw e
                }
            }
            val file = temp ?: run {
                Timber.w("SFTP replace failed, no free name for the temporary file (session ${LogId.of(sessionId)})")
                return TempOutcome.Finished(SshResult.Error(SshErrorCode.UNKNOWN, SFTP_REPLACE_FAILED_KEPT_MESSAGE))
            }

            // The new file belongs to whoever uploads. The replaced one may not: its owner and group are
            // given back, which only works if the server lets this user do it (root, or a group the user
            // is in). If not, the replacement does not change who owns the file: the old way, which keeps it.
            // A server that refuses to show the attributes of a handle open for writing is not a reason to
            // fail a replacement that the old way would do: same fallback.
            val tempAttributes = try {
                file.handle.fetchAttributes()
            } catch (e: SFTPException) {
                if (faultOf(e) == UploadFault.CHANNEL) throw e
                return TempOutcome.InPlace(InPlaceReason.OWNER_NOT_KEPT)
            }

            // A link is followed only if the user who uploads owns it. `realpath` of the server follows
            // any link, where the kernel refuses to follow, in a sticky folder such as /tmp (the
            // protected_symlinks of Linux, on by default, root included), a link that another user laid
            // there: renaming over the file it leads to would overwrite a file that nobody agreed to
            // replace. The owner of the new temporary file is the user, the only way SFTP v3 shows it.
            // An owner that is not shown counts as another user: the open in place goes through the link, and
            // the kernel decides, as in 1.1.0.
            val link = plan.link
            if (link != null &&
                !(link.has(FileAttributes.Flag.UIDGID) && tempAttributes.has(FileAttributes.Flag.UIDGID) &&
                    link.uid == tempAttributes.uid)
            ) {
                return TempOutcome.InPlace(InPlaceReason.LINK_NOT_RESOLVED)
            }
            // The owner of the old file is known (the plan checked it), the one of the new file must be too:
            // without it, nothing says whether the new file belongs to someone else than the old one did.
            if (!tempAttributes.has(FileAttributes.Flag.UIDGID)) return TempOutcome.InPlace(InPlaceReason.OWNER_NOT_KEPT)
            if (old.uid != tempAttributes.uid || old.gid != tempAttributes.gid) {
                try {
                    file.handle.setAttributes(FileAttributes.Builder().withUIDGID(old.uid, old.gid).build())
                } catch (e: SFTPException) {
                    if (faultOf(e) == UploadFault.CHANNEL) throw e
                    return TempOutcome.InPlace(InPlaceReason.OWNER_NOT_KEPT)
                }
            }

            val written = copyInto(file.handle, inputStream, size, onProgress)

            // The permissions of the old file, without the setuid, setgid and sticky bits (masked by
            // 0777). Refused: the file stays private (0600), stricter than the old file for the permission bits
            // (an ACL of the folder or an inherited SELinux context are not covered, see the limits of writeFile).
            if (old.has(FileAttributes.Flag.MODE)) {
                try {
                    file.handle.setAttributes(
                        FileAttributes.Builder().withPermissions(old.mode.permissionsMask and PERMISSION_BITS).build(),
                    )
                } catch (e: SFTPException) {
                    if (faultOf(e) == UploadFault.CHANNEL) throw e
                    Timber.w("SFTP replace could not copy the permissions, the file stays private (status=${sftpStatus(e)})")
                }
            }

            // Closed here and not left to the clean-up: a server may only report a full disk or a quota
            // at the close, and then the file is not complete.
            closed = true
            file.handle.close()

            // The last point of cancellation. None after the rename: it cannot be undone.
            currentCoroutineContext().ensureActive()

            // The target is looked at again: someone else may have replaced it since the plan (another
            // device, a script), and the rename would overwrite their file, which an open in place could
            // not have done. The old file as the plan saw it must be the one that is there. Still not
            // atomic (the rename follows the lookup), but the window is a request wide, not the whole upload.
            val now = lstatExistence(sftp, target)
            if (now == null || !sameEntry(old, now, strict = false)) {
                Timber.w("SFTP replace refused, the target changed during the upload (session ${LogId.of(sessionId)})")
                return TempOutcome.Finished(SshResult.Error(SshErrorCode.UNKNOWN, SFTP_UPLOAD_TARGET_CHANGED_MESSAGE))
            }
            try {
                sftp.rename(file.path, target, EnumSet.of(RenameFlags.OVERWRITE))
                renamed = true
            } catch (e: SFTPException) {
                // Nothing tells a rename onto a folder from another refusal (FAILURE for OpenSSH): the
                // target is looked at. A channel that is lost proves nothing, it goes up as it is.
                if (faultOf(e) != UploadFault.CHANNEL && holdsNoFile(sftp, target)) {
                    Timber.w("SFTP replace refused, the target is no longer a file (status=${sftpStatus(e)})")
                    return TempOutcome.Finished(SshResult.Error(SshErrorCode.UNKNOWN, SFTP_UPLOAD_TARGET_CHANGED_MESSAGE))
                }
                throw e
            }
            Timber.i("SFTP writeFile done ($written bytes written through a temporary file)")
            TempOutcome.Finished(SshResult.Success(Unit))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            lostChannel = faultOf(e) == UploadFault.CHANNEL
            TempOutcome.Finished(uploadFailure(sessionId, e, kept = true))
        } finally {
            // Whatever the way out (failure, cancellation, the old way taken instead), a temporary file
            // that did not become the file is removed, at best: the old file is never concerned.
            val unfinished = temp
            if (unfinished != null) {
                // The file no longer exists: renamed over the target, or deleted with success.
                var gone = renamed
                try {
                    // After a channel that is proven lost, nothing more is asked of the server: a close would
                    // wait for its answer as long as a delete would.
                    if (!closed && !lostChannel) closeQuietly(unfinished.handle)
                    if (!renamed && !lostChannel) gone = removeQuietly(sftp, sessionId, unfinished.path)
                } finally {
                    // No longer being written, and only now that the clean-up is done. Gone: remembered by
                    // name for the listings that were running. Left behind (lost channel, delete refused):
                    // not remembered, it is a leftover from here on.
                    tempEnded(unfinished.path.substringAfterLast('/'), gone)
                }
            }
        }
    }

    /**
     * Copies [inputStream] into [remote] by blocks of 32 Ko, a point of cancellation between two.
     * [head], the first block of an upload in place that was read before the open, is written first, at
     * offset 0, with no point of cancellation before it; the copy goes on after it. A [head] at the end
     * of the source writes nothing.
     */
    private suspend fun copyInto(
        remote: RemoteFile,
        inputStream: InputStream,
        size: Long,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
        head: SourceHead? = null,
    ): Long {
        var offset = 0L
        val buffer = head?.buffer ?: ByteArray(COPY_BLOCK_SIZE)
        if (head != null) {
            if (head.length <= 0) return 0L
            remote.write(0L, buffer, 0, head.length)
            offset = head.length.toLong()
            onProgress(offset, size)
        }
        while (true) {
            // Point d'annulation, voir readFile.
            currentCoroutineContext().ensureActive()
            val read = inputStream.read(buffer)
            if (read <= 0) break
            remote.write(offset, buffer, 0, read)
            offset += read
            onProgress(offset, size)
        }
        // Fewer bytes than the provider announced: the file is cut short and must not pass for complete.
        // More is accepted, and a size that is not positive (unknown) is not checked.
        // A head means an upload in place: the old file is already emptied, so it says it is incomplete.
        if (size > 0 && offset < size) throw SourceTruncatedException(inPlace = head != null)
        return offset
    }

    /**
     * What stopped an upload. A [UploadFault.CHANNEL] is a channel that is lost or a server that stays
     * silent (see [isChannelFailure]), the case where the server cannot be asked anything more. The
     * permission text comes first: a refusal worded by SSHJ alone has no status.
     */
    private fun faultOf(e: Exception): UploadFault {
        if (e is SFTPException) {
            return when {
                e.statusCode == Response.StatusCode.PERMISSION_DENIED ||
                    e.message?.contains("permission", ignoreCase = true) == true -> UploadFault.PERMISSION
                e.message?.contains("no space", ignoreCase = true) == true -> UploadFault.NO_SPACE
                isChannelFailure(e) -> UploadFault.CHANNEL
                else -> UploadFault.OTHER
            }
        }
        // Before the generic IOException: a source that fails is not a connection that is lost.
        if (e is LocalSourceException) return UploadFault.SOURCE
        if (e is SourceTruncatedException) {
            return if (e.inPlace) UploadFault.TRUNCATED_IN_PLACE else UploadFault.TRUNCATED
        }
        return if (e is IOException) UploadFault.IO else UploadFault.OTHER
    }

    /**
     * The error of an upload that stopped on [e], and its line in the log: the status of the server or
     * the class of the exception, never the exception itself (SSHJ and the server can quote a path).
     * [kept]: the upload goes through a temporary file, so the file that is there is untouched, and an
     * unspecific failure says so ([SFTP_REPLACE_FAILED_KEPT_MESSAGE]).
     */
    private fun uploadFailure(sessionId: String, e: Exception, kept: Boolean): SshResult.Error {
        val msg = when (faultOf(e)) {
            UploadFault.PERMISSION -> SFTP_PERMISSION_DENIED_MESSAGE
            UploadFault.NO_SPACE -> SFTP_NO_SPACE_MESSAGE
            UploadFault.CHANNEL, UploadFault.IO -> SFTP_CONNECTION_LOST_MESSAGE
            UploadFault.SOURCE -> SFTP_SOURCE_UNAVAILABLE_MESSAGE
            UploadFault.TRUNCATED -> SFTP_SOURCE_TRUNCATED_MESSAGE
            UploadFault.TRUNCATED_IN_PLACE -> SFTP_SOURCE_TRUNCATED_IN_PLACE_MESSAGE
            UploadFault.OTHER -> if (kept) SFTP_REPLACE_FAILED_KEPT_MESSAGE else SFTP_UPLOAD_FAILED_MESSAGE
        }
        when (e) {
            is LocalSourceException ->
                Timber.w("SFTP upload stopped, the source cannot be read (${errorClass(e.cause ?: e)}, session ${LogId.of(sessionId)})")
            is SourceTruncatedException ->
                Timber.w("SFTP upload cancelled, the source ended before its announced size (session ${LogId.of(sessionId)})")
            is SFTPException ->
                Timber.w("SFTP writeFile failed status=${sftpStatus(e)} (session ${LogId.of(sessionId)})")
            is IOException ->
                Timber.e("SFTP connection lost during writeFile (${errorClass(e)}, session ${LogId.of(sessionId)})")
            else ->
                Timber.e("Unexpected error during SFTP writeFile (${errorClass(e)}, session ${LogId.of(sessionId)})")
        }
        return SshResult.Error(SshErrorCode.UNKNOWN, msg)
    }

    /** Whether [path] now holds something that is not a regular file. A lookup that fails says nothing. */
    private fun holdsNoFile(sftp: SFTPClient, path: String): Boolean = try {
        val found = lstatExistence(sftp, path)
        found != null && found.type != FileMode.Type.REGULAR && found.type != FileMode.Type.UNKNOWN
    } catch (e: Exception) {
        false
    }

    /** Closes [remote] if there is one; a failure is logged (its class only) and does not stop anything. */
    private fun closeQuietly(remote: RemoteFile?) {
        try {
            remote?.close()
        } catch (e: Exception) {
            Timber.w("Error closing a remote file (${errorClass(e)})")
        }
    }

    /**
     * Deletes [path], the temporary file of an upload that did not finish, at best: a failure is logged
     * (its class only) and swallowed, the error of the upload stays the one that is reported. A blocking
     * call, not a suspension, so it also runs while the upload is being cancelled. True when the server
     * deleted the file.
     */
    private fun removeQuietly(sftp: SFTPClient, sessionId: String, path: String): Boolean {
        return try {
            sftp.rm(path)
            true
        } catch (e: Exception) {
            Timber.w("SFTP clean-up of an unfinished upload failed (${errorClass(e)}, session ${LogId.of(sessionId)})")
            false
        }
    }


    /**
     * Deletes the partial file of an exclusive upload at [path], at best, but only if [path] still leads
     * to the file this upload made: the name is looked up (`lstat`, which does not follow a link) and
     * must show what [ours] showed through the handle, with the size, the time of modification and the
     * owner shown by both and equal, and not be a link. Another writer may have replaced the entry
     * during the copy (NextSH on Desktop replaces through a temporary file and a rename, for one): its
     * complete file is not ours to delete. A name that shows nothing more, a lookup that fails, an
     * attribute the server does not give: nothing is deleted. A server without `lstat` is not asked
     * `stat` instead, which follows links: it cannot be told what the name leads to, nothing is deleted.
     */
    private fun removeIfStillOurs(sftp: SFTPClient, sessionId: String, path: String, ours: FileAttributes) {
        val now: FileAttributes = try {
            sftp.lstat(path)
        } catch (e: SFTPException) {
            val gone = e.statusCode == Response.StatusCode.NO_SUCH_FILE || e.statusCode == Response.StatusCode.NO_SUCH_PATH
            if (!gone) {
                Timber.w("SFTP clean-up of an unfinished upload skipped, the name cannot be looked up (status=${sftpStatus(e)})")
            }
            return
        } catch (e: Exception) {
            Timber.w("SFTP clean-up of an unfinished upload skipped, the name cannot be looked up (${errorClass(e)})")
            return
        }
        if (!sameEntry(ours, now, strict = true)) {
            Timber.w("SFTP clean-up of an unfinished upload skipped, the name leads to another file (session ${LogId.of(sessionId)})")
            return
        }
        removeQuietly(sftp, sessionId, path)
    }

    /**
     * Whether [a] and [b] show the same entry, as far as the server tells: the type, the size, the time
     * of modification, the permissions (the twelve bits, setuid, setgid and sticky included), the owner
     * and the group. An attribute that neither shows is not compared, one that only one of them shows
     * is a difference.
     *
     * [strict]: the size, the time of modification and the owner must be shown by both, and neither may
     * be a link. Without them nothing tells the entry from another one of the same size, or from a link
     * to one. An entry whose type the server does not give counts as a regular file (the handle of a
     * file shows one, the same entry through `lstat` may not).
     */
    private fun sameEntry(a: FileAttributes, b: FileAttributes, strict: Boolean): Boolean {
        fun kindOf(attributes: FileAttributes) =
            if (attributes.type == FileMode.Type.UNKNOWN) FileMode.Type.REGULAR else attributes.type
        if (kindOf(a) != kindOf(b)) return false
        if (strict && (a.type == FileMode.Type.SYMLINK || b.type == FileMode.Type.SYMLINK)) return false
        val sizes = a.has(FileAttributes.Flag.SIZE)
        if (sizes != b.has(FileAttributes.Flag.SIZE) || (sizes && a.size != b.size)) return false
        val times = a.has(FileAttributes.Flag.ACMODTIME)
        if (times != b.has(FileAttributes.Flag.ACMODTIME) || (times && a.mtime != b.mtime)) return false
        val owners = a.has(FileAttributes.Flag.UIDGID)
        if (owners != b.has(FileAttributes.Flag.UIDGID) || (owners && (a.uid != b.uid || a.gid != b.gid))) return false
        val modes = a.has(FileAttributes.Flag.MODE)
        if (modes != b.has(FileAttributes.Flag.MODE) ||
            (modes && a.mode.permissionsMask != b.mode.permissionsMask)
        ) {
            return false
        }
        if (strict && !(sizes && times && owners)) return false
        return true
    }

    /**
     * Cherche ce qui occupe le nom sous lequel un fichier va être envoyé, avant l'envoi.
     *
     * Un seul aller-retour dans le cas courant (le nom est libre) : `lstat` d'abord, qui ne suit pas
     * les liens. Un lien est ensuite résolu avec `stat` pour savoir ce qu'il vise : un lien vers un
     * fichier se traite comme un fichier (le remplacement vise la vraie cible du lien, comme
     * `sftp put` qui écrit à travers le lien, voir [writeFile]), un
     * lien cassé, en boucle ou dont la cible est refusée au `stat` n'est pas remplaçable
     * ([UploadTarget.OTHER]). Un type que le serveur ne donne pas ([FileMode.Type.UNKNOWN]) compte
     * comme un fichier. Un serveur sans `lstat` est interrogé par `stat`. Un canal perdu ou un
     * serveur muet, au `lstat` comme au `stat` d'un lien, est une connexion perdue
     * ([SFTP_CONNECTION_LOST_MESSAGE]) et non une entrée irremplaçable.
     *
     * Cette vérification n'est qu'une politesse faite à l'utilisateur, la garantie est l'ouverture
     * en [OpenMode.EXCL] de [writeFile] : l'appelant qui ne peut pas conclure (permission refusée,
     * `lstat` non pris en charge) envoie quand même, sans remplacement. Un remplacement confirmé
     * relit lui-même la cible juste avant d'écrire ([writeFile]) : ce qui a changé depuis cette
     * vérification est refusé, ou retombe sur le nom libre si la cible a disparu.
     *
     * @param sessionId identifiant de la session SSH active.
     * @param remotePath chemin absolu de destination sur le serveur.
     * @return la nature de l'entrée portant ce nom, ou [SshResult.Error] (pas de client, chemin
     *   refusé, permission refusée, connexion perdue, autre échec).
     */
    suspend fun checkUploadTarget(
        sessionId: String,
        remotePath: String,
    ): SshResult<UploadTarget> = withContext(Dispatchers.IO) {
        val sftp = sftpClients[sessionId]
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_NO_SESSION_MESSAGE
            )

        val safePath = sanitizePath(remotePath)
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_INVALID_PATH_MESSAGE
            )

        return@withContext try {
            val own = lstatExistence(sftp, safePath)
            val target = when {
                own == null -> UploadTarget.FREE
                own.type == FileMode.Type.SYMLINK -> {
                    // stat follows the link; nothing behind it means a broken link. A target the
                    // server cannot follow (a loop, which OpenSSH reports as a plain failure) or that
                    // it refuses to show cannot be replaced either: refused at once and clearly, rather
                    // than sent to be refused by the exclusive open with a message that cannot say why.
                    val resolved = try {
                        sftp.statExistence(safePath)
                    } catch (e: SFTPException) {
                        // Not an answer about the link: a channel that is lost or a server that stays
                        // silent goes up, to be told as a lost connection (see isChannelFailure).
                        if (isChannelFailure(e)) throw e
                        Timber.w("SFTP checkUploadTarget: the link cannot be followed (status=${sftpStatus(e)})")
                        null
                    }
                    if (resolved == null) UploadTarget.OTHER else uploadTargetOf(resolved.type)
                }
                else -> uploadTargetOf(own.type)
            }
            SshResult.Success(target)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SFTPException) {
            // The status code only: the text of the server can quote the path.
            Timber.w("SFTP checkUploadTarget failed status=${sftpStatus(e)}")
            val msg = when {
                e.statusCode == Response.StatusCode.PERMISSION_DENIED -> SFTP_PERMISSION_DENIED_MESSAGE
                e.message?.contains("permission", ignoreCase = true) == true -> SFTP_PERMISSION_DENIED_MESSAGE
                // The lost channel and the silent server are told as what they are: the browser then
                // goes to its disconnected state, instead of sending a file over a link that is gone.
                isChannelFailure(e) -> SFTP_CONNECTION_LOST_MESSAGE
                else -> SFTP_UNKNOWN_MESSAGE
            }
            SshResult.Error(SshErrorCode.UNKNOWN, msg)
        } catch (e: java.io.IOException) {
            Timber.e("SFTP connection lost during checkUploadTarget (${errorClass(e)})")
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_CONNECTION_LOST_MESSAGE)
        } catch (e: Exception) {
            Timber.e("Unexpected error during SFTP checkUploadTarget (${errorClass(e)})")
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_UNKNOWN_MESSAGE)
        }
    }

    /**
     * Whether [e] is a failure of the channel and not an answer of the server. SSHJ raises a channel
     * that is lost as an [SFTPException] with the status UNKNOWN (`EOF while reading packet`), and a
     * server that stays silent as one that carries the timeout as its cause. A status that the server
     * sent (FAILURE, PERMISSION_DENIED, NO_SUCH_FILE...) is an answer and has no cause.
     */
    private fun isChannelFailure(e: SFTPException): Boolean =
        e.statusCode == Response.StatusCode.UNKNOWN || e.cause != null

    /**
     * The exception a lookup of a name (before a creation or a rename) goes up with when its channel is
     * lost or its server stays silent ([UploadFault.CHANNEL], see [faultOf]): an [IOException], which the
     * caller reports as [SFTP_CONNECTION_LOST_MESSAGE] without sending anything more on a dead channel.
     * Its text is never shown nor logged: the callers log [errorClass] only.
     */
    private fun lookupChannelLost(e: SFTPException): IOException =
        IOException("SFTP channel lost during the lookup", e)

    /**
     * The attributes of [path] without following a link, or null when nothing is there. A server that
     * does not support `lstat` (OP_UNSUPPORTED) is asked `stat` instead: it follows a link, so a link
     * shows as its target and a broken one as nothing, which the exclusive open of the upload still
     * refuses, but it is better than no answer.
     */
    private fun lstatExistence(sftp: SFTPClient, path: String): FileAttributes? = try {
        sftp.lstat(path)
    } catch (e: SFTPException) {
        when (e.statusCode) {
            Response.StatusCode.NO_SUCH_FILE, Response.StatusCode.NO_SUCH_PATH -> null
            Response.StatusCode.OP_UNSUPPORTED -> sftp.statExistence(path)
            else -> throw e
        }
    }

    /**
     * Whether something is at [path], a broken link included (unless the server only answers `stat`,
     * see [lstatExistence]). A lookup that fails counts as nothing there: it only serves to name the
     * refusal of an open, which keeps its usual translation then.
     */
    private fun entryExists(sftp: SFTPClient, path: String): Boolean = try {
        lstatExistence(sftp, path) != null
    } catch (e: Exception) {
        false
    }

    /** What an entry that exists is for an upload, from its type (the target of a link, never the link). */
    private fun uploadTargetOf(type: FileMode.Type): UploadTarget = when (type) {
        FileMode.Type.DIRECTORY -> UploadTarget.DIRECTORY
        FileMode.Type.REGULAR, FileMode.Type.UNKNOWN -> UploadTarget.FILE
        else -> UploadTarget.OTHER
    }

    /**
     * Retourne les métadonnées d'un fichier ou dossier (suit les symlinks).
     */
    suspend fun stat(
        sessionId: String,
        path: String,
    ): SshResult<SftpFile> = withContext(Dispatchers.IO) {
        val sftp = sftpClients[sessionId]
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_NO_SESSION_MESSAGE
            )

        val safePath = sanitizePath(path)
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                SFTP_INVALID_PATH_MESSAGE
            )

        return@withContext try {
            val attrs = sftp.stat(safePath)
            val name = safePath.substringAfterLast('/')
            val isDir = attrs.type == FileMode.Type.DIRECTORY
            val sftpFile = SftpFile(
                name        = name.ifEmpty { "/" },
                path        = safePath,
                size        = attrs.size,
                permissions = filePermissionsToInt(attrs.permissions),
                isDirectory = isDir,
                modifiedAt  = attrs.mtime * 1000L,
            )
            SshResult.Success(sftpFile)
        } catch (e: Exception) {
            Timber.w("SFTP stat failed (${errorClass(e)})")
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_UNKNOWN_MESSAGE)
        }
    }

    // ── Sécurité : sanitisation des chemins ───────────────────────────────────────

    /**
     * Sanitise un chemin SFTP distant.
     *
     * Un chemin SFTP n'est pas une URL : il est utilisé tel quel, sans aucun
     * décodage. `+` et `%XX` sont des caractères ordinaires de nom de fichier
     * (un dossier `x+y` ou `100%` doit rester joignable) et `%2e%2e` est un nom
     * littéral, pas une remontée : le serveur ne le décode pas non plus.
     *
     * - Rejette les chemins vides ou blancs
     * - Rejette les null-bytes (attaque null-byte injection)
     * - Garde les `\` tels quels (caractère de nom ordinaire sur un serveur POSIX)
     * - Normalise les slashes multiples et les `.`
     * - Résout les segments `..` en remontant dans l'arborescence
     * - Rejette tout chemin résultant qui sort de la racine `/`
     * - Garantit un chemin absolu (commence par `/`)
     *
     * @return le chemin sanitisé, ou null si le chemin est invalide/dangereux.
     */
    fun sanitizePath(path: String): String? {
        // Rejeter les chemins vides
        if (path.isBlank()) {
            Timber.w("sanitizePath: chemin vide ou blanc rejeté")
            return null
        }

        // Rejeter null-bytes
        if (path.contains('\u0000')) {
            Timber.w("sanitizePath: null-byte détecté dans le chemin")
            return null
        }

        // « \ » n'est pas converti : sur un serveur POSIX c'est un caractère de nom
        // ordinaire, et un dossier nommé « x\..\.. » ferait sinon viser le parent du
        // parent (une suppression viderait un autre dossier). Les serveurs Windows
        // (Win32-OpenSSH) présentent déjà leurs chemins avec des « / ».
        val normalized = path

        // Décomposer en segments
        val segments = normalized.split('/')
        val resolved = ArrayDeque<String>()

        for (segment in segments) {
            when {
                segment.isEmpty() || segment == "." -> {
                    // Ignorer les segments vides et les `.`
                }
                segment == ".." -> {
                    if (resolved.isEmpty()) {
                        // Tentative de remonter au-dessus de la racine
                        Timber.w("sanitizePath: traversal above the root detected")
                        return null
                    }
                    resolved.removeLast()
                }
                else -> resolved.addLast(segment)
            }
        }

        return "/" + resolved.joinToString("/")
    }

    /**
     * Sanitise un nom de fichier (sans chemin).
     *
     * - Supprime les `/`, `\`, null-bytes et caractères de contrôle
     * - Tronque à 255 caractères (limite POSIX)
     *
     * @return le nom sanitisé, ou null si vide après sanitisation.
     */
    fun sanitizeFilename(filename: String): String? {
        val sanitized = filename
            .replace(Regex("[/\\\\\u0000\\p{Cntrl}]"), "")
            .trim()
            .take(255)
        return sanitized.ifEmpty { null }
    }

    /**
     * Name of the SFTP status code carried by [e] (for example `PERMISSION_DENIED`), for the
     * logs. The server's own text, which can quote a path, is never logged.
     */
    private fun sftpStatus(e: net.schmizz.sshj.sftp.SFTPException): String =
        e.statusCode?.name ?: "UNKNOWN"

    // ── Conversion SSHJ → SftpFile ────────────────────────────────────────────────

    private fun RemoteResourceInfo.toSftpFile(): SftpFile {
        val attrs = this.attributes
        val isSymlink = attrs.type == FileMode.Type.SYMLINK
        val isDir = this.isDirectory

        return SftpFile(
            name        = this.name,
            path        = this.path,
            size        = if (isDir) 0L else attrs.size,
            permissions = filePermissionsToInt(attrs.permissions),
            isDirectory = isDir,
            isSymlink   = isSymlink,
            modifiedAt  = attrs.mtime * 1000L,
        )
    }

    /**
     * Convertit un [Set<FilePermission>] SSHJ en entier de permissions POSIX.
     *
     * Utilise le mapping explicite des constantes SSHJ vers les bits POSIX standard,
     * car les ordinals de l'enum ne correspondent PAS aux positions de bits.
     */
    private fun filePermissionsToInt(permissions: Set<FilePermission>): Int {
        var bits = 0
        for (perm in permissions) {
            bits = bits or PERMISSION_BIT_MAP.getOrDefault(perm, 0)
        }
        return bits
    }

    companion object {
        /** The size of the blocks an upload copies by, and of the first block of one written in place. */
        private const val COPY_BLOCK_SIZE = 32768

        /**
         * How the temporary file of a replacement is opened: write only, and new. [OpenMode.EXCL] makes
         * the server refuse a name that is taken, and not follow a link laid there in advance.
         */
        private val TEMP_OPEN_MODES = setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)

        /** The write bit of the owner (0200). Kotlin has no octal literal. */
        private const val OWNER_WRITE_BIT = 0b010_000_000

        /** The nine permission bits of a file, without setuid, setgid and sticky (0777). */
        private const val PERMISSION_BITS = 0b111_111_111

        /** Mapping SSHJ FilePermission → valeur POSIX octal. */
        private val PERMISSION_BIT_MAP = mapOf(
            FilePermission.USR_R to 0b100_000_000,  // 0o400
            FilePermission.USR_W to 0b010_000_000,  // 0o200
            FilePermission.USR_X to 0b001_000_000,  // 0o100
            FilePermission.GRP_R to 0b000_100_000,  // 0o040
            FilePermission.GRP_W to 0b000_010_000,  // 0o020
            FilePermission.GRP_X to 0b000_001_000,  // 0o010
            FilePermission.OTH_R to 0b000_000_100,  // 0o004
            FilePermission.OTH_W to 0b000_000_010,  // 0o002
            FilePermission.OTH_X to 0b000_000_001,  // 0o001
        )
    }
}
