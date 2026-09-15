// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.domain.model.SftpFile
import fr.techtical.nextsh.domain.model.SshErrorCode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.xfer.FilePermission
import java.io.InputStream
import java.io.OutputStream
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gère les connexions SFTP au-dessus des sessions SSH actives.
 *
 * Chaque session SSH peut avoir un SFTPClient associé, récupéré depuis
 * [SshSessionManager.getClient]. Toutes les opérations I/O sont exécutées
 * sur [Dispatchers.IO]. Retourne [SshResult], jamais d'exception vers l'UI.
 *
 * Sécurité :
 * - [sanitizePath] rejette les null-bytes et les traversées `..` hors de la racine.
 * - [sanitizeFilename] supprime les caractères dangereux et les séparateurs.
 */
@Singleton
class SftpManager @Inject constructor(
    private val sessionManager: SshSessionManager,
) {

    private val sftpClients = ConcurrentHashMap<String, SFTPClient>()
    private val openMutex = Mutex()

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
                    "Session SSH introuvable : $sessionId"
                )
            return@withContext try {
                val sftp = client.newSFTPClient()
                sftpClients[sessionId] = sftp
                sessionUsers[sessionId] = 1
                Timber.i("SFTP ouvert pour session $sessionId")
                SshResult.Success(Unit)
            } catch (e: Exception) {
                Timber.e(e, "Impossible d'ouvrir le client SFTP pour $sessionId")
                SshResult.Error(SshErrorCode.UNKNOWN, e.message ?: "Erreur SFTP")
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
                Timber.d("SFTP conservé pour $sessionId, $remaining utilisateur(s) restant(s)")
                return@withContext SshResult.Success(Unit)
            }
            sessionUsers.remove(sessionId)
            return@withContext try {
                sftpClients.remove(sessionId)?.close()
                Timber.i("SFTP fermé pour session $sessionId")
                SshResult.Success(Unit)
            } catch (e: Exception) {
                Timber.w(e, "Erreur fermeture SFTP pour $sessionId")
                SshResult.Error(SshErrorCode.UNKNOWN, e.message ?: "Erreur fermeture SFTP")
            }
        }
    }

    /** Vérifie si un SFTPClient est ouvert pour cette session. */
    fun isOpen(sessionId: String): Boolean = sftpClients.containsKey(sessionId)

    // ── Navigation ────────────────────────────────────────────────────────────────

    /**
     * Retourne le répertoire home de l'utilisateur distant (`~`).
     */
    suspend fun getHomeDirectory(sessionId: String): SshResult<String> = withContext(Dispatchers.IO) {
        val sftp = sftpClients[sessionId]
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                "Client SFTP non initialisé pour $sessionId"
            )
        return@withContext try {
            val home = sftp.canonicalize(".")
            Timber.d("SFTP home pour $sessionId : $home")
            SshResult.Success(home)
        } catch (e: Exception) {
            Timber.w(e, "Impossible de résoudre le home SFTP pour $sessionId")
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
                "Client SFTP non initialisé pour $sessionId"
            )

        val safePath = sanitizePath(path)
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                "Chemin invalide ou tentative de traversée détectée : $path"
            )

        return@withContext try {
            val entries: List<RemoteResourceInfo> = sftp.ls(safePath)
            val files = entries.map { info -> info.toSftpFile() }
            SshResult.Success(files)
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            Timber.w(e, "SFTP ls échoué pour $safePath")
            val (code, msg) = when {
                e.message?.contains("permission", ignoreCase = true) == true ->
                    SshErrorCode.AUTH_FAILED to "Permission refusée"
                e.message?.contains("no such", ignoreCase = true) == true ->
                    SshErrorCode.UNKNOWN to "Fichier ou dossier introuvable"
                else -> SshErrorCode.UNKNOWN to "Erreur SFTP"
            }
            SshResult.Error(code, msg)
        } catch (e: java.io.IOException) {
            Timber.e(e, "Connexion SFTP perdue lors du ls de $safePath")
            SshResult.Error(SshErrorCode.UNKNOWN, "Connexion SFTP perdue")
        } catch (e: Exception) {
            Timber.e(e, "Erreur inattendue lors du ls SFTP de $safePath")
            SshResult.Error(SshErrorCode.UNKNOWN, "Erreur SFTP")
        }
    }

    // ── Opérations CRUD ───────────────────────────────────────────────────────────

    /**
     * Crée un répertoire distant.
     *
     * @param sessionId identifiant de la session SSH active.
     * @param path chemin absolu du répertoire à créer.
     * @return [SshResult.Success] ou [SshResult.Error].
     */
    suspend fun createDirectory(
        sessionId: String,
        path: String,
    ): SshResult<Unit> = withContext(Dispatchers.IO) {
        val sftp = sftpClients[sessionId]
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                "Client SFTP non initialisé pour $sessionId"
            )

        // Valider le nom (dernier segment du chemin)
        val name = path.substringAfterLast('/')
        if (sanitizeFilename(name) == null) {
            return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                "Nom de dossier invalide"
            )
        }

        val safePath = sanitizePath(path)
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                "Chemin invalide"
            )

        return@withContext try {
            sftp.mkdir(safePath)
            Timber.i("SFTP mkdir : $safePath")
            SshResult.Success(Unit)
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            Timber.w(e, "SFTP mkdir échoué pour $safePath")
            val msg = when {
                e.message?.contains("permission", ignoreCase = true) == true -> "Permission refusée"
                e.message?.contains("exist", ignoreCase = true) == true -> "Un dossier portant ce nom existe déjà"
                else -> "Impossible de créer le dossier"
            }
            SshResult.Error(SshErrorCode.UNKNOWN, msg)
        } catch (e: java.io.IOException) {
            Timber.e(e, "Connexion SFTP perdue lors du mkdir")
            SshResult.Error(SshErrorCode.UNKNOWN, "Connexion SFTP perdue")
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
                "Client SFTP non initialisé pour $sessionId"
            )

        val safePath = sanitizePath(path)
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                "Chemin invalide"
            )

        return@withContext try {
            sftp.rm(safePath)
            Timber.i("SFTP rm : $safePath")
            SshResult.Success(Unit)
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            Timber.w(e, "SFTP rm échoué pour $safePath")
            val msg = when {
                e.message?.contains("permission", ignoreCase = true) == true -> "Permission refusée"
                e.message?.contains("directory", ignoreCase = true) == true ->
                    "Impossible de supprimer un dossier avec cette opération"
                else -> "Impossible de supprimer le fichier"
            }
            SshResult.Error(SshErrorCode.UNKNOWN, msg)
        } catch (e: java.io.IOException) {
            Timber.e(e, "Connexion SFTP perdue lors du rm")
            SshResult.Error(SshErrorCode.UNKNOWN, "Connexion SFTP perdue")
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
                "Client SFTP non initialisé pour $sessionId"
            )

        val safePath = sanitizePath(path)
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                "Chemin invalide"
            )

        // Interdire la suppression de la racine
        if (safePath == "/") {
            Timber.w("SFTP deleteRecursive: tentative de suppression de la racine refusée")
            return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                "Impossible de supprimer la racine"
            )
        }

        return@withContext try {
            deleteRecursiveInternal(sftp, safePath)
            Timber.i("SFTP deleteRecursive : $safePath")
            SshResult.Success(Unit)
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            Timber.w(e, "SFTP deleteRecursive échoué pour $safePath")
            val msg = when {
                e.message?.contains("permission", ignoreCase = true) == true -> "Permission refusée"
                e.message?.contains("no such", ignoreCase = true) == true -> "Fichier ou dossier introuvable"
                else -> "Impossible de supprimer l'élément"
            }
            SshResult.Error(SshErrorCode.UNKNOWN, msg)
        } catch (e: java.io.IOException) {
            Timber.e(e, "Connexion SFTP perdue lors du deleteRecursive")
            SshResult.Error(SshErrorCode.UNKNOWN, "Connexion SFTP perdue")
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
                sftp.rmdir(entry.path)
            } else {
                sftp.rm(entry.path)
            }
        }
        sftp.rmdir(path)
    }

    /**
     * Renomme (ou déplace dans le même répertoire) un fichier ou dossier.
     *
     * @param sessionId identifiant de la session SSH active.
     * @param oldPath chemin absolu de l'entrée à renommer.
     * @param newName nouveau nom (sans séparateur de chemin).
     */
    suspend fun rename(
        sessionId: String,
        oldPath: String,
        newName: String,
    ): SshResult<Unit> = withContext(Dispatchers.IO) {
        val sftp = sftpClients[sessionId]
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                "Client SFTP non initialisé pour $sessionId"
            )

        val sanitizedName = sanitizeFilename(newName)
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                "Nom de fichier invalide"
            )

        val safeOldPath = sanitizePath(oldPath)
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                "Chemin invalide"
            )

        val parentDir = safeOldPath.substringBeforeLast('/', "/").ifEmpty { "/" }
        val newPath = sanitizePath("$parentDir/$sanitizedName")
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                "Nouveau chemin invalide"
            )

        return@withContext try {
            sftp.rename(safeOldPath, newPath)
            Timber.i("SFTP rename : $safeOldPath → $newPath")
            SshResult.Success(Unit)
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            Timber.w(e, "SFTP rename échoué : $safeOldPath → $newPath")
            val msg = when {
                e.message?.contains("permission", ignoreCase = true) == true -> "Permission refusée"
                e.message?.contains("exist", ignoreCase = true) == true -> "Un fichier portant ce nom existe déjà"
                else -> "Impossible de renommer"
            }
            SshResult.Error(SshErrorCode.UNKNOWN, msg)
        } catch (e: java.io.IOException) {
            Timber.e(e, "Connexion SFTP perdue lors du rename")
            SshResult.Error(SshErrorCode.UNKNOWN, "Connexion SFTP perdue")
        }
    }

    /**
     * Modifie les permissions POSIX d'un fichier ou dossier.
     *
     * @param sessionId identifiant de la session SSH active.
     * @param path chemin absolu de l'entrée cible.
     * @param permissions entier de permissions POSIX (0–511, soit 0o000–0o777).
     */
    suspend fun chmod(
        sessionId: String,
        path: String,
        permissions: Int,
    ): SshResult<Unit> = withContext(Dispatchers.IO) {
        if (permissions < 0 || permissions > 511) {
            return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                "Valeur de permissions invalide (attendu 0–511)"
            )
        }

        val sftp = sftpClients[sessionId]
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                "Client SFTP non initialisé pour $sessionId"
            )

        val safePath = sanitizePath(path)
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                "Chemin invalide"
            )

        return@withContext try {
            sftp.chmod(safePath, permissions)
            Timber.i("SFTP chmod %o : $safePath", permissions)
            SshResult.Success(Unit)
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            Timber.w(e, "SFTP chmod échoué pour $safePath")
            val msg = when {
                e.message?.contains("permission", ignoreCase = true) == true -> "Permission refusée"
                else -> "Impossible de modifier les permissions"
            }
            SshResult.Error(SshErrorCode.UNKNOWN, msg)
        } catch (e: java.io.IOException) {
            Timber.e(e, "Connexion SFTP perdue lors du chmod")
            SshResult.Error(SshErrorCode.UNKNOWN, "Connexion SFTP perdue")
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
                "Client SFTP non initialisé pour $sessionId"
            )

        val safePath = sanitizePath(path)
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                "Chemin invalide : $path"
            )

        var remoteFile: net.schmizz.sshj.sftp.RemoteFile? = null
        return@withContext try {
            remoteFile = sftp.open(safePath)
            val fileSize = remoteFile.length()
            if (fileSize > maxBytes) {
                return@withContext SshResult.Error(
                    SshErrorCode.UNKNOWN,
                    "Fichier trop volumineux pour l'aperçu ($fileSize octets)"
                )
            }
            val buffer = ByteArray(fileSize.toInt())
            remoteFile.read(0, buffer, 0, buffer.size)
            Timber.d("SFTP readPreview : $safePath ($fileSize octets)")
            SshResult.Success(buffer)
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            Timber.w(e, "SFTP readPreview échoué pour $safePath")
            val msg = when {
                e.message?.contains("permission", ignoreCase = true) == true -> "Permission refusée"
                e.message?.contains("no such", ignoreCase = true) == true -> "Fichier introuvable"
                else -> "Impossible de lire le fichier"
            }
            SshResult.Error(SshErrorCode.UNKNOWN, msg)
        } catch (e: java.io.IOException) {
            Timber.e(e, "Connexion SFTP perdue lors du readPreview")
            SshResult.Error(SshErrorCode.UNKNOWN, "Connexion SFTP perdue")
        } catch (e: Exception) {
            Timber.e(e, "Erreur inattendue lors du readPreview de $safePath")
            SshResult.Error(SshErrorCode.UNKNOWN, "Erreur de lecture")
        } finally {
            try {
                remoteFile?.close()
            } catch (e: Exception) {
                Timber.w(e, "Erreur fermeture RemoteFile pour $safePath")
            }
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
                "Client SFTP non initialisé pour $sessionId"
            )

        val safePath = sanitizePath(remotePath)
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                "Chemin invalide : $remotePath"
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
            Timber.i("SFTP readFile : $safePath ($size octets)")
            SshResult.Success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Doit remonter : le catch generique ci-dessous la transformerait en
            // erreur ordinaire et le service croirait le transfert echoue.
            throw e
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            Timber.w(e, "SFTP readFile échoué pour $safePath")
            val msg = when {
                e.message?.contains("permission", ignoreCase = true) == true -> "Permission refusée"
                e.message?.contains("no such", ignoreCase = true) == true -> "Fichier introuvable"
                else -> "Impossible de télécharger le fichier"
            }
            SshResult.Error(SshErrorCode.UNKNOWN, msg)
        } catch (e: java.io.IOException) {
            Timber.e(e, "Connexion SFTP perdue lors du readFile de $safePath")
            SshResult.Error(SshErrorCode.UNKNOWN, "Connexion SFTP perdue")
        } catch (e: Exception) {
            Timber.e(e, "Erreur inattendue lors du readFile de $safePath")
            SshResult.Error(SshErrorCode.UNKNOWN, "Erreur de téléchargement")
        } finally {
            try {
                remoteFile?.close()
            } catch (e: Exception) {
                Timber.w(e, "Erreur fermeture RemoteFile pour $safePath")
            }
        }
    }

    /**
     * Upload un fichier depuis un InputStream vers le serveur distant.
     * Progress callback appelé après chaque chunk de 32 Ko.
     *
     * @param sessionId   identifiant de la session SSH active.
     * @param remotePath  chemin absolu de destination sur le serveur.
     * @param inputStream flux d'entrée local (SAF InputStream).
     * @param size        taille totale du fichier (pour le calcul de progression).
     * @param onProgress  lambda appelée avec (bytesTransferred, totalBytes).
     */
    suspend fun writeFile(
        sessionId: String,
        remotePath: String,
        inputStream: InputStream,
        size: Long,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ): SshResult<Unit> = withContext(Dispatchers.IO) {
        val sftp = sftpClients[sessionId]
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                "Client SFTP non initialisé pour $sessionId"
            )

        val safePath = sanitizePath(remotePath)
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                "Chemin invalide : $remotePath"
            )

        var remoteFile: net.schmizz.sshj.sftp.RemoteFile? = null
        return@withContext try {
            remoteFile = sftp.open(safePath, setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))
            var offset = 0L
            val buffer = ByteArray(32768)
            while (true) {
                // Point d'annulation, voir readFile.
                currentCoroutineContext().ensureActive()
                val read = inputStream.read(buffer)
                if (read <= 0) break
                remoteFile.write(offset, buffer, 0, read)
                offset += read
                onProgress(offset, size)
            }
            Timber.i("SFTP writeFile : $safePath ($offset octets écrits)")
            SshResult.Success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            Timber.w(e, "SFTP writeFile échoué pour $safePath")
            val msg = when {
                e.message?.contains("permission", ignoreCase = true) == true -> "Permission refusée"
                e.message?.contains("no space", ignoreCase = true) == true -> "Espace disque insuffisant"
                else -> "Impossible d'envoyer le fichier"
            }
            SshResult.Error(SshErrorCode.UNKNOWN, msg)
        } catch (e: java.io.IOException) {
            Timber.e(e, "Connexion SFTP perdue lors du writeFile de $safePath")
            SshResult.Error(SshErrorCode.UNKNOWN, "Connexion SFTP perdue")
        } catch (e: Exception) {
            Timber.e(e, "Erreur inattendue lors du writeFile de $safePath")
            SshResult.Error(SshErrorCode.UNKNOWN, "Erreur d'envoi")
        } finally {
            try {
                remoteFile?.close()
            } catch (e: Exception) {
                Timber.w(e, "Erreur fermeture RemoteFile pour $safePath")
            }
        }
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
                "Client SFTP non initialisé pour $sessionId"
            )

        val safePath = sanitizePath(path)
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                "Chemin invalide : $path"
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
            Timber.w(e, "SFTP stat échoué pour $safePath")
            SshResult.Error(SshErrorCode.UNKNOWN, "Erreur stat SFTP")
        }
    }

    // ── Sécurité : sanitisation des chemins ───────────────────────────────────────

    /**
     * Sanitise un chemin SFTP distant.
     *
     * - Rejette les null-bytes (attaque null-byte injection)
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

        // Décoder les séquences percent-encoded (défense contre %2e%2e traversal)
        val decoded = try {
            java.net.URLDecoder.decode(path, "UTF-8")
        } catch (e: IllegalArgumentException) {
            Timber.w("sanitizePath: séquence encodée malformée dans '$path'")
            return null
        }

        // Re-vérifier les null-bytes après décodage
        if (decoded.contains('\u0000')) {
            Timber.w("sanitizePath: null-byte détecté après décodage URL")
            return null
        }

        // Normaliser les séparateurs Windows (défense en profondeur)
        val normalized = decoded.replace('\\', '/')

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
                        Timber.w("sanitizePath: traversée hors racine détectée dans '$path'")
                        return null
                    }
                    resolved.removeLast()
                }
                else -> resolved.addLast(segment)
            }
        }

        val result = "/" + resolved.joinToString("/")
        Timber.v("sanitizePath: '$path' → '$result'")
        return result
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
