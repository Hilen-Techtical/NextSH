// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.shared.domain.model.SshResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The reservations ([SftpReservation]) that one drain of the transfer queue holds, one per session.
 *
 * Why not one reservation per transfer. The transfers of a drain run one after the other, and a
 * reservation taken and given back around each leaves a gap between two of them: if the last other
 * holder of the session is gone by then (the browser was left, the tab closed), the release of the
 * first transfer closes the session and the second finds it gone. So a session is reserved by the
 * first transfer that needs it, and kept until no queued request is left for it ([releaseIdle]),
 * or until the drain ends ([releaseAll]).
 *
 * A reservation is only as good as its session. If the link dies while the drain goes on, the SFTP
 * client of the session goes with it (see [SftpManager]), and the reservation that was kept for the
 * next transfers is stale: [obtain] notices it and fails cleanly, before the transfer opens its
 * local file for writing and fails halfway with a raw error.
 *
 * Used by the single coroutine that drains the queue, one transfer at a time: it holds no lock.
 */
class SftpReservations(private val sftpManager: SftpManager) {

    private val held = HashMap<String, SftpReservation>()

    /**
     * The reservation of [sessionId]: the one this drain already holds if its session is still
     * there, else a new one ([SftpManager.reserve]), whose error (session gone, SFTP channel
     * refused) is passed on.
     *
     * A held reservation whose SFTP client is no longer open belongs to a session that ended: it is
     * dropped and given back (a no-op on a dead session, but its count of SFTP users must go), then
     * a new reservation is asked for, which fails for a session that is gone.
     */
    suspend fun obtain(sessionId: String): SshResult<SftpReservation> {
        val current = held[sessionId]
        if (current != null) {
            if (sftpManager.isOpen(sessionId)) return SshResult.Success(current)
            withContext(NonCancellable) { held.remove(sessionId)?.release() }
        }
        val reserved = sftpManager.reserve(sessionId)
        if (reserved is SshResult.Success) held[sessionId] = reserved.data
        return reserved
    }

    /**
     * Gives back the reservation of every session that no queued request targets any more
     * ([queuedSessionIds]), and keeps the others. Asked after each transfer, for all the sessions
     * held and not only the one that just ended: a request cancelled while another session's
     * transfer ran would otherwise keep its session open until the end of the drain. A session
     * closes if the transfers were its last holders.
     *
     * Not cancellable: a reservation taken out of the map and not given back would be lost.
     */
    suspend fun releaseIdle(queuedSessionIds: Set<String>) {
        withContext(NonCancellable) {
            val idle = held.keys.filter { it !in queuedSessionIds }
            idle.forEach { held.remove(it)?.release() }
        }
    }

    /** Gives every reservation back: the drain is over, however it ended. */
    suspend fun releaseAll() {
        withContext(NonCancellable) {
            val all = held.values.toList()
            held.clear()
            all.forEach { it.release() }
        }
    }
}
