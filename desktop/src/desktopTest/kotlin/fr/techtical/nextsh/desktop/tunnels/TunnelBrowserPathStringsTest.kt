// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.tunnels

import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_browser_path_error_invalid_character
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_browser_path_error_invalid_percent
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_browser_path_error_too_long
import fr.techtical.nextsh.shared.domain.tunnel.TunnelBrowserPath
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The start path messages hold literal percent and dollar signs. Compose resources only substitute
 * the positional tokens (`%1$d`) of the arguments they are given, so a message read without arguments
 * must come back verbatim. The assertions do not depend on the machine language (FR or EN).
 */
class TunnelBrowserPathStringsTest {

    @Test
    fun `the invalid character message lists the allowed signs and the percent 20 example verbatim`() = runTest {
        val message = getString(Res.string.tunnel_config_browser_path_error_invalid_character)

        assertTrue(message.contains("- . _ ~ ! $ & ' ( ) * + , ; = : @ / ? # %"), message)
        assertTrue(message.contains("%20"), message)
        assertFalse(message.contains("&amp;"), message)
    }

    @Test
    fun `the invalid percent message keeps its percent 20 example verbatim`() = runTest {
        val message = getString(Res.string.tunnel_config_browser_path_error_invalid_percent)

        assertTrue(message.contains("(%)"), message)
        assertTrue(message.contains("%20"), message)
    }

    @Test
    fun `the too long message receives the maximum length and leaves no token behind`() = runTest {
        val message = getString(Res.string.tunnel_config_browser_path_error_too_long, TunnelBrowserPath.MAX_LENGTH)

        assertTrue(message.contains(TunnelBrowserPath.MAX_LENGTH.toString()), message)
        assertFalse(message.contains("%1"), message)
    }
}
