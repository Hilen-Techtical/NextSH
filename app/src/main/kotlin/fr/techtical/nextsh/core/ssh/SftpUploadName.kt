// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

/** Longueur maximale d'un nom d'entrée, en caractères : la même que [SftpManager.sanitizeFilename]. */
private const val MAX_ENTRY_NAME_LENGTH = 255

/**
 * Séparateurs de chemin et caractères de contrôle, NUL et DEL compris.
 *
 * `\p{Cc}` couvre les contrôles C0 et C1 (U+0080 à U+009F), là où `\p{Cntrl}` de
 * [SftpManager.sanitizeFilename] est limité à l'ASCII en Java : c'est donc un
 * sur-ensemble, et `sanitizeFilename` laisse inchangé tout nom qui passe ici.
 */
private val UNSAFE_ENTRY_CHARS = Regex("[/\\\\\\p{Cc}]")

/**
 * Nom sûr sous lequel envoyer un fichier choisi dans le sélecteur Android.
 *
 * Le nom vient d'une autre application (le `DocumentsProvider`), donc d'une
 * source qu'il faut tenir pour hostile : collé tel quel au dossier distant, un
 * nom `../.ssh/authorized_keys` écrirait hors du dossier choisi. Les séparateurs
 * et les caractères de contrôle sont remplacés par `_` plutôt que refusés, pour
 * qu'un fichier dont le nom en contient (un fichier Drive nommé `a/b`) reste
 * envoyable. Les espaces en bordure sont retirées, le nom est coupé à 255
 * caractères, puis les espaces que la coupe aurait laissées en fin sont retirées
 * à leur tour.
 *
 * Le résultat ne contient aucun séparateur ni caractère de contrôle (C0 et C1),
 * n'est jamais `.` ni `..`, et [SftpManager.sanitizeFilename] le laisse inchangé.
 * La coupe est de 255 caractères, pas de 255 octets : un nom multi-octets trop
 * long sera refusé par le serveur, ce qui n'est pas une traversée.
 *
 * @return le nom à utiliser, ou `null` s'il ne reste rien d'exploitable (vide,
 *         blanc, `.` ou `..`) : l'appelant doit alors refuser l'envoi.
 */
fun uploadEntryName(raw: String): String? {
    val name = raw
        .replace(UNSAFE_ENTRY_CHARS, "_")
        .trim()
        .take(MAX_ENTRY_NAME_LENGTH)
        .trimEnd()
    return name.takeUnless { it.isEmpty() || it == "." || it == ".." }
}

/**
 * Nom à proposer pour un fichier choisi dans le sélecteur, avant assainissement.
 *
 * Le nom du fournisseur de documents (`OpenableColumns.DISPLAY_NAME`) passe en
 * premier s'il n'est pas blanc. À défaut, on garde la fin du dernier segment de
 * l'URI après le dernier `/` : ce segment contient souvent un `/`
 * (`primary:Download/foo.txt`, `raw:/storage/.../foo.txt`) que l'envoi
 * remplacerait par `_` et qui ferait perdre le vrai nom du fichier.
 *
 * Le résultat n'est pas encore sûr : l'envoi le contrôle avec [uploadEntryName].
 *
 * @return le nom retenu, ou `null` si ni l'un ni l'autre n'est exploitable (le
 *         dernier recours est un nom générique, choisi par l'appelant).
 */
fun pickedFileName(providerName: String?, lastPathSegment: String?): String? =
    providerName?.takeIf { it.isNotBlank() }
        ?: lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
