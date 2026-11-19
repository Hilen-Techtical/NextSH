// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import android.content.Context
import fr.techtical.nextsh.R
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.TunnelConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf

/**
 * A `Context` that says the French texts of the messages [ReconnectTunnelsUseCase] makes, so that
 * a test reads what the user would. The argument order is the one of the resources. A text it does
 * not know comes back as `res:<id>`, which no assertion expects.
 */
internal fun reconnectTestContext(): Context = mockk {
    every { getString(any<Int>()) } answers { text(firstArg<Int>(), emptyList()) }
    every { getString(any<Int>(), *anyVararg<Any>()) } answers {
        // mockk hands the elements of a vararg either flat or as one array: both are read.
        val formatArgs = args.drop(1).flatMap { if (it is Array<*>) it.toList() else listOf(it) }
        text(firstArg<Int>(), formatArgs)
    }
}

private fun text(id: Int, formatArgs: List<Any?>): String = when (id) {
    R.string.tunnel_error_reconnect_failed_reason ->
        "Reconnexion échouée après ${formatArgs[0]} tentatives : ${formatArgs[1]}"
    R.string.tunnel_error_reconnect_failed -> "Reconnexion échouée après ${formatArgs[0]} tentatives"
    R.string.tunnel_error_reconnect_failed_internal -> "Reconnexion échouée"
    R.string.tunnel_error_session_lost -> "SSH connection lost (translated)"
    R.string.tunnel_error_quick_cuts -> "Le tunnel se coupe dès qu'il est rétabli. Vérifiez le serveur, puis relancez-le."
    else -> "res:$id"
}

/**
 * The settings of the app with the "Reconnexion automatique" option at the value [autoReconnect]
 * reads when the use case asks, so that a test can flip it between two steps.
 */
internal fun reconnectTestSettings(autoReconnect: () -> Boolean = { true }): SettingsDataStore = mockk {
    every { settingsFlow } answers { flowOf(SettingsDataStore.Settings(autoReconnect = autoReconnect())) }
}

/** The tunnel [cfg] as the database gives it, on a password host whose own option is [autoReconnect]. */
internal fun liveOf(
    cfg: TunnelConfig,
    autoReconnect: Boolean = true,
    hostname: String = "example.org",
): LiveTunnel = LiveTunnel(
    tunnel = cfg,
    host = Host(
        id = cfg.hostId,
        label = "host-${cfg.hostId}",
        hostname = hostname,
        username = "root",
        authType = AuthType.PASSWORD,
        credentialId = "c1",
        autoReconnect = autoReconnect,
    ),
)
