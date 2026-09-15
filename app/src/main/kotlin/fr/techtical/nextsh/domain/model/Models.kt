// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

@file:Suppress("unused")
package fr.techtical.nextsh.domain.model

/**
 * Typealiases bridging the app package to the shared KMP domain models.
 * All domain types now live in :shared and are re-exported here so that
 * existing app code (ViewModels, DAOs, use cases) compiles without change.
 */

typealias Host               = fr.techtical.nextsh.shared.domain.model.Host
typealias AuthType           = fr.techtical.nextsh.shared.domain.model.AuthType
typealias Fido2Mode          = fr.techtical.nextsh.shared.domain.model.Fido2Mode

typealias SshKey             = fr.techtical.nextsh.shared.domain.model.SshKey
typealias SshKeyType         = fr.techtical.nextsh.shared.domain.model.SshKeyType

typealias TunnelConfig       = fr.techtical.nextsh.shared.domain.model.TunnelConfig
typealias TunnelType         = fr.techtical.nextsh.shared.domain.model.TunnelType

typealias SshSession         = fr.techtical.nextsh.shared.domain.model.SshSession
typealias SessionStatus      = fr.techtical.nextsh.shared.domain.model.SessionStatus

typealias TunnelState        = fr.techtical.nextsh.shared.domain.model.TunnelState
typealias TunnelStatus       = fr.techtical.nextsh.shared.domain.model.TunnelStatus

typealias SshResult<T>       = fr.techtical.nextsh.shared.domain.model.SshResult<T>
typealias SshErrorCode       = fr.techtical.nextsh.shared.domain.model.SshErrorCode

typealias Snippet            = fr.techtical.nextsh.shared.domain.model.Snippet

typealias CustomTerminalTheme = fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme

typealias SftpFile           = fr.techtical.nextsh.shared.domain.model.SftpFile
typealias SortOrder          = fr.techtical.nextsh.shared.domain.model.SortOrder

typealias TransferRequest    = fr.techtical.nextsh.shared.domain.model.TransferRequest
typealias TransferDirection  = fr.techtical.nextsh.shared.domain.model.TransferDirection
typealias TransferState      = fr.techtical.nextsh.shared.domain.model.TransferState

// SplitState types remain Android-only (not in :shared yet)
