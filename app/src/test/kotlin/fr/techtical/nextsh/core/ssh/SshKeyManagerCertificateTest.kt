// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * A certificate can come from a backup (anyone who knows the passphrase can write one). SSHJ reads
 * the announced length without bounding it, so a hostile blob must end as an unreadable
 * certificate (an exception), never as an OutOfMemoryError that kills the process.
 */
class SshKeyManagerCertificateTest {

    private val manager = SshKeyManager()

    private fun line(blob: ByteArray) = "ssh-ed25519-cert-v01@openssh.com " + Base64.getEncoder().encodeToString(blob)

    @Test
    fun `a certificate announcing a gigantic string is an ordinary failure, not an OutOfMemoryError`() {
        // [length 0x7FFFFFFF] then a few bytes: SSHJ would try to allocate 2 GiB.
        val hostile = byteArrayOf(0x7F, -1, -1, -1, 1, 2, 3, 4)

        val failure = runCatching { manager.readCertificatePublicKey(line(hostile)) }.exceptionOrNull()

        assertTrue(failure != null, "the hostile certificate must be refused")
        assertTrue(failure is Exception, "an Error (OutOfMemoryError) must not escape, got $failure")
    }

    @Test
    fun `the failure message holds nothing of the certificate`() {
        val hostile = byteArrayOf(0x7F, -1, -1, -1, 1, 2, 3, 4)
        val text = line(hostile)

        val failure = assertThrows(Exception::class.java) { manager.readCertificatePublicKey(text) }

        assertFalse(failure.message.orEmpty().contains(Base64.getEncoder().encodeToString(hostile)))
    }

    @Test
    fun `an empty or malformed line keeps its explicit refusal`() {
        assertThrows(IllegalArgumentException::class.java) { manager.readCertificatePublicKey("") }
        assertThrows(IllegalArgumentException::class.java) { manager.readCertificatePublicKey("ssh-ed25519-cert-v01@openssh.com") }
    }
}
