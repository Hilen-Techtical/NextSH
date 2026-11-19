// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

/**
 * Typealias bridging the app package to the shared KMP StartTunnelUseCase.
 * The shared use case depends on the SshTunnelManager, HostRepository and TunnelSessionProvider
 * shared interfaces (AndroidTunnelSessionProvider answers the last one).
 * Android managers implement those interfaces: see SharedUseCaseModule for Hilt wiring.
 */
typealias StartTunnelUseCase = fr.techtical.nextsh.shared.domain.usecase.StartTunnelUseCase
