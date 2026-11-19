// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.OpenFailException
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.TimeoutException

/**
 * The accept loop of a local forward, from its start to its end. Returns the failure that stopped
 * it, null when it stopped without one (the tunnel was closed).
 *
 * Why a loop. `LocalPortForwarder.listen()` (SSHJ 0.38.0) only catches the `SocketException` of the
 * socket being closed: the channel open of ONE client connection that fails comes out of it as an
 * exception, and used to end the whole tunnel. A client that retries then killed it on every
 * attempt, and the automatic reconnection (a full SSH connection each time) brought it back each
 * time. SSHJ already closes the failed client connection itself, so the listener only has to go
 * back to `listen()`, as long as the listening socket is open and the SSH connection is alive.
 *
 * Two failures of a channel open are survived that way:
 *  - the refusal by the server (`OpenFailException` : the target refuses the connection,
 *    `AllowTcpForwarding no`, `PermitOpen`) ;
 *  - the channel open that got no answer in time (`ConnectionException` caused by a
 *    `TimeoutException`, 30 s by default in SSHJ). sshd answers a `direct-tcpip` only once its own
 *    connection to the target is done or failed: behind a firewall that drops the packets it waits
 *    about two minutes, so SSHJ gives up long before it while the transport is healthy (the
 *    keepalive of the liveness watchdog is answered). Ending the tunnel there would kill it on every
 *    client attempt. A link that is really dead is not hidden by this: [clientConnected] turns false
 *    when the connection is torn down, and the watchdog of the manager ends a silent one.
 *    Known limit: if sshd confirms the channel AFTER the delay of SSHJ (a slow but reachable
 *    target, a slow DNS on the server), `gotOpenConfirmation` opens a channel that nobody reads or
 *    closes. It stays open on the server until the target closes it or the SSH connection ends
 *    (`LocalPortForwarder.startChannel` is private in SSHJ, so there is no simple fix). Before,
 *    ending the tunnel cleaned everything up.
 *
 * A stopped listener is not entered again. A Stop, a release for reconnection or a loss reported
 * elsewhere cancels the job BEFORE closing the socket (without that `listen()` never returns), and
 * `runInterruptible` turns the cancellation into an interrupt of the thread this loop runs on. The
 * loop therefore has to run INSIDE `runInterruptible`, and reads two signals: the interrupt flag
 * of its thread, and [stopRequested], the state of the job, which does not depend on the flag (a
 * piece of code that swallows the interrupt must not send the loop back into `listen()` on a
 * socket that is being closed: it would spin at 100% of a core).
 *
 * What stays fatal, as before: any other `IOException` (lost transport, broken listening socket,
 * any `ConnectionException` that is neither of the two failures above), and the two failures above
 * themselves once the SSH connection is gone.
 *
 * @param listen `LocalPortForwarder.listen`, which blocks until the socket is closed or an
 *   exception comes out.
 * @param serverSocket the listening socket of the tunnel, read through `isClosed` only.
 * @param clientConnected the SSH connection is still established (`SSHClient.isConnected`); must
 *   not throw.
 * @param stopRequested the stop of the tunnel was asked for (the job was cancelled).
 * @param onClientFailed called for every client connection whose channel could not be opened (refused
 *   by the server, or no answer in time) and that the listener survives, before the listening goes
 *   on. The caller logs the class of the exception, which tells the two apart: it knows the
 *   identifier of the tunnel, this function logs nothing.
 * @return null if the loop stopped without a failure (`listen()` returned, a stop was requested,
 *   or the listening socket was closed); otherwise the `IOException` that stopped it. The caller
 *   keeps only its class: its message comes from SSHJ or from the system and can name a host or a
 *   port.
 */
internal fun runLocalForwardListener(
    listen: () -> Unit,
    serverSocket: ServerSocket,
    clientConnected: () -> Boolean,
    stopRequested: () -> Boolean = { false },
    onClientFailed: (IOException) -> Unit = {},
): IOException? {
    while (true) {
        try {
            listen()
            return null
        } catch (e: IOException) {
            // Stopping: whatever ended listen(), a failed channel open racing the stop included, it is
            // not entered again. The stopper owns the state.
            if (Thread.currentThread().isInterrupted || stopRequested()) return null
            if (e.isChannelOpenRefusal() || e.isChannelOpenTimeout()) {
                if (serverSocket.isClosed) return null
                if (clientConnected()) {
                    onClientFailed(e)
                    continue
                }
            }
            return e
        }
    }
}

/** This exception, or one of its causes, is the refusal by the server to open a channel. */
private fun Throwable.isChannelOpenRefusal(): Boolean = anyInCauseChain { it is OpenFailException }

/**
 * This exception, or one of its causes, is a channel open that got no answer in time: the
 * `ConnectionException` that SSHJ's `Promise` raises around a `TimeoutException`. A timeout under
 * another exception (the transport, for one) is not matched.
 */
private fun Throwable.isChannelOpenTimeout(): Boolean =
    anyInCauseChain { it is ConnectionException && it.cause is TimeoutException }
