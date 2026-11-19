// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

/**
 * Résultat d'un export : le fichier .nextsh chiffré et le nombre de snippets et de thèmes
 * personnalisés existants que l'import refuserait (texte invalide), donc qui ne pourraient pas être
 * restaurés. L'export n'est pas bloqué, l'UI le signale.
 *
 * equals, hashCode et toString n'exposent aucun contenu.
 */
class VaultExport(
    val bytes: ByteArray,
    val notRestorable: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is VaultExport && notRestorable == other.notRestorable && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + notRestorable

    override fun toString(): String = "VaultExport(size=${bytes.size}, notRestorable=$notRestorable)"
}
