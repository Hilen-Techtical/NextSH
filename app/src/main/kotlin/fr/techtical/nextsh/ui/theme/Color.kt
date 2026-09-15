// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.theme

import androidx.compose.ui.graphics.Color

// ── Backgrounds ──────────────────────────────────────────────────────────────
val NearBlack       = Color(0xFF0F0F0F)  // background principal
val Surface         = Color(0xFF1A1A1A)  // cards, panels, bottom bar
val SurfaceVariant  = Color(0xFF242424)  // inputs, modales, dividers

// ── Borders (parité Desktop refondu Phase 2) ─────────────────────────────────
val Border1         = Color(0xFF1F1F1F)  // séparation cards / sections
val Border2         = Color(0xFF2A2A2A)  // boutons secondaires, hover

// ── Accent primaire : Burgundy ────────────────────────────────────────────────
val Burgundy        = Color(0xFF8B1A2F)  // boutons, FAB, selected
val BurgundyLight   = Color(0xFFAB2438)  // hover / pressed
val BurgundyDark    = Color(0xFF5C0F1E)  // container

// ── Accent secondaire : Gold ──────────────────────────────────────────────────
val Gold            = Color(0xFFC9A84C)  // highlights, badges, icons actifs
val GoldLight       = Color(0xFFE8C96A)  // texte sur container sombre
val GoldMuted       = Color(0xFF8A6F2E)  // outline, désactivé, subtil

// ── Texte ─────────────────────────────────────────────────────────────────────
val TextPrimary     = Color(0xFFE8E8E8)  // texte principal
val TextSecondary   = Color(0xFFAAAAAA)  // labels, hints
val TextDisabled    = Color(0xFF6B6B6B)  // placeholders, inactif
val White           = Color(0xFFFFFFFF)

// ── États ─────────────────────────────────────────────────────────────────────
val ErrorRed        = Color(0xFFCF6679)  // erreur connexion
val SuccessGreen    = Color(0xFF4CAF7A)  // tunnel/session actif
val WarningAmber    = Color(0xFFE8A838)  // reconnexion, alerte
val InfoBlue        = Color(0xFF5B9BD5)  // information neutre
val BioViolet       = Color(0xFF9D7FE8)  // accent badge BIO (clés biométriques Android)
