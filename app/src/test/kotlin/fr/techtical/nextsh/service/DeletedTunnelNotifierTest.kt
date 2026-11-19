// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.service

import android.content.res.Resources
import fr.techtical.nextsh.R
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The words of the "tunnel stopped" notification come from resources (so they follow the device
 * language), and a notification is posted only when the system allows it. The posting itself
 * needs a device (NotificationCompat.Builder is a framework class), it is checked by hand.
 */
class DeletedTunnelNotifierTest {

    private lateinit var resources: Resources

    @BeforeEach
    fun setUp() {
        resources = mockk()
        every { resources.getQuantityString(R.plurals.tunnel_deleted_elsewhere_title, any()) } answers {
            if (secondArg<Int>() == 1) "Tunnel arrete" else "Tunnels arretes"
        }
    }

    // ── Message: one tunnel ─────────────────────────────────────────────────────────

    @Test
    fun `one tunnel is named in the text and gets the singular title`() {
        every { resources.getString(R.string.tunnel_deleted_elsewhere_text_single, "Web") } returns
            "\"Web\" a ete supprime sur un autre appareil."

        val message = DeletedTunnelMessage.of(resources, listOf("Web"))

        assertEquals("Tunnel arrete", message.title)
        assertEquals("\"Web\" a ete supprime sur un autre appareil.", message.text)
        assertTrue(message.lines.isEmpty(), "the text already names the only tunnel")
        assertNull(message.more)
    }

    // ── Message: several tunnels are grouped ────────────────────────────────────────

    @Test
    fun `several tunnels share one message with a count and the plural title`() {
        every { resources.getQuantityString(R.plurals.tunnel_deleted_elsewhere_text_count, 3, 3) } returns
            "3 tunnels ont ete supprimes sur un autre appareil."

        val message = DeletedTunnelMessage.of(resources, listOf("A", "B", "C"))

        assertEquals("Tunnels arretes", message.title)
        assertEquals("3 tunnels ont ete supprimes sur un autre appareil.", message.text)
        assertEquals(listOf("A", "B", "C"), message.lines)
        assertNull(message.more)
    }

    @Test
    fun `a long list is cut and the rest is summarized`() {
        val labels = (1..7).map { "T$it" }
        every { resources.getQuantityString(R.plurals.tunnel_deleted_elsewhere_text_count, 7, 7) } returns "7 tunnels"
        every { resources.getString(R.string.tunnel_notification_more, 2) } returns "+2 autre(s)"

        val message = DeletedTunnelMessage.of(resources, labels)

        assertEquals(DeletedTunnelMessage.MAX_LINES, message.lines.size)
        assertEquals(labels.take(DeletedTunnelMessage.MAX_LINES), message.lines)
        assertEquals("+2 autre(s)", message.more)
    }

    @Test
    fun `every text is read from resources, none is built in code`() {
        every { resources.getString(R.string.tunnel_deleted_elsewhere_text_single, "Web") } returns "texte"

        DeletedTunnelMessage.of(resources, listOf("Web"))

        verify(exactly = 1) { resources.getQuantityString(R.plurals.tunnel_deleted_elsewhere_title, 1) }
        verify(exactly = 1) { resources.getString(R.string.tunnel_deleted_elsewhere_text_single, "Web") }
    }

    // ── Permission ──────────────────────────────────────────────────────────────────

    @Test
    fun `from Android 13 a notification needs the permission`() {
        assertFalse(mayPostNotifications(sdkInt = 33) { false })
        assertFalse(mayPostNotifications(sdkInt = 36) { false })
        assertTrue(mayPostNotifications(sdkInt = 33) { true })
        assertTrue(mayPostNotifications(sdkInt = 36) { true })
    }

    @Test
    fun `before Android 13 there is no permission to ask, nor to check`() {
        var checked = false
        assertTrue(mayPostNotifications(sdkInt = 29) { checked = true; false })
        assertTrue(mayPostNotifications(sdkInt = 32) { checked = true; false })
        assertFalse(checked, "the permission does not exist below Android 13, it must not be queried")
    }

    // ── The wipe of the vault takes down the notifications that name tunnels ────

    @Test
    fun `the notifications of the batches are the ones of the tunnel channel from id 2000, not the foreground one`() {
        val channel = TUNNEL_CHANNEL_ID

        assertTrue(AndroidDeletedTunnelNotifier.isBatchNotification(2000, channel))
        assertTrue(AndroidDeletedTunnelNotifier.isBatchNotification(2057, channel))
        assertFalse(AndroidDeletedTunnelNotifier.isBatchNotification(1001, channel), "the foreground notification of the tunnels")
        assertFalse(AndroidDeletedTunnelNotifier.isBatchNotification(1999, channel))
        assertFalse(AndroidDeletedTunnelNotifier.isBatchNotification(2000, "another_channel"))
        assertFalse(AndroidDeletedTunnelNotifier.isBatchNotification(2000, null))
    }

    @Test
    fun `a notifier that does not override cancelAll does nothing, so a fake of the interface still compiles`() {
        val notifier = object : DeletedTunnelNotifier {
            override fun notifyStopped(labels: List<String>) = Unit
        }

        notifier.cancelAll()
    }
}
