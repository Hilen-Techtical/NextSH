// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.auth.fido2

import fr.techtical.nextsh.desktop.assertNothingScrubbed
import fr.techtical.nextsh.desktop.captureLogs
import fr.techtical.nextsh.desktop.taggedBy
import java.io.IOException
import javax.smartcardio.Card
import javax.smartcardio.CardTerminal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Log content of [YubiKitFidoManager], without any hardware: the three enumeration seams of the
 * constructor are replaced, so no hid4java native library is loaded and no PC/SC reader is
 * reached. The sources fail on purpose, with messages that quote a reader, a user name and a path.
 */
class YubiKitFidoManagerLogTest {

    /** A reader as `javax.smartcardio` would list it; [onPresenceCheck] stands in for a driver fault. */
    private class FakeTerminal(
        private val readerName: String,
        private val onPresenceCheck: () -> Boolean,
    ) : CardTerminal() {
        override fun getName(): String = readerName
        override fun isCardPresent(): Boolean = onPresenceCheck()
        override fun connect(protocol: String?): Card = throw UnsupportedOperationException()
        override fun waitForCardPresent(timeout: Long): Boolean = false
        override fun waitForCardAbsent(timeout: Long): Boolean = false
    }

    @Test
    fun `enumerating devices logs counts and status codes, never a reader name, a path or a message`() {
        val manager = YubiKitFidoManager(
            hidDeviceSource = { throw UnsatisfiedLinkError("C:\\Users\\alice\\AppData\\Local\\Temp\\hidapi-secret.dll") },
            pcscDeviceSource = {
                throw IOException("SCARD_E_NO_READERS_AVAILABLE for reader ACME-Secret-Reader (0x8010002E)")
            },
            terminalSource = {
                listOf(
                    FakeTerminal("ACME-Secret-Reader 0") { true },
                    FakeTerminal("ACME-Secret-Reader 1") { throw IllegalStateException("ACME-Secret-Reader 1 unplugged (0x80100069)") },
                )
            },
        )

        // The sink is global: judge the manager's own lines for content, every line for the leak.
        val captured = captureLogs { assertEquals(emptyList(), manager.listDevices()) }
        val lines = captured.taggedBy("YubiKit")

        // What stays: counts, ordinals, the exception class and the fixed vocabulary of the status.
        assertTrue(lines.any { it.contains("PC/SC#0 isCardPresent=true") }, "got $lines")
        assertTrue(lines.any { it.contains("isCardPresent failed for PC/SC#1") && it.contains("IllegalStateException") }, "got $lines")
        assertTrue(lines.any { it.contains("scard=0x80100069") }, "got $lines")
        assertTrue(lines.any { it.contains("scard=0x8010002E SCARD_E_NO_READERS_AVAILABLE") }, "got $lines")
        assertTrue(lines.any { it.contains("UnsatisfiedLinkError") }, "got $lines")
        assertTrue(lines.any { it.contains("NO DEVICES on any transport") }, "got $lines")
        // What goes: reader names, the user name and the path of the failed native load.
        val leaked = listOf("ACME", "alice", "hidapi-secret", "unplugged")
        assertTrue(captured.none { line -> leaked.any { line.contains(it) } }, "a private value leaked: $captured")
        assertNothingScrubbed(lines)
    }

    @Test
    fun `describeFailure keeps the class chain and the SCARD vocabulary, never the message`() {
        val failure = IOException(
            "transmit failed on ACME-Secret-Reader: SCARD_E_NOT_TRANSACTED (0x80100027)",
            IllegalStateException("inner 0x80100069 SCARD_W_REMOVED_CARD and 0x80100027 again"),
        )

        val described = describeFailure(failure)

        assertEquals(
            "IOException<-IllegalStateException, scard=0x80100027 SCARD_E_NOT_TRANSACTED 0x80100069 SCARD_W_REMOVED_CARD",
            described,
        )
        assertFalse(described.contains("ACME"))
        assertFalse(described.contains("transmit failed"))
    }

    @Test
    fun `scardStatuses finds every code in the chain, ignores numbers out of the SCARD range`() {
        val failure = RuntimeException(
            "a 0x80100001 and 0xDEADBEEF and a long run 0x123456789ABCDEF0 then 0x8010002E",
            IOException("cause with 0x80100001 again"),
        )

        assertEquals(listOf("0x80100001", "0x8010002E"), scardStatuses(failure))
        assertEquals(emptyList(), scardStatuses(IllegalStateException("no status here")))
        assertEquals("IllegalStateException", describeFailure(IllegalStateException("no status here")))
    }

    @Test
    fun `describeCtapInfo reports counts and one known flag instead of the lists the key sent`() {
        val described = describeCtapInfo(
            versions = listOf("U2F_V2", "FIDO_2_0", "FIDO_2_1", "vendor-private-version"),
            transports = listOf("usb", "nfc"),
        )

        assertEquals("versionCount=4 transportCount=2 fido21=true", described)
        assertEquals("versionCount=0 transportCount=0 fido21=false", describeCtapInfo(null, null))
        assertFalse(described.contains("vendor"))
    }
}
