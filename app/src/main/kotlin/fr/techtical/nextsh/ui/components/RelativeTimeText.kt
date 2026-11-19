// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import fr.techtical.nextsh.R
import fr.techtical.nextsh.shared.util.RelativeTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Le temps écoulé depuis [timestampMillis] en toutes lettres courtes ("il y a 5 min",
 * "5 min ago"), dans la langue de l'application, rafraîchi tout seul (voir [rememberNowTicking]).
 *
 * Passé 7 jours ([RELATIVE_TIME_LIMIT_MS]), le texte devient une date avec l'année ("5 mars 2027",
 * "Mar 5, 2027") : "il y a 212 j" ne se lit plus. C'est le seuil que
 * `DateUtils.getRelativeTimeSpanString` appliquait en 1.0.0 sur les écrans des conflits et des
 * appareils enrôlés (les réglages, eux, écrivaient déjà "il y a N j" sans borne). "il y a 6 j" est
 * donc le maximum relatif, et 7 jours pile donnent déjà une date. Le détail du choix est dans
 * [timeLabel], le format dans [formatAbsoluteDate].
 *
 * `DateUtils` n'est pas utilisé : il suit la langue du système et pas celle des ressources de
 * l'app, d'où des phrases mélangées ("Détecté 5 minutes ago"), et `SyncScreensStringsTest` le
 * refuse dans les écrans de synchronisation et les réglages. La date est écrite dans la langue des
 * ressources affichées, pas dans celle du téléphone : voir [dateLocale].
 *
 * Un écran qui doit dire autre chose qu'un temps écoulé quand le texte est une date ("Détecté le
 * 5 mars 2027", pas "Détecté 5 mars 2027") passe par [relativeTimeLabel], qui dit laquelle des
 * deux formes est affichée.
 */
@Composable
fun relativeTimeText(timestampMillis: Long): String = relativeTimeLabel(timestampMillis).text

/**
 * Le texte de [relativeTimeText] et la forme qu'il a prise : [RelativeTimeLabel.isDate] est vrai
 * quand c'est une date absolue, faux quand c'est un temps écoulé.
 */
@Composable
internal fun relativeTimeLabel(timestampMillis: Long): RelativeTimeLabel {
    val now = rememberNowTicking(timestampMillis)
    return when (val label = timeLabel(timestampMillis, now)) {
        is TimeLabel.Ago -> RelativeTimeLabel(
            text = stringResource(relativeTimeAgoRes(label.bucket.unit), label.bucket.value),
            isDate = false,
        )
        is TimeLabel.OnDate -> {
            // La langue de la date suit celle des ressources affichées (voir dateLocale), relue à
            // chaque composition pour suivre un changement de langue.
            val locale = dateLocale(
                configLocale = LocalConfiguration.current.locales[0],
                resourceLanguage = stringResource(R.string.relative_date_language),
            )
            RelativeTimeLabel(
                text = formatAbsoluteDate(label.epochMillis, locale, ZoneId.systemDefault()),
                isDate = true,
            )
        }
    }
}

/** Un texte de date ou de temps écoulé, et la forme qu'il a : [isDate] vrai pour une date absolue. */
internal data class RelativeTimeLabel(val text: String, val isDate: Boolean)

/** Au-delà de cet âge (7 jours, dans les deux sens), on affiche une date et plus un temps écoulé. */
internal const val RELATIVE_TIME_LIMIT_MS = 7L * 86_400_000L

/** Ce qu'il faut afficher pour un instant : un temps écoulé, ou la date de cet instant. */
internal sealed interface TimeLabel {
    /** Un temps écoulé, découpé par [RelativeTime.bucket] (de "0 s" à "6 j"). */
    data class Ago(val bucket: RelativeTime.Bucket) : TimeLabel

    /** La date absolue de [epochMillis], avec l'année (voir [formatAbsoluteDate]). */
    data class OnDate(val epochMillis: Long) : TimeLabel
}

/**
 * Choisit entre un temps écoulé et une date pour [timestampMillis], vu à l'instant [nowMillis].
 *
 * - L'âge `nowMillis - timestampMillis` est de [RELATIVE_TIME_LIMIT_MS] (7 jours) ou plus : [TimeLabel.OnDate].
 *   7 jours moins 1 ms donnent encore "il y a 6 j", 7 jours pile donnent la date.
 * - Un instant dans le futur de 7 jours ou plus (horloge très décalée, valeur corrompue) :
 *   [TimeLabel.OnDate] aussi, plutôt qu'un "il y a 0 s" qui masquerait l'anomalie.
 * - Sinon [TimeLabel.Ago]. Un léger futur (horloges des deux appareils qui divergent de quelques
 *   heures) est ramené à 0 par [RelativeTime.bucket] : "il y a 0 s", comme avant le seuil.
 */
internal fun timeLabel(timestampMillis: Long, nowMillis: Long): TimeLabel {
    val ageMs = nowMillis - timestampMillis
    return if (ageMs >= RELATIVE_TIME_LIMIT_MS || ageMs <= -RELATIVE_TIME_LIMIT_MS) {
        TimeLabel.OnDate(timestampMillis)
    } else {
        TimeLabel.Ago(RelativeTime.bucket(ageMs))
    }
}

/**
 * La date de [epochMillis] dans le fuseau [zone], au format moyen de [locale] et toujours avec
 * l'année ("5 mars 2027" en français, "Mar 5, 2027" en anglais). Le jour change à minuit du fuseau
 * de l'appareil, pas à minuit UTC.
 *
 * Le formateur est construit à chaque appel et pas gardé dans une valeur globale : celui-ci
 * garderait la langue de la première composition après un changement de langue.
 */
internal fun formatAbsoluteDate(epochMillis: Long, locale: Locale, zone: ZoneId): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(zone)
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

/**
 * La langue dans laquelle écrire une date : celle des ressources affichées, qui n'est pas forcément
 * celle du téléphone. Un téléphone en allemand affiche l'app en français (repli sur `values/`, la
 * langue par défaut) : sa date en allemand ("05.03.2027") jurerait à côté de "Détecté le ...".
 *
 * - [configLocale], la langue de la configuration, est de la langue des ressources : elle est
 *   gardée telle quelle, avec sa région (en-GB, fr-CA), pour que la date suive les usages du pays.
 * - Sinon, la langue des ressources seule, sans région : `Locale.forLanguageTag(resourceLanguage)`.
 *
 * [resourceLanguage] est le code de la langue du fichier de ressources lu ("fr" ou "en", chaîne
 * `relative_date_language`) : le choix des ressources est fait par Android, pas à refaire ici.
 */
internal fun dateLocale(configLocale: Locale, resourceLanguage: String): Locale =
    if (configLocale.language.equals(resourceLanguage, ignoreCase = true)) {
        configLocale
    } else {
        Locale.forLanguageTag(resourceLanguage)
    }

/** La ressource qui dit une durée de cette [unit] ("il y a %1$d min"). */
@StringRes
internal fun relativeTimeAgoRes(unit: RelativeTime.Unit): Int = when (unit) {
    RelativeTime.Unit.SECONDS -> R.string.relative_seconds_ago
    RelativeTime.Unit.MINUTES -> R.string.relative_minutes_ago
    RelativeTime.Unit.HOURS -> R.string.relative_hours_ago
    RelativeTime.Unit.DAYS -> R.string.relative_days_ago
}
