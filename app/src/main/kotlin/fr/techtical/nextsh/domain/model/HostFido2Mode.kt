// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.model

/**
 * The FIDO2 mode of this host as a connection reads it. A FIDO2 host without a mode came from the
 * Desktop, which has no such notion: it is a security key host. One place for that default, so that
 * what a connection does and what is said about it beforehand (does it need the user?) cannot drift.
 */
fun Host.fido2ModeOrDefault(): Fido2Mode = fido2Mode ?: Fido2Mode.HARDWARE_KEY