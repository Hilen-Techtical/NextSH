// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.shared.util.Logger
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey

private const val TAG = "DesktopKnownHostsVerifier"

/**
 * SSHJ [HostKeyVerifier] implementation backed by [DesktopKnownHostsStore].
 *
 * Three-way outcome:
 * - **Trusted** (stored key matches): `verify` returns `true`, connection proceeds.
 * - **Unknown** (no record): delegates to [unknownHostCallback]: the UI shows a
 *   TOFU confirmation dialog. If the user accepts, the key is persisted and
 *   `verify` returns `true`.
 * - **Mismatch** (stored key != received): `verify` returns `false` immediately,
 *   no user bypass. The user must explicitly wipe the old entry via the
 *   KnownHosts settings screen to re-trust: prevents silent MITM on a compromised
 *   route.
 *
 * SSHJ calls [verify] on an IO thread during the SSH handshake. The UI dialog
 * is async, so we bridge via `runBlocking`: same pattern as Android's
 * `InteractiveHostKeyVerifier`. Safe because the caller (SSH thread) is dedicated
 * to this single handshake.
 */
class DesktopKnownHostsVerifier(
    private val store: DesktopKnownHostsStore,
) : HostKeyVerifier {

    /**
     * Called when the host key is unknown. Returning `true` means "trust & store",
     * `false` means "reject". Null means no callback was wired yet (reject by default).
     */
    var unknownHostCallback: (suspend (HostKeyVerifyResult.Unknown) -> Boolean)? = null

    /**
     * Fire-and-forget notification when a mismatch is detected (rejected without
     * user bypass). UI observes to display a post-mortem alert: not a decision
     * point. Null means no listener wired yet.
     */
    var mismatchListener: ((HostKeyVerifyResult.Mismatch) -> Unit)? = null

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        return when (val result = checkKey(hostname, port, key)) {
            is HostKeyVerifyResult.Trusted -> true
            is HostKeyVerifyResult.Unknown -> {
                val cb = unknownHostCallback
                if (cb == null) {
                    Logger.w(TAG, "Unknown host ${result.hostPort} and no UI callback wired: rejecting")
                    false
                } else {
                    val accepted = runBlocking { cb(result) }
                    if (accepted) {
                        store.upsert(result.hostPort, key.algorithm, key.encoded)
                        Logger.d(TAG, "Host ${result.hostPort} accepted by user: persisted")
                        true
                    } else {
                        Logger.d(TAG, "Host ${result.hostPort} rejected by user")
                        false
                    }
                }
            }
            is HostKeyVerifyResult.Mismatch -> {
                Logger.w(
                    TAG,
                    "HOST KEY MISMATCH for ${result.hostPort}: " +
                        "stored=${result.storedFingerprint} received=${result.receivedFingerprint}: rejecting",
                )
                mismatchListener?.invoke(result)
                false
            }
        }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> {
        val hostPort = "$hostname:$port"
        val stored = store.getStored(hostPort) ?: return emptyList()
        return listOf(stored.first)
    }

    /**
     * Pure computation: no side effects, no user prompt. Returned to callers who
     * want to inspect the state without persisting. [verify] dispatches on this.
     */
    fun checkKey(hostname: String, port: Int, key: PublicKey): HostKeyVerifyResult {
        val hostPort = "$hostname:$port"
        val received = DesktopKnownHostsStore.sha256Fingerprint(key.encoded)

        val stored = store.getStored(hostPort)
        return when {
            stored == null -> HostKeyVerifyResult.Unknown(
                hostPort = hostPort,
                algorithm = key.algorithm,
                fingerprint = received,
            )
            stored.first == key.algorithm && stored.second.contentEquals(key.encoded) ->
                HostKeyVerifyResult.Trusted
            else -> HostKeyVerifyResult.Mismatch(
                hostPort = hostPort,
                storedFingerprint = DesktopKnownHostsStore.sha256Fingerprint(stored.second),
                receivedFingerprint = received,
            )
        }
    }
}
