// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.theme

import fr.techtical.nextsh.domain.model.CustomTerminalTheme

enum class TerminalThemeId(val displayName: String) {
    TECHTICAL_DARK("Techtical Dark"),
    TECHTICAL_LIGHT("Techtical Light"),
    SOLARIZED_DARK("Solarized Dark"),
    DRACULA("Dracula"),
    NORD("Nord"),
    MONOKAI("Monokai"),
    GRUVBOX_DARK("Gruvbox Dark"),
    SOLARIZED_LIGHT("Solarized Light"),
    HIGH_CONTRAST("High Contrast"),
}

data class TerminalThemePalette(
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    val selectionBg: Int,
    val ansi: IntArray,
) {
    init { require(ansi.size == 16) { "ANSI palette must have exactly 16 colors" } }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TerminalThemePalette) return false
        return background == other.background &&
            foreground == other.foreground &&
            cursor == other.cursor &&
            selectionBg == other.selectionBg &&
            ansi.contentEquals(other.ansi)
    }

    override fun hashCode(): Int {
        var result = background
        result = 31 * result + foreground
        result = 31 * result + cursor
        result = 31 * result + selectionBg
        result = 31 * result + ansi.contentHashCode()
        return result
    }
}

val TERMINAL_THEMES: Map<TerminalThemeId, TerminalThemePalette> = mapOf(
    TerminalThemeId.TECHTICAL_DARK to TerminalThemePalette(
        background = 0xFF0F0F0F.toInt(),
        foreground = 0xFFC9A84C.toInt(),
        cursor = 0xFFE8C96A.toInt(),
        selectionBg = 0x448B1A2F.toInt(),
        ansi = intArrayOf(
            0xFF1A1A1A.toInt(), 0xFFD44058.toInt(), 0xFFAB2438.toInt(), 0xFFE8A838.toInt(),
            0xFF5B9BD5.toInt(), 0xFFC45B84.toInt(), 0xFF3AADA8.toInt(), 0xFFE8E8E8.toInt(),
            0xFF242424.toInt(), 0xFFE8606E.toInt(), 0xFFCF3A4E.toInt(), 0xFFE8C96A.toInt(),
            0xFF7BB4E8.toInt(), 0xFFD47A9C.toInt(), 0xFF52C4BE.toInt(), 0xFFFFFFFF.toInt(),
        ),
    ),
    // Techtical Light : l'encre sur le fond beige, l'or et le bordeaux aux mêmes
    // emplacements que dans Techtical Dark. Le vert ANSI reste bordeaux, c'est la
    // signature du terminal Techtical ; le jaune normal est un or profond lisible
    // sur le beige (#7E6528, 4,9:1) ; l'or de la DA claire (#A8862E, 3:1) ne sert
    // qu'au curseur et au jaune vif. Contrastes vérifiés par TerminalThemesTest.
    TerminalThemeId.TECHTICAL_LIGHT to TerminalThemePalette(
        background = 0xFFF5F0E8.toInt(),
        foreground = 0xFF1A1A1A.toInt(),
        cursor = 0xFFA8862E.toInt(),
        selectionBg = 0x448B1A2F.toInt(),
        ansi = intArrayOf(
            0xFF1A1A1A.toInt(), 0xFFAB2438.toInt(), 0xFF8B1A2F.toInt(), 0xFF7E6528.toInt(),
            0xFF3A6B99.toInt(), 0xFF8E3A62.toInt(), 0xFF217A74.toInt(), 0xFF3A3A3A.toInt(),
            0xFF525252.toInt(), 0xFFD44058.toInt(), 0xFFCF3A4E.toInt(), 0xFFA8862E.toInt(),
            0xFF4A82B5.toInt(), 0xFFB24E7E.toInt(), 0xFF2E9A93.toInt(), 0xFF0F0F0F.toInt(),
        ),
    ),
    TerminalThemeId.SOLARIZED_DARK to TerminalThemePalette(
        background = 0xFF002B36.toInt(),
        foreground = 0xFF839496.toInt(),
        cursor = 0xFF93A1A1.toInt(),
        selectionBg = 0x44268BD2.toInt(),
        ansi = intArrayOf(
            0xFF073642.toInt(), 0xFFDC322F.toInt(), 0xFF859900.toInt(), 0xFFB58900.toInt(),
            0xFF268BD2.toInt(), 0xFFD33682.toInt(), 0xFF2AA198.toInt(), 0xFFEEE8D5.toInt(),
            0xFF002B36.toInt(), 0xFFCB4B16.toInt(), 0xFF586E75.toInt(), 0xFF657B83.toInt(),
            0xFF839496.toInt(), 0xFF6C71C4.toInt(), 0xFF93A1A1.toInt(), 0xFFFDF6E3.toInt(),
        ),
    ),
    TerminalThemeId.DRACULA to TerminalThemePalette(
        background = 0xFF282A36.toInt(),
        foreground = 0xFFF8F8F2.toInt(),
        cursor = 0xFFF8F8F2.toInt(),
        selectionBg = 0x4444475A.toInt(),
        ansi = intArrayOf(
            0xFF21222C.toInt(), 0xFFFF5555.toInt(), 0xFF50FA7B.toInt(), 0xFFF1FA8C.toInt(),
            0xFF6272A4.toInt(), 0xFFFF79C6.toInt(), 0xFF8BE9FD.toInt(), 0xFFF8F8F2.toInt(),
            0xFF6272A4.toInt(), 0xFFFF6E6E.toInt(), 0xFF69FF94.toInt(), 0xFFFFFFA5.toInt(),
            0xFFD6ACFF.toInt(), 0xFFFF92DF.toInt(), 0xFFA4FFFF.toInt(), 0xFFFFFFFF.toInt(),
        ),
    ),
    TerminalThemeId.NORD to TerminalThemePalette(
        background = 0xFF2E3440.toInt(),
        foreground = 0xFFD8DEE9.toInt(),
        cursor = 0xFFD8DEE9.toInt(),
        selectionBg = 0x443B4252.toInt(),
        ansi = intArrayOf(
            0xFF3B4252.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
            0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF88C0D0.toInt(), 0xFFE5E9F0.toInt(),
            0xFF4C566A.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
            0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF8FBCBB.toInt(), 0xFFECEFF4.toInt(),
        ),
    ),
    TerminalThemeId.MONOKAI to TerminalThemePalette(
        background = 0xFF272822.toInt(),
        foreground = 0xFFF8F8F2.toInt(),
        cursor = 0xFFF8F8F0.toInt(),
        selectionBg = 0x4449483E.toInt(),
        ansi = intArrayOf(
            0xFF272822.toInt(), 0xFFF92672.toInt(), 0xFFA6E22E.toInt(), 0xFFF4BF75.toInt(),
            0xFF66D9EF.toInt(), 0xFFAE81FF.toInt(), 0xFFA1EFE4.toInt(), 0xFFF8F8F2.toInt(),
            0xFF75715E.toInt(), 0xFFF92672.toInt(), 0xFFA6E22E.toInt(), 0xFFF4BF75.toInt(),
            0xFF66D9EF.toInt(), 0xFFAE81FF.toInt(), 0xFFA1EFE4.toInt(), 0xFFF9F8F5.toInt(),
        ),
    ),
    TerminalThemeId.GRUVBOX_DARK to TerminalThemePalette(
        background = 0xFF282828.toInt(),
        foreground = 0xFFEBDBB2.toInt(),
        cursor = 0xFFEBDBB2.toInt(),
        selectionBg = 0x443C3836.toInt(),
        ansi = intArrayOf(
            0xFF282828.toInt(), 0xFFCC241D.toInt(), 0xFF98971A.toInt(), 0xFFD79921.toInt(),
            0xFF458588.toInt(), 0xFFB16286.toInt(), 0xFF689D6A.toInt(), 0xFFA89984.toInt(),
            0xFF928374.toInt(), 0xFFFB4934.toInt(), 0xFFB8BB26.toInt(), 0xFFFABD2F.toInt(),
            0xFF83A598.toInt(), 0xFFD3869B.toInt(), 0xFF8EC07C.toInt(), 0xFFEBDBB2.toInt(),
        ),
    ),
    TerminalThemeId.SOLARIZED_LIGHT to TerminalThemePalette(
        background = 0xFFFDF6E3.toInt(),
        foreground = 0xFF657B83.toInt(),
        cursor = 0xFF586E75.toInt(),
        selectionBg = 0x44268BD2.toInt(),
        ansi = intArrayOf(
            0xFFEEE8D5.toInt(), 0xFFDC322F.toInt(), 0xFF859900.toInt(), 0xFFB58900.toInt(),
            0xFF268BD2.toInt(), 0xFFD33682.toInt(), 0xFF2AA198.toInt(), 0xFF073642.toInt(),
            0xFFFDF6E3.toInt(), 0xFFCB4B16.toInt(), 0xFF586E75.toInt(), 0xFF657B83.toInt(),
            0xFF839496.toInt(), 0xFF6C71C4.toInt(), 0xFF93A1A1.toInt(), 0xFF002B36.toInt(),
        ),
    ),
    TerminalThemeId.HIGH_CONTRAST to TerminalThemePalette(
        background = 0xFF000000.toInt(),
        foreground = 0xFFFFFFFF.toInt(),
        cursor = 0xFF00FF00.toInt(),
        selectionBg = 0x660066FF.toInt(),
        ansi = intArrayOf(
            0xFF000000.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
            0xFF0066FF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt(),
            0xFF666666.toInt(), 0xFFFF6666.toInt(), 0xFF66FF66.toInt(), 0xFFFFFF66.toInt(),
            0xFF6699FF.toInt(), 0xFFFF66FF.toInt(), 0xFF66FFFF.toInt(), 0xFFFFFFFF.toInt(),
        ),
    ),
)

fun resolveTheme(themeName: String): TerminalThemeId =
    try { TerminalThemeId.valueOf(themeName) } catch (_: IllegalArgumentException) { TerminalThemeId.TECHTICAL_DARK }

/** Converts a synced [CustomTerminalTheme] into a render-ready [TerminalThemePalette]. */
fun CustomTerminalTheme.toPalette(): TerminalThemePalette = TerminalThemePalette(
    background = background,
    foreground = foreground,
    cursor = cursor,
    selectionBg = selectionBg,
    ansi = ansi.toIntArray(),
)

/**
 * Resolves a [Host.terminalTheme] value to a concrete palette (decision 2):
 *  1. if [themeName] matches a [TerminalThemeId] enum name → its preset palette,
 *  2. else if it matches a custom theme UUID in [customThemes] → that palette,
 *  3. else fall back to the default preset (TECHTICAL_DARK).
 *
 * UUIDs never collide with enum names, so this is unambiguous and backward-compatible.
 */
fun resolveThemePalette(
    themeName: String,
    customThemes: List<CustomTerminalTheme>,
): TerminalThemePalette {
    val preset = TerminalThemeId.entries.firstOrNull { it.name == themeName }
    if (preset != null) return TERMINAL_THEMES.getValue(preset)
    val custom = customThemes.firstOrNull { it.id == themeName }
    if (custom != null) return custom.toPalette()
    return TERMINAL_THEMES.getValue(TerminalThemeId.TECHTICAL_DARK)
}
