// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.os.SystemClock
import fr.techtical.nextsh.core.network.NetworkEvent
import fr.techtical.nextsh.core.network.NetworkMonitor
import fr.techtical.nextsh.shared.util.LogId
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.SSHClient
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** The step of the loop of a watched session: it costs nothing on the radio, only the probes do. */
internal const val LIVENESS_TICK_MS = 15_000L

/** The cadence of a session is its host keepalive in seconds, kept between these bounds. */
internal const val LIVENESS_MIN_SECONDS = 15
internal const val LIVENESS_MAX_SECONDS = 60

/**
 * The interval of the SSHJ transport keepalive for a host keepalive of [hostSeconds]: null (no
 * keepalive) for 0 or less, else the value kept between [LIVENESS_MIN_SECONDS] and
 * [LIVENESS_MAX_SECONDS], the same bounds as the cadence of the liveness watchdog. A stored value
 * out of bounds (synced or imported host) is bounded here, at use, never rewritten.
 */
internal fun transportKeepAliveSeconds(hostSeconds: Int): Int? =
    hostSeconds.takeIf { it > 0 }?.coerceIn(LIVENESS_MIN_SECONDS, LIVENESS_MAX_SECONDS)

/** Extra silence, over the cadence, that a probe left unanswered must also reach to declare the death. */
internal const val LIVENESS_DEAD_EXTRA_SILENCE_MS = 30_000L

/** How long the answer to a probe asked for by a network event is waited for. */
internal const val LIVENESS_NUDGE_GRACE_MS = 10_000L

/**
 * Slack on "a probe has been in flight for a whole tick": the delay of the loop and the clock of
 * the device do not count time in exactly the same way, and a missed tick would add 15 s.
 */
internal const val LIVENESS_TICK_SLACK_MS = 1_000L

private const val NOT_WRITING = Long.MIN_VALUE

/** How often the coroutine of a probe looks whether its promise got an answer. */
private const val PROBE_POLL_MS = 1_000L

/** What [LivenessPolicy.decide] asks the loop to do. */
internal enum class LivenessAction { NONE, PROBE, DECLARE_DEAD }

/**
 * The decision of the liveness watchdog for ONE session, without any I/O, so that it can be tested
 * on its own. Times are in milliseconds of a monotonic clock that counts the sleep of the device
 * (the elapsed real time).
 *
 * - [cadenceSeconds] (the host keepalive, 15 to 60 after [coerceIn]) is the silence after which a
 *   probe is sent: the proof of life is any byte received, or the answer to a probe.
 * - Death is declared when a probe has been in flight for a whole tick, no byte and no answer came
 *   since it was sent, AND the silence is at least the cadence plus 30 s. With the default 30 s
 *   that is a probe at 30 to 45 s of silence and the death at 60 to 75 s.
 * - A probe asked for by a network event ([nudge]) is sent at once, whatever the silence, and its
 *   answer is waited for [LIVENESS_NUDGE_GRACE_MS] only.
 *
 * Only one probe is in flight at a time.
 */
internal class LivenessPolicy(cadenceSeconds: Int, private val armedAt: Long) {

    private val silenceBeforeProbeMs = cadenceSeconds.coerceIn(LIVENESS_MIN_SECONDS, LIVENESS_MAX_SECONDS) * 1000L
    private val silenceBeforeDeathMs = silenceBeforeProbeMs + LIVENESS_DEAD_EXTRA_SILENCE_MS

    private var probeSentAt: Long? = null
    private var probeIsNudge = false
    private var nudgeRequested = false

    /** A probe was sent and nothing has cleared it yet (the wait for its answer is still useful). */
    val probeInFlight: Boolean
        get() = probeSentAt != null

    /** A network event happened: probe now, unless a probe is already waiting for its answer. */
    fun nudge() {
        nudgeRequested = true
    }

    /**
     * @param lastInboundAt the time of the last byte received, null when the source does not say
     * @param lastAnswerAt the time of the last answer to a probe, [Long.MIN_VALUE] if none came
     * @param writingSince since when a probe is being WRITTEN (the write may block on a dead link),
     * null when none is. Only the write counts, not the wait for the answer: none is stacked on a
     * write that is stuck, and a stuck write counts as a probe in flight since its start, so the
     * death rule applies to it even when bytes cleared the probe in the meantime.
     */
    fun decide(now: Long, lastInboundAt: Long?, lastAnswerAt: Long, writingSince: Long?): LivenessAction {
        val alive = maxOf(armedAt, lastInboundAt ?: Long.MIN_VALUE, lastAnswerAt)
        if (writingSince != null) {
            nudgeRequested = false
            // A probe asked for by a network event has its own, shorter deadline, written or not,
            // unless bytes came since the write began.
            val nudgeStuck = probeIsNudge && probeSentAt != null &&
                now - writingSince >= LIVENESS_NUDGE_GRACE_MS && alive <= writingSince
            val stuck = nudgeStuck || (
                now - writingSince >= LIVENESS_TICK_MS - LIVENESS_TICK_SLACK_MS &&
                    now - alive >= silenceBeforeDeathMs
                )
            return if (stuck) LivenessAction.DECLARE_DEAD else LivenessAction.NONE
        }
        val sentAt = probeSentAt
        if (sentAt != null) {
            val answered = lastAnswerAt >= sentAt || (lastInboundAt != null && lastInboundAt > sentAt)
            if (answered) {
                probeSentAt = null
            } else {
                nudgeRequested = false
                val age = now - sentAt
                val dead = if (probeIsNudge) {
                    age >= LIVENESS_NUDGE_GRACE_MS
                } else {
                    age >= LIVENESS_TICK_MS - LIVENESS_TICK_SLACK_MS && now - alive >= silenceBeforeDeathMs
                }
                return if (dead) LivenessAction.DECLARE_DEAD else LivenessAction.NONE
            }
        }
        val nudged = nudgeRequested
        nudgeRequested = false
        if (nudged || now - alive >= silenceBeforeProbeMs) {
            probeSentAt = now
            probeIsNudge = nudged
            return LivenessAction.PROBE
        }
        return LivenessAction.NONE
    }

    /** How long the loop may sleep from [now]: a tick, or less to meet the end of a nudge probe's grace. */
    fun nextDelayMs(now: Long): Long {
        val sentAt = probeSentAt
        if (sentAt != null && probeIsNudge) {
            val remaining = LIVENESS_NUDGE_GRACE_MS - (now - sentAt)
            // Past the grace (bytes kept the probe alive, or its write is stuck): a tick, never a spin.
            return if (remaining <= 0) LIVENESS_TICK_MS else remaining.coerceAtMost(LIVENESS_TICK_MS)
        }
        return LIVENESS_TICK_MS
    }
}

/**
 * Notices a mute SSH link under a running tunnel. SSHJ sees nothing when a NAT forgets the
 * connection, a Wi-Fi loses its Internet or a server vanishes without a FIN: the tunnel would stay
 * ACTIVE for ever. The watch is per SESSION, not per tunnel: tunnels share their session with each
 * other and with the terminal, and a reconnection would reuse a dead one.
 *
 * Which sessions: those that carry at least one active tunnel ([SshTunnelManager.activeTunnels]),
 * derived, not told. A session whose host keepalive is 0 is not watched at all (no probe on silence,
 * none on a network event). The cadence is read once, when the watch of the session starts, from
 * what [SshSessionManager] knows of its host at connection: editing the host applies to the next
 * connection.
 *
 * What it does, per session and tick ([LIVENESS_TICK_MS]): nothing while bytes arrive; after the
 * cadence of silence one `keepalive@openssh.com` probe asking for an answer; when that probe stays
 * unanswered and the silence is long enough ([LivenessPolicy]) it calls
 * [SshSessionManager.declareUnresponsive], and everything else (tunnels in error, announcement,
 * reconnection, SFTP, terminal) follows the ordinary end of a session. It NEVER writes the state of
 * a tunnel. A network event ([NetworkMonitor.networkEvents]) makes every watched session send a probe
 * at once, answered within [LIVENESS_NUDGE_GRACE_MS].
 *
 * The probe is sent by a coroutine of its own: writing on a dead link may block, the loop never
 * waits for it and never stacks a second one. No wake lock and no alarm: while the device sleeps
 * nothing is probed, by design.
 *
 * Runs in the scope of `TunnelForegroundService`, next to the reconnection.
 */
@Singleton
class TunnelLivenessWatchdog internal constructor(
    private val activeSessionIds: Flow<Set<String>>,
    private val networkEvents: Flow<NetworkEvent>,
    private val clientOf: (String) -> SSHClient?,
    private val cadenceSecondsOf: (String) -> Int?,
    private val declareUnresponsive: (String, SSHClient) -> Unit,
    /**
     * Sends one probe on the client and suspends until it is answered: false when it was not. It
     * calls its second argument as soon as the probe is WRITTEN, before it waits for the answer.
     */
    private val probe: suspend (SSHClient, () -> Unit) -> Boolean,
    private val inboundAt: (SSHClient) -> Long?,
    private val clock: () -> Long,
    private val ioDispatcher: CoroutineDispatcher,
) {

    @Inject
    constructor(
        sessionManager: SshSessionManager,
        tunnelManager: SshTunnelManager,
        networkMonitor: NetworkMonitor,
    ) : this(
        activeSessionIds = watchedSessionIds(tunnelManager.activeTunnels),
        networkEvents = networkMonitor.networkEvents,
        clientOf = { sessionManager.getClient(it) },
        cadenceSecondsOf = { sessionManager.livenessCadenceSeconds(it) },
        declareUnresponsive = { id, client -> sessionManager.declareUnresponsive(id, client) },
        probe = ::sendKeepAliveProbe,
        inboundAt = { (it.socket as? InboundActivitySocket)?.lastInboundAt },
        clock = { SystemClock.elapsedRealtime() },
        ioDispatcher = Dispatchers.IO,
    )

    private val nudges = ConcurrentHashMap<String, Channel<Unit>>()

    /** Runs until cancelled (the service ends): one loop per watched session, plus the network wake. */
    suspend fun observe() = coroutineScope {
        val loops = HashMap<String, Job>()
        launch {
            networkEvents.collect {
                for (channel in nudges.values) channel.trySend(Unit)
            }
        }
        activeSessionIds.collect { ids ->
            for ((id, job) in loops.entries.toList()) {
                if (id !in ids) {
                    job.cancel()
                    loops.remove(id)
                }
            }
            for (id in ids) {
                if (loops[id]?.isActive == true) continue
                val client = clientOf(id) ?: continue
                val cadence = cadenceSecondsOf(id) ?: continue
                if (cadence <= 0) continue
                val channel = Channel<Unit>(Channel.CONFLATED)
                nudges[id] = channel
                loops[id] = launch {
                    try {
                        watchSession(id, client, cadence, channel)
                    } finally {
                        nudges.remove(id, channel)
                    }
                }
            }
        }
    }

    private suspend fun watchSession(id: String, client: SSHClient, cadenceSeconds: Int, nudge: Channel<Unit>) {
        val policy = LivenessPolicy(cadenceSeconds, armedAt = clock())
        val lastAnswerAt = AtomicLong(Long.MIN_VALUE)
        // Its own scope: a probe blocked on a dead link cannot be cancelled and must not hold the loop.
        val probeScope = CoroutineScope(SupervisorJob() + ioDispatcher)
        var sending: Job? = null
        // Since when a probe is being WRITTEN (not waited for), NOT_WRITING if none: the write is
        // what can block on a dead link, and the only thing that must not be stacked.
        val writingSince = AtomicLong(NOT_WRITING)
        try {
            while (true) {
                if (clientOf(id) !== client || !client.isConnected) return
                val now = clock()
                val writing = writingSince.get().takeIf { it != NOT_WRITING }
                when (policy.decide(now, inboundAt(client), lastAnswerAt.get(), writing)) {
                    LivenessAction.NONE -> Unit
                    LivenessAction.PROBE -> {
                        // A probe is only asked for when no write is in progress: the previous task,
                        // if any, is in its wait for an answer nobody needs any more.
                        sending?.cancel()
                        writingSince.set(now)
                        sending = probeScope.launch {
                            try {
                                val answered = try {
                                    probe(client) { writingSince.compareAndSet(now, NOT_WRITING) }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Timber.w("Liveness probe failed for session ${LogId.of(id)} (${errorClass(e)})")
                                    false
                                }
                                if (answered) lastAnswerAt.set(clock())
                            } finally {
                                writingSince.compareAndSet(now, NOT_WRITING)
                            }
                        }
                    }
                    LivenessAction.DECLARE_DEAD -> {
                        Timber.i("Session ${LogId.of(id)} declared unresponsive by the liveness watchdog")
                        declareUnresponsive(id, client)
                        return
                    }
                }
                // The policy cleared the probe (bytes came): nobody needs its answer any more, and a
                // wait left running would only linger. A write still stuck is left alone (it cannot
                // be cancelled), the policy keeps counting it.
                if (!policy.probeInFlight && writingSince.get() == NOT_WRITING) sending?.cancel()
                val woken = withTimeoutOrNull(policy.nextDelayMs(clock())) { nudge.receive() }
                if (woken != null) policy.nudge()
            }
        } finally {
            probeScope.cancel()
        }
    }
}

/**
 * The sessions to watch: those that carry at least one active tunnel, each once however many
 * tunnels share it. Emits only when the set changes.
 */
internal fun watchedSessionIds(tunnels: Flow<Map<String, ActiveTunnel>>): Flow<Set<String>> =
    tunnels
        .map { all -> all.values.map { it.sessionId }.filter { it.isNotEmpty() }.toSet() }
        .distinctUntilChanged()

/**
 * The default probe: `keepalive@openssh.com` asking for an answer, then a wait for the promise by
 * looking at it, never by a blocking read (`tryRetrieve(0, ...)` on an unfulfilled promise waits for
 * ever). Counts as an answer what [transportAnswered] counts: a success, and the `REQUEST_FAILURE`
 * an OpenSSH server gives to a request it does not know. The sending itself may block on a dead
 * link: it is released by the close of the socket ([SshSessionManager.declareUnresponsive]).
 */
internal suspend fun sendKeepAliveProbe(client: SSHClient, onWritten: () -> Unit = {}): Boolean {
    val promise = try {
        client.connection.sendGlobalRequest("keepalive@openssh.com", true, ByteArray(0))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return false
    }
    onWritten()
    while (!promise.isFulfilled) delay(PROBE_POLL_MS)
    return transportAnswered { promise.tryRetrieve(1, TimeUnit.MILLISECONDS) }
}
