// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.backup

/**
 * The binary frame of a `.nextsh` file:
 *
 * `[SALT_LEN(4, big endian)][SALT(32)][IV_LEN(1)][IV(12)][CIPHERTEXT||TAG]`
 *
 * The part after the salt, `[IV_LEN(1)][IV][CIPHERTEXT||TAG]`, is exactly what `encryptAesGcm` of
 * `CryptoUtils` (Android) and `DesktopCryptoUtils` (Desktop) returns and what their `decryptAesGcm`
 * takes: it is called `sealed` here. This object does NOT derive keys and does NOT encrypt: it only
 * puts bytes into the frame and takes them out, with one size rule for both platforms.
 *
 * Rules on read: the whole file is at most [BackupLimits.MAX_FILE_BYTES]; the salt length is
 * 1..1024 and fits in the file; `IV_LEN` is 1..64 (the range both decryptors accept) and at least
 * one 16-byte GCM tag follows the IV. A violation returns null (the caller reports a malformed file).
 */
object BackupEnvelope {

    /** Size of the salt the exporters generate. */
    const val DEFAULT_SALT_BYTES: Int = 32

    private const val SALT_LEN_FIELD = 4
    private const val MIN_IV = 1
    private const val MAX_IV = 64
    private const val GCM_TAG_BYTES = 16

    /** A frame taken apart. The arrays are fresh copies owned by the caller (wipe them after use). */
    class Unpacked(
        val salt: ByteArray,
        /** `[IV_LEN(1)][IV][CIPHERTEXT||TAG]`, ready for `decryptAesGcm`. */
        val sealed: ByteArray,
    ) {
        /** The IV alone. */
        val iv: ByteArray get() = sealed.copyOfRange(1, 1 + (sealed[0].toInt() and 0xFF))

        /** The ciphertext with its GCM tag, without the IV. */
        val ciphertext: ByteArray get() = sealed.copyOfRange(1 + (sealed[0].toInt() and 0xFF), sealed.size)

        // Hand written: never print bytes.
        override fun toString(): String = "Unpacked"
    }

    /**
     * Puts [salt] and [sealed] (`[IV_LEN(1)][IV][CIPHERTEXT||TAG]`) into a frame.
     * @throws IllegalArgumentException when the salt or the sealed block would not pass [unpack] (a programming error).
     */
    fun pack(salt: ByteArray, sealed: ByteArray): ByteArray {
        require(salt.size in BackupLimits.MIN_SALT_BYTES..BackupLimits.MAX_SALT_BYTES) { "Invalid salt size" }
        require(isValidSealed(sealed, 0, sealed.size)) { "Invalid sealed block" }
        val total = SALT_LEN_FIELD.toLong() + salt.size + sealed.size
        require(total <= BackupLimits.MAX_FILE_BYTES) { "Backup too large" }

        val out = ByteArray(total.toInt())
        val len = salt.size
        out[0] = (len ushr 24).toByte()
        out[1] = (len ushr 16).toByte()
        out[2] = (len ushr 8).toByte()
        out[3] = len.toByte()
        salt.copyInto(out, SALT_LEN_FIELD)
        sealed.copyInto(out, SALT_LEN_FIELD + salt.size)
        return out
    }

    /** Same as [pack] from the three parts (an [iv] of 1..64 bytes). */
    fun packParts(salt: ByteArray, iv: ByteArray, ciphertextAndTag: ByteArray): ByteArray {
        require(iv.size in MIN_IV..MAX_IV) { "Invalid IV size" }
        val sealed = ByteArray(1 + iv.size + ciphertextAndTag.size)
        sealed[0] = iv.size.toByte()
        iv.copyInto(sealed, 1)
        ciphertextAndTag.copyInto(sealed, 1 + iv.size)
        return pack(salt, sealed)
    }

    /** Takes a frame apart; null when [file] is not a valid frame (including when it is too large). */
    fun unpack(file: ByteArray): Unpacked? {
        if (!BackupLimits.fitsFile(file.size.toLong())) return null
        if (file.size < SALT_LEN_FIELD) return null
        val saltLen = ((file[0].toInt() and 0xFF) shl 24) or
            ((file[1].toInt() and 0xFF) shl 16) or
            ((file[2].toInt() and 0xFF) shl 8) or
            (file[3].toInt() and 0xFF)
        if (saltLen !in BackupLimits.MIN_SALT_BYTES..BackupLimits.MAX_SALT_BYTES) return null
        // Subtraction form: no overflow whatever the claimed length.
        val sealedStart = SALT_LEN_FIELD + saltLen
        if (sealedStart >= file.size) return null
        if (!isValidSealed(file, sealedStart, file.size - sealedStart)) return null
        return Unpacked(
            salt = file.copyOfRange(SALT_LEN_FIELD, sealedStart),
            sealed = file.copyOfRange(sealedStart, file.size),
        )
    }

    private fun isValidSealed(bytes: ByteArray, offset: Int, length: Int): Boolean {
        if (length < 1) return false
        val ivLen = bytes[offset].toInt() and 0xFF
        if (ivLen !in MIN_IV..MAX_IV) return false
        return length - 1 - ivLen >= GCM_TAG_BYTES
    }
}
