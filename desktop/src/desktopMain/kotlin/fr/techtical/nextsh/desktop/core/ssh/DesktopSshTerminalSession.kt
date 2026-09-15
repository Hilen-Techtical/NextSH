// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.shared.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.direct.Session
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "DesktopSshTerminalSession"

/**
 * Holds the SSHJ plumbing for one shell: client, session channel, shell, and
 * the [SshTtyConnector] that the Compose Canvas renderer consumes. Rendering is
 * entirely delegated to [ComposeTerminalRenderer] with native mouse reporting
 * (xterm modes 1000/1002/1003).
 *
 * A per-session RTT watchdog runs on [scope] and emits round-trip latency (ms)
 * every 20 s via [latencyMs]. Pattern adapted from [DesktopTunnelManager]'s
 * liveness watchdog: here we measure RTT instead of just detecting loss.
 */
class DesktopSshTerminalSession(
    private val sshClient: SSHClient,
    private val session: Session,
    private val shell: Session.Shell,
    private val onExit: () -> Unit,
) {
    val handle: String = UUID.randomUUID().toString()
    private val closed = AtomicBoolean(false)

    val connector: SshTtyConnector = SshTtyConnector(shell)

    // ── Latency watchdog ──────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _latencyMs = MutableStateFlow<Long?>(null)

    /**
     * Live RTT in milliseconds from the most recent `keepalive@openssh.com`
     * probe. `null` means either the first probe hasn't completed yet or the
     * last probe timed out / threw. The watchdog continues running after a
     * failure: it does NOT close the session; that responsibility stays with
     * [DesktopSshSessionManager].
     *
     * SSHJ's built-in [net.schmizz.keepalive.KeepAliveProvider.HEARTBEAT]
     * uses `keepalive@openssh.com` with wantReply=false and swallows errors.
     * Our watchdog sends a separate global request with wantReply=true and
     * measures the RTT around the reply: no conflict.
     */
    val latencyMs: StateFlow<Long?> = _latencyMs.asStateFlow()

    init {
        scope.launch {
            // First probe after 3 s so the user sees a measurement quickly,
            // then settle into a 15 s cadence (low overhead + responsive when
            // the link degrades).
            val firstProbeDelayMs = 3_000L
            val probeIntervalMs = 15_000L
            val replyTimeoutSec = 8L
            val replyTimeoutNanos = TimeUnit.SECONDS.toNanos(replyTimeoutSec)
            // After this many CONSECUTIVE timed-out probes we declare the
            // session dead and trigger [shutdown]. Two probes ≈ 30 s of
            // unresponsive network/server before the UI flips to
            // Disconnected: long enough to ride out a brief WiFi blip,
            // short enough that the reconnect overlay doesn't feel laggy.
            val deadAfterFailures = 2
            var consecutiveFailures = 0
            var loggedFirstSuccess = false
            var loggedFirstFailure = false

            delay(firstProbeDelayMs)
            while (isActive) {
                if (closed.get()) return@launch

                val start = System.nanoTime()
                val rtt: Long? = try {
                    val promise = sshClient.connection.sendGlobalRequest(
                        "keepalive@openssh.com",
                        true,
                        ByteArray(0),
                    )
                    promise.retrieve(replyTimeoutSec, TimeUnit.SECONDS)
                    (System.nanoTime() - start) / 1_000_000L
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ConnectionException) {
                    // OpenSSH (and most servers) reply SSH_MSG_REQUEST_FAILURE
                    // to `keepalive@openssh.com` because the request name is
                    // unknown to the connection layer: but the server still
                    // ACKed our packet, which is exactly what we want. SSHJ
                    // raises this as ConnectionException("Global request ...
                    // failed"). If the elapsed time is below the timeout we
                    // got a real reply, so treat it as a measurement.
                    val elapsedNanos = System.nanoTime() - start
                    if (elapsedNanos < replyTimeoutNanos) {
                        elapsedNanos / 1_000_000L
                    } else {
                        if (!loggedFirstFailure) {
                            Logger.w(TAG, "Latency probe timed out: ${e.message}")
                            loggedFirstFailure = true
                        }
                        null
                    }
                } catch (e: Exception) {
                    if (!loggedFirstFailure) {
                        Logger.w(TAG, "Latency probe failed: ${e::class.simpleName} ${e.message}")
                        loggedFirstFailure = true
                    }
                    null
                }

                _latencyMs.value = rtt
                if (rtt != null) {
                    if (!loggedFirstSuccess) {
                        Logger.d(TAG, "First latency probe OK (${rtt}ms)")
                        loggedFirstSuccess = true
                    }
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures++
                    if (consecutiveFailures >= deadAfterFailures) {
                        Logger.w(TAG, "Session declared dead after $consecutiveFailures consecutive probe failures: triggering shutdown")
                        // Run the teardown off this scope: shutdown() calls
                        // scope.cancel() which would cancel us mid-way; the
                        // shell/session/client closes use blocking I/O that
                        // benefits from a fresh background coroutine.
                        Dispatchers.IO.let { dispatcher ->
                            CoroutineScope(SupervisorJob() + dispatcher).launch { shutdown() }
                        }
                        return@launch
                    }
                }

                delay(probeIntervalMs)
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Exposes the underlying SSHJ client so companion services (SFTP, port
     * forwarding) can open additional channels on the same authenticated
     * connection. Returns null if the session has been shut down.
     */
    fun getSshClient(): SSHClient? = if (closed.get()) null else sshClient

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        // Cancel the watchdog first to avoid a probe racing with disconnect.
        scope.cancel()
        try { shell.close() } catch (_: Exception) {}
        try { session.close() } catch (_: Exception) {}
        try { sshClient.disconnect() } catch (_: Exception) {}
        try { sshClient.close() } catch (_: Exception) {}
        onExit()
    }
}
