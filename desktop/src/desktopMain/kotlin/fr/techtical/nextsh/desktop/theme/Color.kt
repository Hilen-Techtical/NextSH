// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.theme

import androidx.compose.ui.graphics.Color

// ── Backgrounds ──────────────────────────────────────────────────────────────
val NearBlack       = Color(0xFF0F0F0F)
val Surface         = Color(0xFF1A1A1A)
val SurfaceVariant  = Color(0xFF242424)

// ── Accent primaire : Burgundy ────────────────────────────────────────────────
val Burgundy        = Color(0xFF8B1A2F)
val BurgundyLight   = Color(0xFFAB2438)
val BurgundyDark    = Color(0xFF5C0F1E)

// ── Accent secondaire : Gold ──────────────────────────────────────────────────
val Gold            = Color(0xFFC9A84C)
val GoldLight       = Color(0xFFE8C96A)
val GoldMuted       = Color(0xFF8A6F2E)

// ── Texte ─────────────────────────────────────────────────────────────────────
val TextPrimary     = Color(0xFFE8E8E8)
val TextSecondary   = Color(0xFFAAAAAA)
val TextDisabled    = Color(0xFF6B6B6B)
val White           = Color(0xFFFFFFFF)

// ── États ─────────────────────────────────────────────────────────────────────
val ErrorRed        = Color(0xFFCF6679)
val SuccessGreen    = Color(0xFF4CAF7A)
val WarningAmber    = Color(0xFFE8A838)
val InfoBlue        = Color(0xFF5B9BD5)
val BioViolet       = Color(0xFF9B7FD4)

// ── Bordures / dividers ───────────────────────────────────────────────────────
val Border1         = Color(0xFF1F1F1F)
val Border2         = Color(0xFF2A2A2A)

// ── Backgrounds chrome (maquette Claude Design) ──────────────────────────────
// Nuances spécifiques à la sidebar et la status bar dans la maquette,
// distinctes de NearBlack (zone main) et Surface (cards).
val SidebarBg       = Color(0xFF121212)
val StatusBarBg     = Color(0xFF131313)

// ── Tokens sémantiques (alias canoniques pour la refonte UI) ──────────────────
// fg-* / bg-* / accent-* matchent la maquette Claude Design ; les noms RGB
// concrets (Burgundy, Gold, etc.) restent disponibles pour la rétro-compat.
object SemanticColors {
    val Fg1     = TextPrimary
    val Fg2     = TextSecondary
    val Fg3     = TextDisabled
    val Bg1     = NearBlack
    val Bg2     = Surface
    val Bg3     = SurfaceVariant
    val Accent1 = Burgundy
    val Accent2 = Gold
}
