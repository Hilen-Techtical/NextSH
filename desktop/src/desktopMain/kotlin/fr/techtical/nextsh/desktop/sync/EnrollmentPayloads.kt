// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import kotlinx.serialization.Serializable

@Serializable
data class ServerHello(
    val publicKey: String,
    val deviceName: String,
    val tlsCertFingerprint: String,
    val version: Int = 1,
)

@Serializable
data class ClientEnroll(
    val publicKey: String,
    val deviceId: String,
    val deviceName: String,
    val platform: String,
)

@Serializable
data class EnrollAck(
    val deviceId: String,
    val deviceName: String,
)
