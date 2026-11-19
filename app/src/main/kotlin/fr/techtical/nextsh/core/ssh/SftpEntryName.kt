// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

/** Longueur maximale d'un nom d'entrée, en octets UTF-8 : la limite d'un nom sur les systèmes de fichiers courants. */
private const val MAX_NAME_BYTES = 255

/**
 * Ce qui empêche un nom saisi (nouveau dossier, nouveau fichier, renommage) d'être un nom d'entrée.
 *
 * Le nom est refusé et dit comme tel, jamais corrigé : `a/b` ne devient pas `ab`. Sans Android ni
 * `Context`, pour que le domaine, le gestionnaire et les écrans partagent la même règle ; l'écran
 * traduit chaque valeur en texte.
 */
enum class SftpEntryNameProblem {
    /** `.` ou `..` : le dossier lui-même ou son parent, jamais une entrée. */
    RESERVED,

    /** Un `/` ou un `\` : le nom désignerait un chemin, pas une entrée. */
    SEPARATOR,

    /** Un caractère de contrôle (C0, DEL ou C1), invisible à l'écran. */
    CONTROL_CHARACTER,

    /**
     * Un caractère de format invisible (voir [isInvisibleFormat]) : il change l'affichage du nom sans
     * se voir, donc deux noms différents paraissent identiques et une extension peut être déguisée.
     *
     * Refusés, par `U+XXXX` (jamais écrits en brut dans le code) :
     * - contrôles bidirectionnels : U+061C, U+200E, U+200F, U+202A à U+202E, U+2066 à U+2069 ;
     * - invisibles sans chasse : U+200B, U+2060 à U+2064, U+206A à U+206F, U+FEFF, U+FFF9 à U+FFFB ;
     * - voyelles inhérentes khmères invisibles : U+17B4 et U+17B5 ;
     * - trait d'union conditionnel : U+00AD ;
     * - séparateurs de ligne et de paragraphe : U+2028, U+2029.
     *
     * Restent permis : U+200C (ZWNJ) et U+200D (ZWJ), dont le persan, les écritures indiennes et les
     * émojis composés ont besoin, U+180E, les sélecteurs de variante U+FE00 à U+FE0F et les étiquettes
     * des drapeaux. Une liste explicite, plutôt que toute la catégorie Cf : la règle ne dépend alors
     * ni de la version d'Unicode de l'appareil ni de la plateforme (Desktop a la même liste).
     */
    INVISIBLE_FORMAT,

    /** Plus de 255 octets en UTF-8, la limite d'un nom sur les systèmes de fichiers courants. */
    TOO_LONG,
}

/**
 * Le défaut d'un nom d'entrée, ou `null` s'il n'y en a pas à relever.
 *
 * `null` pour un nom vide aussi : tant que rien n'est tapé, il n'y a rien à reprocher (le bouton de
 * validation reste inactif de son côté, voir [isValidEntryName]). Les espaces en bordure ne sont pas
 * jugés ici non plus : l'écran les retire avant de valider, et [isValidEntryName] les refuse.
 *
 * Les contrôles sont ceux de [Char.isISOControl] (C0, DEL, C1) et les caractères de format ceux de
 * [isInvisibleFormat] : ensemble, le même que celui que [uploadEntryName] remplace par `_`. La longueur se compte en octets UTF-8, pas en caractères : c'est la limite du
 * système de fichiers du serveur, un nom de 128 `é` (256 octets) est trop long, un emoji compte pour
 * 4 octets. [uploadEntryName] coupe à 255 caractères : pour un nom multi-octets, ce qu'il rend peut
 * donc dépasser cette limite (le serveur le refuserait), ce que la règle d'ici ne tolère pas.
 */
fun sftpEntryNameProblem(name: String): SftpEntryNameProblem? = when {
    name.isEmpty() -> null
    name == "." || name == ".." -> SftpEntryNameProblem.RESERVED
    name.any { it == '/' || it == '\\' } -> SftpEntryNameProblem.SEPARATOR
    name.any { it.isISOControl() } -> SftpEntryNameProblem.CONTROL_CHARACTER
    name.any { it.isInvisibleFormat() } -> SftpEntryNameProblem.INVISIBLE_FORMAT
    name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES -> SftpEntryNameProblem.TOO_LONG
    else -> null
}

/**
 * Vrai pour un caractère de format invisible qu'un nom ne peut pas contenir (liste et raisons dans la
 * KDoc de [SftpEntryNameProblem.INVISIBLE_FORMAT]). Tous sont dans le plan de base : un test par
 * `Char` suffit. Écrits en échappements, jamais en brut.
 */
internal fun Char.isInvisibleFormat(): Boolean =
    this == '\u00AD' || this == '\u061C' || this == '\u200B' || this == '\u200E' || this == '\u200F' ||
        this == '\u2028' || this == '\u2029' || this == '\uFEFF' ||
        this in '\u202A'..'\u202E' || this in '\u2060'..'\u206F' || this in '\uFFF9'..'\uFFFB' ||
        // Khmer inherent vowels U+17B4 and U+17B5: invisible, and no word needs them. By code, not raw.
        this.code in 0x17B4..0x17B5

/**
 * [name] où chaque caractère de contrôle ([Char.isISOControl]) et chaque caractère de format invisible
 * ([isInvisibleFormat]) devient [replacement]. Rend [name] lui-même s'il n'en contient aucun.
 */
internal fun replaceInvisibleCharacters(name: String, replacement: Char): String {
    fun Char.hidden() = isISOControl() || isInvisibleFormat()
    if (name.none { it.hidden() }) return name
    return buildString(name.length) { name.forEach { append(if (it.hidden()) replacement else it) } }
}

/**
 * Vrai si [name] est un nom d'entrée tel que le serveur le recevra : ni vide ni blanc, sans espace en
 * bordure, et sans défaut au sens de [sftpEntryNameProblem].
 *
 * Strict : un nom que [SftpManager.sanitizeFilename] changerait est refusé au lieu d'être corrigé en
 * silence. Tout nom accepté ici ressort donc inchangé de [SftpManager.sanitizeFilename] (qui coupe à
 * 255 caractères, et un nom de 255 octets au plus n'en compte pas davantage), et collé à un dossier
 * parent il ressort inchangé de [SftpManager.sanitizePath] : un `+`, un `%` ou un `%2F` restent des
 * caractères du nom.
 */
fun isValidEntryName(name: String): Boolean =
    name.isNotBlank() && name == name.trim() && sftpEntryNameProblem(name) == null
