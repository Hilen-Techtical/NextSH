// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sync

import kotlinx.serialization.Serializable

/**
 * Mirror exact des DTOs Desktop (Wave 2.1, fr.techtical.nextsh.desktop.sync).
 * Les noms de champs JSON doivent rester identiques pour que la sérialisation
 * soit interopérable sans module partagé pour ces DTOs de protocole.
 */

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
