// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.os.SystemClock
import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * A TCP socket that dates what the server sends: [lastInboundAt] is the time of the last read that
 * returned at least one byte. It is the proof of life of the liveness watchdog
 * ([TunnelLivenessWatchdog]): SSHJ exposes no read time of its own, and the answer to a probe waits
 * behind the data in flight, so on a slow link only the incoming traffic itself says the link lives.
 *
 * Nothing that is read is kept, copied or logged: only a date. A read that returns 0 bytes, `-1`
 * (end of stream) or throws (the socket timeout that SSHJ's reader ignores and retries, a reset)
 * is not activity.
 *
 * [getInputStream] returns the same wrapper on every call, like a plain socket does with its stream.
 *
 * @param clock the time in milliseconds, [SystemClock.elapsedRealtime] in production (it keeps
 *   counting while the device sleeps, unlike the clock of the coroutine delays). Replaceable in tests.
 */
internal class InboundActivitySocket(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : Socket() {

    @Volatile
    private var inboundAt: Long = clock()

    private var stream: InputStream? = null

    /** The time of the last byte received, or of the creation of the socket if none came yet. */
    val lastInboundAt: Long
        get() = inboundAt

    @Synchronized
    override fun getInputStream(): InputStream {
        stream?.let { return it }
        val raw = super.getInputStream()
        val wrapped = object : FilterInputStream(raw) {
            override fun read(): Int {
                val b = super.read()
                if (b >= 0) inboundAt = clock()
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) inboundAt = clock()
                return n
            }
        }
        stream = wrapped
        return wrapped
    }
}

/**
 * The factory SSHJ asks for its socket ([net.schmizz.sshj.SocketClient.setSocketFactory]): every
 * socket it makes is an [InboundActivitySocket]. SSHJ only calls the no-argument [createSocket] and
 * connects the socket itself; the four others stay correct (a created socket, then connected) for
 * anything else.
 */
internal class InboundActivitySocketFactory(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : SocketFactory() {

    override fun createSocket(): Socket = InboundActivitySocket(clock)

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().also { it.connect(java.net.InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().also {
            it.bind(java.net.InetSocketAddress(localHost, localPort))
            it.connect(java.net.InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().also { it.connect(java.net.InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        createSocket().also {
            it.bind(java.net.InetSocketAddress(localAddress, localPort))
            it.connect(java.net.InetSocketAddress(address, port))
        }
}
