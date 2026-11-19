// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import timber.log.Timber
import java.security.PublicKey

/**
 * Adaptateur HostKeyVerifier qui délègue à [KnownHostsVerifier.checkKey] et,
 * en cas de première connexion, demande à l'utilisateur via [callback]
 * ([HostKeyPromptCoordinator.promptUnknown] dans l'app).
 *
 * Exécuté sur le thread de lecture du transport SSH pendant l'échange de clés : runBlocking est
 * acceptable ici car on attend la décision de l'utilisateur avant de continuer le handshake. Ce
 * thread ne sait rien de la coroutine qui connecte : la demande en devient donc l'enfant
 * ([connection], le Job de la coroutine de connexion), comme celles de la clé de sécurité et de
 * l'empreinte (voir `requestFido2Signature`). Annuler la connexion (écran quitté, démarrage de
 * tunnel abandonné) retire la demande qui est affichée ou en file, au lieu de la laisser jusqu'à
 * son délai.
 *
 * Une instance par connexion : [promptEnd] dit comment la demande de CETTE connexion s'est
 * terminée, quand elle s'est terminée sans que l'utilisateur ait répondu. SSHJ, lui, ne voit qu'un
 * refus de la clé, et le ferait lire comme une clé qui ne correspond plus (voir [HostKeyPromptEnd]).
 *
 * @param callback lue à chaque clé inconnue et non à la construction : le callback est posé par
 *   `App.onCreate` sur le gestionnaire de sessions, et null avant (la clé est alors refusée).
 */
internal class InteractiveHostKeyVerifier(
    private val knownHosts: KnownHostsVerifier,
    private val callback: () -> (suspend (HostKeyVerifyResult.Unknown) -> HostKeyPromptResponse)?,
    private val connection: Job?,
) : HostKeyVerifier {

    /**
     * Comment la demande s'est terminée sans réponse de l'utilisateur (personne à l'écran, délai
     * écoulé, connexion annulée), null sinon : une clé acceptée ou refusée par l'utilisateur n'en
     * est pas une. Écrit par le thread de transport, lu par celui qui connecte après l'échec.
     */
    @Volatile
    var promptEnd: HostKeyPromptEnd? = null
        private set

    /** The scope of the prompt that is up or queued for this connection, null when there is none. */
    @Volatile
    private var activePrompt: Job? = null

    /** The connection is over: no prompt is to be shown for it any more, see [abandonPrompt]. */
    @Volatile
    private var abandoned = false

    /**
     * The connection failed or gave up while a prompt may still be up or queued for it (SSHJ's wait
     * ran out, for instance): takes it away. Without it the dialog would stay on screen, and the
     * connecting coroutine would wait for its end, for a connection that is already over. Call it
     * after reading [promptEnd]: the prompt then ends as cancelled, which is not why the connection
     * failed.
     *
     * It also forbids the prompts to come: the thread of SSHJ that was still on its way to ask (the
     * key exchange was slow, the connection gave up under it) would otherwise open a dialog for a
     * connection that no longer exists, and the key the user trusted in it would be stored for
     * nothing.
     */
    fun abandonPrompt() {
        abandoned = true
        activePrompt?.cancel()
    }

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        return when (val result = knownHosts.checkKey(hostname, port, key)) {
            is HostKeyVerifyResult.Trusted -> true

            is HostKeyVerifyResult.Unknown -> confirm(result, key)

            is HostKeyVerifyResult.Mismatch -> {
                // The fingerprints are never logged. The connection then fails with
                // HOST_KEY_MISMATCH, which is what the user gets to see (no fingerprint either).
                Timber.e("KnownHosts: host key mismatch, algorithm=${key.algorithm}")
                false
            }
        }
    }

    private fun confirm(unknown: HostKeyVerifyResult.Unknown, key: PublicKey): Boolean {
        // First of all: nobody is waiting for the answer of an abandoned connection.
        if (abandoned) {
            promptEnd = HostKeyPromptEnd.CANCELLED
            return false
        }
        // A host the known hosts file cannot store (blank, control or invisible format character,
        // bad port): no prompt, a key nobody can remember must not open the connection. The user
        // already got a readable message from SshSessionManager before the connection started.
        if (!KnownHostsVerifier.isStorableHostPort(unknown.hostname)) {
            Timber.w("KnownHosts: unknown host key, host address not storable, refused")
            return false
        }
        val ask = callback()
        if (ask == null) {
            Timber.w("KnownHosts: unknown host key (algorithm=${unknown.algorithm}), refused (no UI callback)")
            return false
        }
        // The prompt gets a scope of its own, a child of the connection: cancelling the connection
        // cancels it, and so does abandonPrompt.
        val scope = Job(connection)
        activePrompt = scope
        // Abandoned between the check above and the line before, when abandonPrompt found no scope to cancel.
        if (abandoned) scope.cancel()
        val response = try {
            runBlocking { withContext(scope) { ask(unknown) } }
        } catch (e: CancellationException) {
            // The connection was cancelled while the prompt was up or queued, and took it away.
            // Refused: the handshake ends at once instead of waiting for a person who is not coming.
            promptEnd = HostKeyPromptEnd.CANCELLED
            return false
        } finally {
            activePrompt = null
            // Over: a child that stayed active would keep the connecting coroutine from completing.
            scope.cancel()
        }
        return when (response) {
            HostKeyPromptResponse.Trusted -> {
                // An answer that reaches here while the vault is being wiped is "no": nothing is stored
                // (see KnownHostsVerifier.acceptHost), and the connection of an erased app does not open.
                if (knownHosts.isWriteRefused()) {
                    Timber.w("KnownHosts: unknown host key, not saved while the app is being wiped")
                    promptEnd = HostKeyPromptEnd.NO_SCREEN
                    return false
                }
                // A key that cannot be remembered does not open the connection.
                val stored = knownHosts.acceptHost(unknown.hostname, unknown.algorithm, key.encoded)
                // The gate closed between the test above and the call: not a key that changed.
                if (!stored && knownHosts.isWriteRefused()) promptEnd = HostKeyPromptEnd.NO_SCREEN
                stored
            }
            // Trusted while this request waited for its turn, and stored by the prompt that did it.
            HostKeyPromptResponse.AlreadyTrusted -> true
            HostKeyPromptResponse.Refused -> false
            HostKeyPromptResponse.NoScreen -> {
                Timber.w("KnownHosts: unknown host key, nobody on screen to confirm it")
                promptEnd = HostKeyPromptEnd.NO_SCREEN
                false
            }
            HostKeyPromptResponse.TimedOut -> {
                Timber.w("KnownHosts: unknown host key, not confirmed in time")
                promptEnd = HostKeyPromptEnd.TIMED_OUT
                false
            }
        }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
        knownHosts.findExistingAlgorithms(hostname, port)
}
