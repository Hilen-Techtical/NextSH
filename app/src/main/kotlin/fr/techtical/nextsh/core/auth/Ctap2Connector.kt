// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.auth

import com.yubico.yubikit.core.YubiKeyConnection
import com.yubico.yubikit.core.application.CommandException
import com.yubico.yubikit.core.fido.FidoConnection
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.fido.ctap.Ctap2Session
import java.io.Closeable
import java.io.IOException

/** An open CTAP2 session on a [SecurityKey]. Closing it closes the connection under it. */
internal class Ctap2Link(
    val session: Ctap2Session,
    private val connection: YubiKeyConnection,
) : Closeable {
    override fun close() = connection.close()
}

/** Opens a CTAP2 session on a security key, by the connection that suits its transport. */
internal fun interface Ctap2Connector {
    @Throws(IOException::class, CommandException::class)
    fun connect(key: SecurityKey): Ctap2Link
}

/**
 * The connection YubiKit has to open depends on the transport, and the wrong one fails:
 * - USB goes through [FidoConnection] (HID FIDO). A YubiKey plugged in this way does not expose
 *   its CCID applet, so a [SmartCardConnection] ends in "The application couldn't be selected".
 * - NFC goes through [SmartCardConnection] (ISO 7816 APDUs).
 *
 * [newSession] builds the [Ctap2Session] on the opened connection, which talks to the key at once
 * (it reads the key's info). It is a parameter so that tests need no key.
 */
internal class YubiKitCtap2Connector(
    private val newSession: (YubiKeyConnection) -> Ctap2Session = ::createCtap2Session,
) : Ctap2Connector {

    override fun connect(key: SecurityKey): Ctap2Link {
        val connection: YubiKeyConnection = when (key.transport) {
            KeyTransport.USB -> key.device.openConnection(FidoConnection::class.java)
            KeyTransport.NFC -> key.device.openConnection(SmartCardConnection::class.java)
        }
        return try {
            Ctap2Link(newSession(connection), connection)
        } catch (e: Exception) {
            // The session never came up: do not leave the connection open behind it.
            try {
                connection.close()
            } catch (_: IOException) {
                // The first failure is the one that matters.
            }
            throw e
        }
    }
}

private fun createCtap2Session(connection: YubiKeyConnection): Ctap2Session = when (connection) {
    is FidoConnection -> Ctap2Session(connection)
    is SmartCardConnection -> Ctap2Session(connection)
    else -> throw IllegalArgumentException("No CTAP2 session on this kind of connection")
}
