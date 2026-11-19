// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import java.io.ByteArrayOutputStream
import java.math.BigInteger

private const val DER_SEQUENCE = 0x30
private const val DER_INTEGER = 0x02

/** Long form of a DER length that fits one byte (`0x81 nn`), the only long form an ECDSA signature needs. */
private const val DER_LENGTH_ONE_BYTE = 0x81

/**
 * Converts the ASN.1 DER signature that the Android Keystore (like any JCA `SHA256withECDSA`)
 * produces, `SEQUENCE { INTEGER r, INTEGER s }`, into the blob an SSH server expects inside an
 * ECDSA signature (RFC 5656 section 3.1.2): `mpint r` followed by `mpint s`.
 *
 * The caller wraps it the SSH way, `string "ecdsa-sha2-nistp256"` then `string blob`, see
 * [BiometricAuthMethod]. SSHJ has the inverse conversion for its own signatures but nothing for a
 * signature made elsewhere, and its parser assumes short-form lengths without checking any bound,
 * hence this one: the DER comes from a hardware module through the platform, and what is not DER
 * must fail here, loudly, not as a signature the server refuses without saying why.
 *
 * Checked: the tags, that every length is minimal and stays inside its container, that nothing
 * follows the sequence, and that r and s are present, positive and non-zero (an ECDSA value is in
 * `[1, n-1]`, and a negative DER integer would be turned into a negative mpint the server rejects).
 * Not checked: redundant leading zero bytes of an integer. They are harmless, [BigInteger] drops
 * them and the mpint written is canonical whatever the signer did, and refusing them would fail a
 * connection for a vendor's encoder quirk that cannot hurt anyone.
 *
 * @throws IllegalArgumentException when [der] is not a well-formed ECDSA signature
 */
internal fun ecdsaDerToSshSignature(der: ByteArray): ByteArray {
    // Fixed texts: the bytes are a signature, none of it belongs in an exception message.
    fun malformed(why: String): Nothing = throw IllegalArgumentException("Malformed DER ECDSA signature: $why")

    /** Reads a DER length at [at]: its value and the offset of the first byte after it. */
    fun readLength(at: Int): Pair<Int, Int> {
        if (at >= der.size) malformed("truncated length")
        val first = der[at].toInt() and 0xFF
        return when {
            first < 0x80 -> first to at + 1
            first == DER_LENGTH_ONE_BYTE -> {
                if (at + 1 >= der.size) malformed("truncated length")
                val value = der[at + 1].toInt() and 0xFF
                if (value < 0x80) malformed("length not minimal")
                value to at + 2
            }
            else -> malformed("unsupported length form")
        }
    }

    /** Reads one DER INTEGER at [at], inside a container that ends at [end]: its value and the next offset. */
    fun readInteger(at: Int, end: Int): Pair<BigInteger, Int> {
        if (at >= end || (der[at].toInt() and 0xFF) != DER_INTEGER) malformed("integer expected")
        val (length, start) = readLength(at + 1)
        if (length == 0) malformed("empty integer")
        // Subtraction, not start + length: an enormous length must not overflow into a small one.
        if (start > end || length > end - start) malformed("integer overruns its container")
        if ((der[start].toInt() and 0x80) != 0) malformed("negative integer")
        val value = BigInteger(der.copyOfRange(start, start + length))
        if (value.signum() == 0) malformed("zero integer")
        return value to start + length
    }

    if (der.isEmpty() || (der[0].toInt() and 0xFF) != DER_SEQUENCE) malformed("sequence expected")
    val (sequenceLength, sequenceStart) = readLength(1)
    if (sequenceStart > der.size || sequenceLength != der.size - sequenceStart) {
        malformed("sequence length does not match the data")
    }
    val (r, afterR) = readInteger(sequenceStart, der.size)
    val (s, afterS) = readInteger(afterR, der.size)
    if (afterS != der.size) malformed("trailing data in the sequence")

    return ByteArrayOutputStream().use { out ->
        writeMpint(out, r)
        writeMpint(out, s)
        out.toByteArray()
    }
}

/** `mpint` (RFC 4251): a big-endian two's complement string, minimal, with a 4-byte length in front. */
private fun writeMpint(out: ByteArrayOutputStream, value: BigInteger) {
    val bytes = value.toByteArray()
    out.write(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte()))
    out.write(bytes)
    bytes.fill(0)
}
