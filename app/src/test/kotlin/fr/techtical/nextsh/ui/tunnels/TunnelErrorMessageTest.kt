// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.tunnels

import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.ssh.TUNNEL_AUTH_REQUIRED_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_CONNECT_UNEXPECTED_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_HOST_NOT_FOUND_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_LISTENER_STOPPED_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_LOCAL_PORT_INVALID_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_LOCAL_PORT_RESERVED_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_NETWORK_LOST_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_NO_ANSWER_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_NOT_FOUND_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_PORT_IN_USE_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_RECONNECT_INTERRUPTED_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_REMOTE_FORWARD_REFUSED_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_REMOTE_PORT_INVALID_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_SESSION_LOST_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_SESSION_NOT_FOUND_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_SOCKS_STOPPED_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_START_CANCELLED_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_START_FAILED_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_START_STOPPED_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_STOP_FAILED_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_VAULT_AUTH_EXPIRED_MESSAGE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The messages the tunnel manager makes up are shown in the language of the user, not raw. */
class TunnelErrorMessageTest {

    @Test
    fun `the start cancelled message is a string resource`() {
        assertEquals(R.string.tunnel_error_start_cancelled, tunnelErrorMessageRes(TUNNEL_START_CANCELLED_MESSAGE))
    }

    @Test
    fun `the reconnection interrupted message is a string resource`() {
        assertEquals(
            R.string.tunnel_error_reconnect_interrupted,
            tunnelErrorMessageRes(TUNNEL_RECONNECT_INTERRUPTED_MESSAGE),
        )
    }

    @Test
    fun `the authentication required message is a string resource`() {
        assertEquals(R.string.tunnel_error_auth_required, tunnelErrorMessageRes(TUNNEL_AUTH_REQUIRED_MESSAGE))
    }

    @Test
    fun `the cut reasons of the tunnel manager are string resources, not French literals with an enum name`() {
        assertEquals(R.string.tunnel_error_session_lost, tunnelErrorMessageRes(TUNNEL_SESSION_LOST_MESSAGE))
        assertEquals(R.string.tunnel_error_listener_stopped, tunnelErrorMessageRes(TUNNEL_LISTENER_STOPPED_MESSAGE))
        assertEquals(R.string.tunnel_error_socks_stopped, tunnelErrorMessageRes(TUNNEL_SOCKS_STOPPED_MESSAGE))
    }

    @Test
    fun `the reasons of a reconnection are string resources`() {
        assertEquals(R.string.tunnel_error_no_answer, tunnelErrorMessageRes(TUNNEL_NO_ANSWER_MESSAGE))
        assertEquals(R.string.tunnel_error_network_lost, tunnelErrorMessageRes(TUNNEL_NETWORK_LOST_MESSAGE))
        assertEquals("Le serveur ne répond plus", TUNNEL_NO_ANSWER_MESSAGE)
        assertEquals("Réseau indisponible", TUNNEL_NETWORK_LOST_MESSAGE)
    }

    @Test
    fun `the fixed message of a lost session carries no name of the SSH library`() {
        // It used to read "Connexion SSH perdue (CONNECTION_LOST)": an enum name for the user.
        assertEquals("Connexion SSH perdue", TUNNEL_SESSION_LOST_MESSAGE)
        assertNull(tunnelErrorMessageRes("Connexion SSH perdue (CONNECTION_LOST)"))
    }

    @Test
    fun `the reasons of a start or a stop that fails are string resources`() {
        assertEquals(R.string.tunnel_error_start_failed, tunnelErrorMessageRes(TUNNEL_START_FAILED_MESSAGE))
        assertEquals(R.string.tunnel_error_port_in_use, tunnelErrorMessageRes(TUNNEL_PORT_IN_USE_MESSAGE))
        assertEquals(R.string.tunnel_error_session_not_found, tunnelErrorMessageRes(TUNNEL_SESSION_NOT_FOUND_MESSAGE))
        assertEquals(R.string.tunnel_error_host_not_found, tunnelErrorMessageRes(TUNNEL_HOST_NOT_FOUND_MESSAGE))
        assertEquals(R.string.tunnel_error_connect_unexpected, tunnelErrorMessageRes(TUNNEL_CONNECT_UNEXPECTED_MESSAGE))
        assertEquals(R.string.tunnel_error_vault_auth_expired, tunnelErrorMessageRes(TUNNEL_VAULT_AUTH_EXPIRED_MESSAGE))
        assertEquals(R.string.tunnel_error_not_found, tunnelErrorMessageRes(TUNNEL_NOT_FOUND_MESSAGE))
        assertEquals(R.string.tunnel_error_stop_failed, tunnelErrorMessageRes(TUNNEL_STOP_FAILED_MESSAGE))
    }

    @Test
    fun `the ports a tunnel cannot use, the refusal of the server and a stopped start are string resources`() {
        assertEquals(R.string.tunnel_error_local_port_invalid, tunnelErrorMessageRes(TUNNEL_LOCAL_PORT_INVALID_MESSAGE))
        assertEquals(R.string.tunnel_error_remote_port_invalid, tunnelErrorMessageRes(TUNNEL_REMOTE_PORT_INVALID_MESSAGE))
        assertEquals(R.string.tunnel_error_local_port_reserved, tunnelErrorMessageRes(TUNNEL_LOCAL_PORT_RESERVED_MESSAGE))
        assertEquals(R.string.tunnel_error_remote_forward_refused, tunnelErrorMessageRes(TUNNEL_REMOTE_FORWARD_REFUSED_MESSAGE))
        assertEquals(R.string.tunnel_error_start_stopped, tunnelErrorMessageRes(TUNNEL_START_STOPPED_MESSAGE))
    }

    @Test
    fun `every fixed message has its own resource`() {
        val resources = listOf(
            tunnelErrorMessageRes(TUNNEL_START_CANCELLED_MESSAGE),
            tunnelErrorMessageRes(TUNNEL_RECONNECT_INTERRUPTED_MESSAGE),
            tunnelErrorMessageRes(TUNNEL_AUTH_REQUIRED_MESSAGE),
            tunnelErrorMessageRes(TUNNEL_SESSION_LOST_MESSAGE),
            tunnelErrorMessageRes(TUNNEL_LISTENER_STOPPED_MESSAGE),
            tunnelErrorMessageRes(TUNNEL_SOCKS_STOPPED_MESSAGE),
            tunnelErrorMessageRes(TUNNEL_START_FAILED_MESSAGE),
            tunnelErrorMessageRes(TUNNEL_PORT_IN_USE_MESSAGE),
            tunnelErrorMessageRes(TUNNEL_SESSION_NOT_FOUND_MESSAGE),
            tunnelErrorMessageRes(TUNNEL_HOST_NOT_FOUND_MESSAGE),
            tunnelErrorMessageRes(TUNNEL_CONNECT_UNEXPECTED_MESSAGE),
            tunnelErrorMessageRes(TUNNEL_VAULT_AUTH_EXPIRED_MESSAGE),
            tunnelErrorMessageRes(TUNNEL_NOT_FOUND_MESSAGE),
            tunnelErrorMessageRes(TUNNEL_STOP_FAILED_MESSAGE),
            tunnelErrorMessageRes(TUNNEL_LOCAL_PORT_INVALID_MESSAGE),
            tunnelErrorMessageRes(TUNNEL_REMOTE_PORT_INVALID_MESSAGE),
            tunnelErrorMessageRes(TUNNEL_LOCAL_PORT_RESERVED_MESSAGE),
            tunnelErrorMessageRes(TUNNEL_REMOTE_FORWARD_REFUSED_MESSAGE),
            tunnelErrorMessageRes(TUNNEL_START_STOPPED_MESSAGE),
            tunnelErrorMessageRes(TUNNEL_NO_ANSWER_MESSAGE),
            tunnelErrorMessageRes(TUNNEL_NETWORK_LOST_MESSAGE),
        )
        assertEquals(resources.size, resources.toSet().size, "$resources")
        assertEquals(false, resources.any { it == null })
    }

    @Test
    fun `any other message is shown as it is`() {
        // Messages built from a connection are already resolved with the Context: left alone.
        assertNull(tunnelErrorMessageRes("Authentication failed"))
        // The old literal, with its port: the manager does not store it any more, so it is not mapped.
        assertNull(tunnelErrorMessageRes("Port 8080 déjà utilisé localement"))
        assertNull(tunnelErrorMessageRes(""))
    }
}
