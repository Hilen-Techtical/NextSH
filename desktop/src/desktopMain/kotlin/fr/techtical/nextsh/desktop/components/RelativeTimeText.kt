// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.components

import androidx.compose.runtime.Composable
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.relative_date_language
import fr.techtical.nextsh.desktop.generated.resources.relative_just_now
import fr.techtical.nextsh.desktop.generated.resources.statusbar_relative_days_ago
import fr.techtical.nextsh.desktop.generated.resources.statusbar_relative_hours_ago
import fr.techtical.nextsh.desktop.generated.resources.statusbar_relative_minutes_ago
import fr.techtical.nextsh.shared.util.RelativeTime
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Le temps ecoule depuis [timestampMillis] en toutes lettres courtes ("il y a 5 min",
 * "5 min ago"), dans la langue de l'application, rafraichi tout seul (voir [rememberNowTicking]).
 *
 * Passe 7 jours ([RELATIVE_TIME_LIMIT_MS]), le texte devient une date avec l'annee ("5 mars 2027",
 * "Mar 5, 2027") : "il y a 212 j" ne se lit plus. "il y a 6 j" est le maximum relatif, et 7 jours
 * pile donnent deja une date. Moins d'une minute se dit "a l'instant" partout (`relative_just_now`).
 * Meme logique que `RelativeTimeText.kt` de l'application Android (parite, tests compris).
 *
 * Un ecran qui doit dire autre chose qu'un temps ecoule quand le texte est une date ("Detecte le
 * 5 mars 2027", pas "Detecte 5 mars 2027") passe par [relativeTimeLabel], qui dit laquelle des deux
 * formes est affichee.
 */
@Composable
fun relativeTimeText(timestampMillis: Long): String = relativeTimeLabel(timestampMillis).text

/** Comme [relativeTimeText], avec la forme prise par le texte (voir [RelativeTimeLabel.isDate]). */
@Composable
internal fun relativeTimeLabel(timestampMillis: Long): RelativeTimeLabel =
    relativeTimeLabel(timestampMillis, rememberNowTicking(timestampMillis))

/**
 * Le texte de [timestampMillis] vu a l'instant [nowMillis], sans rafraichissement propre : l'appelant
 * fournit l'heure (ecran qui la tient deja, ou ligne d'historique qui n'a pas besoin de tick).
 */
@Composable
internal fun relativeTimeLabel(timestampMillis: Long, nowMillis: Long): RelativeTimeLabel =
    when (val label = timeLabel(timestampMillis, nowMillis)) {
        is TimeLabel.Ago -> RelativeTimeLabel(
            text = when (label.bucket.unit) {
                RelativeTime.Unit.SECONDS -> stringResource(Res.string.relative_just_now)
                RelativeTime.Unit.MINUTES ->
                    stringResource(Res.string.statusbar_relative_minutes_ago, label.bucket.value)
                RelativeTime.Unit.HOURS ->
                    stringResource(Res.string.statusbar_relative_hours_ago, label.bucket.value)
                RelativeTime.Unit.DAYS ->
                    stringResource(Res.string.statusbar_relative_days_ago, label.bucket.value)
            },
            isDate = false,
        )
        is TimeLabel.OnDate -> RelativeTimeLabel(
            text = formatAbsoluteDate(label.epochMillis, appDateLocale(), ZoneId.systemDefault()),
            isDate = true,
        )
    }

/**
 * La date de [epochMillis] au format moyen de la langue des ressources affichees, avec l'annee
 * ("5 mars 2027", "Mar 5, 2027"), dans le fuseau de la machine.
 */
@Composable
internal fun absoluteDateText(epochMillis: Long): String =
    formatAbsoluteDate(epochMillis, appDateLocale(), ZoneId.systemDefault())

/** La locale d'une date : celle des ressources reellement chargees (voir [dateLocale]). */
@Composable
internal fun appDateLocale(): Locale = dateLocale(
    configLocale = Locale.getDefault(),
    resourceLanguage = stringResource(Res.string.relative_date_language),
)

/** Un texte de date ou de temps ecoule, et la forme qu'il a : [isDate] vrai pour une date absolue. */
internal data class RelativeTimeLabel(val text: String, val isDate: Boolean)

/** Au-dela de cet age (7 jours, dans les deux sens), on affiche une date et plus un temps ecoule. */
internal const val RELATIVE_TIME_LIMIT_MS = 7L * 86_400_000L

/** Ce qu'il faut afficher pour un instant : un temps ecoule, ou la date de cet instant. */
internal sealed interface TimeLabel {
    /** Un temps ecoule, decoupe par [RelativeTime.bucket] (de "0 s" a "6 j"). */
    data class Ago(val bucket: RelativeTime.Bucket) : TimeLabel

    /** La date absolue de [epochMillis], avec l'annee (voir [formatAbsoluteDate]). */
    data class OnDate(val epochMillis: Long) : TimeLabel
}

/**
 * Choisit entre un temps ecoule et une date pour [timestampMillis], vu a l'instant [nowMillis].
 *
 * - L'age `nowMillis - timestampMillis` est de [RELATIVE_TIME_LIMIT_MS] (7 jours) ou plus :
 *   [TimeLabel.OnDate]. 7 jours moins 1 ms donnent encore "il y a 6 j", 7 jours pile la date.
 * - Un instant dans le futur de 7 jours ou plus (horloge tres decalee, valeur corrompue) :
 *   [TimeLabel.OnDate] aussi, plutot qu'un "a l'instant" qui masquerait l'anomalie.
 * - Sinon [TimeLabel.Ago]. Un leger futur est ramene a 0 par [RelativeTime.bucket].
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
 * l'annee ("5 mars 2027" en francais, "Mar 5, 2027" en anglais). Le jour change a minuit du fuseau
 * de la machine, pas a minuit UTC.
 *
 * Le formateur est construit a chaque appel : une valeur globale garderait la langue de la
 * premiere composition apres un changement de langue.
 */
internal fun formatAbsoluteDate(epochMillis: Long, locale: Locale, zone: ZoneId): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(zone)
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

/**
 * La langue dans laquelle ecrire une date : celle des ressources affichees, qui n'est pas forcement
 * celle du systeme. Avec `LanguagePref.SYSTEM` sur un systeme en allemand, l'application s'affiche
 * en francais (repli sur `values/`) : une date en allemand jurerait a cote de "Detecte le ...".
 *
 * - [configLocale] est de la langue des ressources : gardee telle quelle, region comprise (en-GB,
 *   fr-CA), pour que la date suive les usages du pays.
 * - Sinon, la langue des ressources seule : `Locale.forLanguageTag(resourceLanguage)`.
 *
 * [resourceLanguage] est le code de la langue du fichier de ressources lu ("fr" ou "en", chaine
 * `relative_date_language`) : le choix des ressources est fait par compose-resources.
 */
internal fun dateLocale(configLocale: Locale, resourceLanguage: String): Locale =
    if (configLocale.language.equals(resourceLanguage, ignoreCase = true)) {
        configLocale
    } else {
        Locale.forLanguageTag(resourceLanguage)
    }

/**
 * La date et l'heure de [epochMillis] au format localise (date moyenne, heure courte), dans la
 * langue des ressources affichees ("5 mars 2027 14:30", "Mar 5, 2027, 2:30 PM"), fuseau de la machine.
 */
@Composable
internal fun dateTimeText(epochMillis: Long): String =
    formatLocalizedDateTime(epochMillis, appDateLocale(), ZoneId.systemDefault())

/** Comme [formatAbsoluteDate], avec l'heure : formateur construit a chaque appel. */
internal fun formatLocalizedDateTime(epochMillis: Long, locale: Locale, zone: ZoneId): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(zone)
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale))

/** L'heure de [epochMillis] au format court localise ("14:30", "2:30 PM"), langue des textes charges. */
@Composable
internal fun timeText(epochMillis: Long): String =
    formatLocalizedTime(epochMillis, appDateLocale(), ZoneId.systemDefault())

internal fun formatLocalizedTime(epochMillis: Long, locale: Locale, zone: ZoneId): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(zone)
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
