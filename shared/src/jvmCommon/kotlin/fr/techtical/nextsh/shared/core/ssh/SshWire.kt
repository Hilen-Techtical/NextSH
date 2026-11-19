// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.ssh

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/** A malformed SSH structure. Carries no message and no content. */
internal class SshWireException : Exception() {
    // No stack trace: it is an expected outcome on untrusted input, and it is never logged.
    override fun fillInStackTrace(): Throwable = this
}

/**
 * Reads SSH wire fields (RFC 4251) without ever trusting a length: every length is checked against the
 * bytes that remain AND against a caller bound BEFORE anything is allocated, so a forged length can never
 * ask for gigabytes (SSHJ 0.38 allocates the announced length of an Ed25519 key or an ECDSA point).
 * Throws [SshWireException] on any malformed field.
 */
internal class SshWireReader(private val bytes: ByteArray) {
    private var pos = 0

    fun remaining(): Int = bytes.size - pos

    fun uint32(): Long {
        if (remaining() < 4) throw SshWireException()
        val value = ((bytes[pos].toLong() and 0xFF) shl 24) or
            ((bytes[pos + 1].toLong() and 0xFF) shl 16) or
            ((bytes[pos + 2].toLong() and 0xFF) shl 8) or
            (bytes[pos + 3].toLong() and 0xFF)
        pos += 4
        return value
    }

    fun raw(length: Int): ByteArray {
        if (length < 0 || length > remaining()) throw SshWireException()
        val value = bytes.copyOfRange(pos, pos + length)
        pos += length
        return value
    }

    /** An SSH `string` of at most [maxLength] bytes. */
    fun string(maxLength: Int = Int.MAX_VALUE): ByteArray {
        val length = uint32()
        if (length > remaining() || length > maxLength) throw SshWireException()
        return raw(length.toInt())
    }

    /** An SSH `string` read as text (names of types, curves, ciphers), at most [maxLength] bytes. */
    fun text(maxLength: Int): String = string(maxLength).decodeToString()

    /** A non-negative SSH `mpint` of at most [maxBytes] bytes (sign byte included). The raw bytes are wiped. */
    fun mpint(maxBytes: Int): BigInteger {
        val value = string(maxBytes)
        try {
            if (value.isEmpty()) return BigInteger.ZERO
            if (value[0] < 0) throw SshWireException() // negative
            return BigInteger(1, value)
        } finally {
            value.fill(0) // may be a private exponent or a prime
        }
    }

    /** Every byte left. */
    fun rest(): ByteArray = raw(remaining())
}

/** A [ByteArrayOutputStream] whose internal buffer can be wiped: it may hold private key bytes. */
private class WipeableByteArrayOutputStream(initialCapacity: Int) : ByteArrayOutputStream(initialCapacity) {
    fun wipe() {
        buf.fill(0)
        reset()
    }
}

/**
 * Writes SSH wire fields (RFC 4251). For private material, give an [initialCapacity] large enough for the
 * whole content (a growing buffer leaves copies behind it) and call [wipe] once done.
 */
internal class SshWireWriter(initialCapacity: Int = 64) {
    private val out = WipeableByteArrayOutputStream(initialCapacity)

    /** Bytes written so far. */
    fun size(): Int = out.size()

    /** Overwrites the internal buffer with zeros and empties it. */
    fun wipe() = out.wipe()

    fun uint32(value: Long): SshWireWriter {
        out.write((value ushr 24).toInt() and 0xFF)
        out.write((value ushr 16).toInt() and 0xFF)
        out.write((value ushr 8).toInt() and 0xFF)
        out.write(value.toInt() and 0xFF)
        return this
    }

    fun raw(value: ByteArray): SshWireWriter {
        out.write(value)
        return this
    }

    fun string(value: ByteArray): SshWireWriter = uint32(value.size.toLong()).raw(value)

    fun string(value: String): SshWireWriter = string(value.encodeToByteArray())

    /** A non-negative `mpint`: minimal two's complement, empty for zero. */
    fun mpint(value: BigInteger): SshWireWriter {
        require(value.signum() >= 0)
        return string(if (value.signum() == 0) ByteArray(0) else value.toByteArray())
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}
