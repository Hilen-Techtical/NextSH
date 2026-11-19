// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.shortcut

import android.content.Intent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import timber.log.Timber
import java.security.SecureRandom

/**
 * What the exported activity takes from an incoming intent as a launcher shortcut: only an intent
 * that carries the token of this install is one, the extras are always taken out of the intent, and
 * a redelivered intent (restored activity, relaunch from the recents list) is never acted on.
 */
class ShortcutRequestTest {

    private val tokens = ShortcutTokenStore(FakeSharedPreferences(), SecureRandom())
    private val token = tokens.token()

    private fun read(fake: FakeIntent, restored: Boolean = false) = readShortcutRequest(fake.intent, tokens, restored)

    /** The extras a shortcut is published with, as a plain map. */
    private fun hostExtras(hostId: String = "h1", withToken: String? = token) = mutableMapOf(
        ShortcutUpdater.EXTRA_SHORTCUT_ACTION to ShortcutUpdater.ACTION_CONNECT_HOST,
        ShortcutUpdater.EXTRA_HOST_ID to hostId,
    ).also { map -> withToken?.let { map[ShortcutUpdater.EXTRA_SHORTCUT_TOKEN] = it } }

    private fun tunnelExtras(tunnelId: String = "t1", withToken: String? = token) = mutableMapOf(
        ShortcutUpdater.EXTRA_SHORTCUT_ACTION to ShortcutUpdater.ACTION_START_TUNNEL,
        ShortcutUpdater.EXTRA_TUNNEL_ID to tunnelId,
    ).also { map -> withToken?.let { map[ShortcutUpdater.EXTRA_SHORTCUT_TOKEN] = it } }

    // ── Accepted ──────────────────────────────────────────────────────────────

    @Test
    fun `a host shortcut with the token of the install is a request to connect`() {
        assertEquals(ShortcutRequest.ConnectHost("h1"), read(FakeIntent(hostExtras("h1"))))
    }

    @Test
    fun `a tunnel shortcut with the token of the install is a request to start the tunnel`() {
        assertEquals(ShortcutRequest.StartTunnel("t1"), read(FakeIntent(tunnelExtras("t1"))))
    }

    @Test
    fun `the intent a shortcut is published with is accepted`() {
        val host = FakeIntent()
        ShortcutUpdater.hostIntent(host.intent, "h9", token)
        val tunnel = FakeIntent()
        ShortcutUpdater.tunnelIntent(tunnel.intent, "t9", token)

        assertEquals(ShortcutRequest.ConnectHost("h9"), read(host))
        assertEquals(ShortcutRequest.StartTunnel("t9"), read(tunnel))
    }

    // ── Refused ───────────────────────────────────────────────────────────────

    @Test
    fun `the extras of a shortcut without a token are ignored, as a forged intent would be`() {
        assertNull(read(FakeIntent(hostExtras(withToken = null))))
        assertNull(read(FakeIntent(tunnelExtras(withToken = null))))
    }

    @Test
    fun `a wrong, empty or old token is ignored`() {
        for (bad in listOf("forged", "", "A".repeat(43), token.dropLast(1), token + "A")) {
            assertNull(read(FakeIntent(hostExtras(withToken = bad))), "host, token '$bad'")
            assertNull(read(FakeIntent(tunnelExtras(withToken = bad))), "tunnel, token '$bad'")
        }
        val otherInstall = ShortcutTokenStore(FakeSharedPreferences(), SecureRandom()).token()
        assertNull(read(FakeIntent(hostExtras(withToken = otherInstall))), "the token of another install")
    }

    @Test
    fun `an extra of the wrong type is not text, the token as a number is ignored like a missing one`() {
        for (key in listOf(
            ShortcutUpdater.EXTRA_SHORTCUT_TOKEN,
            ShortcutUpdater.EXTRA_HOST_ID,
            ShortcutUpdater.EXTRA_SHORTCUT_ACTION,
        )) {
            val extras = HashMap<String, Any>(hostExtras())
            extras[key] = 42
            val fake = FakeIntent(extras)

            assertNull(read(fake), "$key as a number")
            assertFalse(fake.extras.containsKey(key), "and it is taken out of the intent")
        }
    }

    @Test
    fun `a good token does not make an unknown action or a missing target a request`() {
        val unknown = hostExtras().also { it[ShortcutUpdater.EXTRA_SHORTCUT_ACTION] = "wipe_vault" }
        val noHost = hostExtras().also { it.remove(ShortcutUpdater.EXTRA_HOST_ID) }
        val blankHost = hostExtras(hostId = "  ")
        val noTunnel = tunnelExtras().also { it.remove(ShortcutUpdater.EXTRA_TUNNEL_ID) }
        val hostActionWithTunnelId = tunnelExtras().also {
            it[ShortcutUpdater.EXTRA_SHORTCUT_ACTION] = ShortcutUpdater.ACTION_CONNECT_HOST
        }

        for (extras in listOf(unknown, noHost, blankHost, noTunnel, hostActionWithTunnelId)) {
            assertNull(read(FakeIntent(extras)), "$extras")
        }
    }

    @Test
    fun `a plain launch is not a shortcut, with or without a token`() {
        assertNull(read(FakeIntent()))
        assertNull(read(FakeIntent(mutableMapOf(ShortcutUpdater.EXTRA_SHORTCUT_TOKEN to token))))
        assertNull(readShortcutRequest(null, tokens, restored = false))
    }

    // ── The intent after reading ──────────────────────────────────────────────

    @Test
    fun `the shortcut extras and the token are taken out of the intent whatever the outcome`() {
        val shortcutExtras = setOf(
            ShortcutUpdater.EXTRA_SHORTCUT_ACTION,
            ShortcutUpdater.EXTRA_HOST_ID,
            ShortcutUpdater.EXTRA_TUNNEL_ID,
            ShortcutUpdater.EXTRA_SHORTCUT_TOKEN,
        )
        val cases = mapOf(
            "accepted" to FakeIntent(hostExtras()),
            "forged" to FakeIntent(hostExtras(withToken = "forged")),
            "no token" to FakeIntent(tunnelExtras(withToken = null)),
            "restored" to FakeIntent(hostExtras()),
        )

        for ((name, fake) in cases) {
            read(fake, restored = name == "restored")

            assertTrue(fake.extras.keys.none { it in shortcutExtras }, "$name: ${fake.extras.keys}")
        }
    }

    @Test
    fun `other extras of the intent are left alone`() {
        val fake = FakeIntent(hostExtras().also { it["something_else"] = "kept" })

        read(fake)

        assertEquals(mapOf("something_else" to "kept"), fake.extras)
    }

    @Test
    fun `reading the same intent again gives nothing, a rotation does not connect twice`() {
        val fake = FakeIntent(hostExtras())

        assertEquals(ShortcutRequest.ConnectHost("h1"), read(fake))
        assertNull(read(fake))
    }

    // ── Redelivery ────────────────────────────────────────────────────────────

    @Test
    fun `a restored activity does not act on the intent the system hands back, token or not`() {
        // After the death of the process the system redelivers the original intent.
        assertNull(read(FakeIntent(hostExtras()), restored = true))
        assertNull(read(FakeIntent(tunnelExtras()), restored = true))
    }

    @Test
    fun `an intent relaunched from the recents list does not act`() {
        val fromHistory = FakeIntent(hostExtras(), flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY or Intent.FLAG_ACTIVITY_NEW_TASK)

        assertNull(read(fromHistory))
        assertFalse(fromHistory.extras.containsKey(ShortcutUpdater.EXTRA_SHORTCUT_TOKEN))
    }

    @Test
    fun `an intent that is not from the history acts, whatever other flags it has`() {
        assertEquals(
            ShortcutRequest.ConnectHost("h1"),
            read(FakeIntent(hostExtras(), flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)),
        )
    }

    // ── Open the terminal (the notification of the terminal service) ──────────

    /** The extras of the notification of the terminal service: an action and the token, no target. */
    private fun terminalExtras(withToken: String? = token) = mutableMapOf(
        ShortcutUpdater.EXTRA_SHORTCUT_ACTION to ShortcutUpdater.ACTION_OPEN_TERMINAL,
    ).also { map -> withToken?.let { map[ShortcutUpdater.EXTRA_SHORTCUT_TOKEN] = it } }

    @Test
    fun `an open terminal intent with the token of the install is a request to open the terminal`() {
        assertEquals(ShortcutRequest.OpenTerminal, read(FakeIntent(terminalExtras())))
    }

    @Test
    fun `the intent the notification of the terminal service is published with is accepted`() {
        val notification = FakeIntent()
        ShortcutUpdater.openTerminalIntent(notification.intent, token)

        assertEquals(ShortcutRequest.OpenTerminal, read(notification))
    }

    @Test
    fun `an open terminal intent without a token, or with a wrong one, is ignored like a forged intent`() {
        assertNull(read(FakeIntent(terminalExtras(withToken = null))))
        for (bad in listOf("forged", "", "A".repeat(43), token.dropLast(1), token + "A")) {
            assertNull(read(FakeIntent(terminalExtras(withToken = bad))), "token '$bad'")
        }
        val otherInstall = ShortcutTokenStore(FakeSharedPreferences(), SecureRandom()).token()
        assertNull(read(FakeIntent(terminalExtras(withToken = otherInstall))), "the token of another install")
    }

    @Test
    fun `an open terminal intent is not acted on when the activity is restored or relaunched from the recents list`() {
        assertNull(read(FakeIntent(terminalExtras()), restored = true))
        assertNull(read(FakeIntent(terminalExtras(), flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)))
    }

    @Test
    fun `the extras of an open terminal intent are taken out of the intent whatever the outcome`() {
        val shortcutExtras = setOf(
            ShortcutUpdater.EXTRA_SHORTCUT_ACTION,
            ShortcutUpdater.EXTRA_HOST_ID,
            ShortcutUpdater.EXTRA_TUNNEL_ID,
            ShortcutUpdater.EXTRA_SHORTCUT_TOKEN,
        )
        val cases = mapOf(
            "accepted" to FakeIntent(terminalExtras()),
            "forged" to FakeIntent(terminalExtras(withToken = "forged")),
            "no token" to FakeIntent(terminalExtras(withToken = null)),
            "restored" to FakeIntent(terminalExtras()),
            "from the recents list" to FakeIntent(terminalExtras(), flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY),
        )

        for ((name, fake) in cases) {
            read(fake, restored = name == "restored")

            assertTrue(fake.extras.keys.none { it in shortcutExtras }, "$name: ${fake.extras.keys}")
        }
    }

    @Test
    fun `an open terminal intent is read once, a rotation does not open the terminal again`() {
        val fake = FakeIntent(terminalExtras())

        assertEquals(ShortcutRequest.OpenTerminal, read(fake))
        assertNull(read(fake))
    }

    @Test
    fun `a host id or a tunnel id on an open terminal intent changes nothing, the request has no target`() {
        val extras = terminalExtras().also {
            it[ShortcutUpdater.EXTRA_HOST_ID] = "h1"
            it[ShortcutUpdater.EXTRA_TUNNEL_ID] = "t1"
        }

        assertEquals(ShortcutRequest.OpenTerminal, read(FakeIntent(extras)))
    }

    @Test
    fun `a refused open terminal intent is logged as a warning that does not carry the token`() {
        val logged = mutableListOf<Pair<Int, String>>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logged += priority to message
            }
        }
        Timber.plant(tree)
        try {
            read(FakeIntent(terminalExtras(withToken = "forged-secret-token")))
            read(FakeIntent(terminalExtras(withToken = null)))
        } finally {
            Timber.uproot(tree)
        }

        assertEquals(2, logged.size)
        assertTrue(logged.all { (priority, _) -> priority == android.util.Log.WARN })
        for ((_, message) in logged) {
            assertFalse(message.contains("forged-secret-token") || message.contains(token), message)
        }
    }

    @Test
    fun `an accepted open terminal intent logs nothing and its request does not carry the token`() {
        val logged = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logged += message
            }
        }
        Timber.plant(tree)
        val request = try {
            read(FakeIntent(terminalExtras()))
        } finally {
            Timber.uproot(tree)
        }

        assertTrue(logged.isEmpty(), "$logged")
        assertFalse(request.toString().contains(token))
    }

    // ── Logs ──────────────────────────────────────────────────────────────────

    @Test
    fun `a refused shortcut is logged as a warning that carries neither the token nor the ids`() {
        val logged = mutableListOf<Pair<Int, String>>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logged += priority to message
            }
        }
        Timber.plant(tree)
        try {
            read(FakeIntent(hostExtras(hostId = "host-secret-id", withToken = "forged-secret-token")))
            read(FakeIntent(hostExtras(hostId = "host-secret-id", withToken = null)))
        } finally {
            Timber.uproot(tree)
        }

        assertEquals(2, logged.size)
        assertTrue(logged.all { (priority, _) -> priority == android.util.Log.WARN })
        for ((_, message) in logged) {
            assertFalse(message.contains("forged-secret-token") || message.contains(token), message)
            assertFalse(message.contains("host-secret-id"), message)
        }
    }

    @Test
    fun `an accepted shortcut and a plain launch log nothing`() {
        val logged = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logged += message
            }
        }
        Timber.plant(tree)
        try {
            read(FakeIntent(hostExtras()))
            read(FakeIntent())
        } finally {
            Timber.uproot(tree)
        }

        assertTrue(logged.isEmpty(), "$logged")
    }

    @Test
    fun `a request does not carry the token`() {
        val request = read(FakeIntent(hostExtras()))

        assertFalse(request.toString().contains(token))
    }
}
