// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.backup

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BackupEnvelopeTest {

    private val salt = ByteArray(32) { (it + 1).toByte() }
    private val iv = ByteArray(12) { (0x40 + it).toByte() }
    private val ciphertext = ByteArray(40) { (0x80 + it).toByte() } // 24 bytes of data + 16 bytes of tag
    private val sealed = byteArrayOf(iv.size.toByte()) + iv + ciphertext

    @Test
    fun `the frame has the documented layout`() {
        val file = BackupEnvelope.pack(salt, sealed)
        assertEquals(4 + 32 + 1 + 12 + 40, file.size)
        assertContentEquals(byteArrayOf(0, 0, 0, 32), file.copyOfRange(0, 4))
        assertContentEquals(salt, file.copyOfRange(4, 36))
        assertEquals(12, file[36].toInt())
        assertContentEquals(iv, file.copyOfRange(37, 49))
        assertContentEquals(ciphertext, file.copyOfRange(49, file.size))
    }

    @Test
    fun `pack then unpack gives the parts back`() {
        val unpacked = assertNotNull(BackupEnvelope.unpack(BackupEnvelope.pack(salt, sealed)))
        assertContentEquals(salt, unpacked.salt)
        assertContentEquals(sealed, unpacked.sealed)
        assertContentEquals(iv, unpacked.iv)
        assertContentEquals(ciphertext, unpacked.ciphertext)
        assertEquals("Unpacked", unpacked.toString())
    }

    @Test
    fun `packParts equals pack of the sealed block`() {
        assertContentEquals(BackupEnvelope.pack(salt, sealed), BackupEnvelope.packParts(salt, iv, ciphertext))
    }

    @Test
    fun `salt sizes from 1 to 1024 are accepted`() {
        for (size in listOf(1, 16, 32, 1024)) {
            val file = BackupEnvelope.pack(ByteArray(size) { 9 }, sealed)
            assertEquals(size, assertNotNull(BackupEnvelope.unpack(file)).salt.size)
        }
    }

    @Test
    fun `pack refuses what unpack would refuse`() {
        assertFailsWith<IllegalArgumentException> { BackupEnvelope.pack(ByteArray(0), sealed) }
        assertFailsWith<IllegalArgumentException> { BackupEnvelope.pack(ByteArray(1025), sealed) }
        assertFailsWith<IllegalArgumentException> { BackupEnvelope.pack(salt, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { BackupEnvelope.pack(salt, byteArrayOf(0) + ByteArray(40)) }
        assertFailsWith<IllegalArgumentException> { BackupEnvelope.pack(salt, byteArrayOf(65) + ByteArray(100)) }
        assertFailsWith<IllegalArgumentException> { BackupEnvelope.pack(salt, byteArrayOf(12) + iv + ByteArray(15)) } // no room for a tag
        assertFailsWith<IllegalArgumentException> { BackupEnvelope.packParts(salt, ByteArray(0), ciphertext) }
    }

    @Test
    fun `an empty plaintext still has room for the tag`() {
        val tagOnly = byteArrayOf(12) + iv + ByteArray(16)
        assertNotNull(BackupEnvelope.unpack(BackupEnvelope.pack(salt, tagOnly)))
    }

    @Test
    fun `unpack refuses a truncated header`() {
        assertNull(BackupEnvelope.unpack(ByteArray(0)))
        assertNull(BackupEnvelope.unpack(ByteArray(3)))
        assertNull(BackupEnvelope.unpack(byteArrayOf(0, 0, 0, 32)))
    }

    @Test
    fun `unpack refuses a bad salt length`() {
        fun withSaltLen(len: Int): ByteArray {
            val file = BackupEnvelope.pack(salt, sealed)
            file[0] = (len ushr 24).toByte()
            file[1] = (len ushr 16).toByte()
            file[2] = (len ushr 8).toByte()
            file[3] = len.toByte()
            return file
        }
        assertNull(BackupEnvelope.unpack(withSaltLen(0)))
        assertNull(BackupEnvelope.unpack(withSaltLen(-1)))
        assertNull(BackupEnvelope.unpack(withSaltLen(1025)))
        assertNull(BackupEnvelope.unpack(withSaltLen(Int.MAX_VALUE)))
        assertNull(BackupEnvelope.unpack(withSaltLen(Int.MIN_VALUE)))
        // Longer than the file: the salt would swallow the rest.
        assertNull(BackupEnvelope.unpack(withSaltLen(100)))
        assertNotNull(BackupEnvelope.unpack(withSaltLen(32)))
    }

    @Test
    fun `unpack refuses a bad IV length or a missing tag`() {
        fun withIvLen(len: Int): ByteArray = BackupEnvelope.pack(salt, sealed).also { it[36] = len.toByte() }
        assertNull(BackupEnvelope.unpack(withIvLen(0)))
        assertNull(BackupEnvelope.unpack(withIvLen(65)))
        assertNull(BackupEnvelope.unpack(withIvLen(255)))
        assertNull(BackupEnvelope.unpack(withIvLen(40))) // 1 + 40 + tag does not fit in what remains
        assertNotNull(BackupEnvelope.unpack(withIvLen(12)))
        // Cut inside the tag.
        val file = BackupEnvelope.pack(salt, byteArrayOf(12) + iv + ByteArray(16))
        assertNull(BackupEnvelope.unpack(file.copyOf(file.size - 1)))
    }

    @Test
    fun `size limit is shared`() {
        assertEquals(50L * 1024 * 1024, BackupLimits.MAX_FILE_BYTES)
        assertEquals(true, BackupLimits.fitsFile(0))
        assertEquals(true, BackupLimits.fitsFile(BackupLimits.MAX_FILE_BYTES))
        assertEquals(false, BackupLimits.fitsFile(BackupLimits.MAX_FILE_BYTES + 1))
        assertEquals(false, BackupLimits.fitsFile(-1))
    }
}
