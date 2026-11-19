// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.components

import fr.techtical.nextsh.shared.util.RelativeTime
import kotlin.test.Test
import kotlin.test.assertEquals
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

/**
 * The time shown by the sync screens and the settings: "5 min ago" for a week, then a date with
 * its year. Without a bound the text reads "212 d ago": the conflict and enrolled device screens
 * lost the date they showed from 7 days in 1.0.0 (`DateUtils`), and the settings screen, which
 * already read "N d ago" without a bound in 1.0.0, gets the bound too.
 *
 * Pure JVM: `now` and the zone are given, nothing reads the clock. The composable that wraps these
 * functions (`relativeTimeLabel`) only picks the string resources and reads the configuration.
 */
class RelativeTimeTextTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val paris: ZoneId = ZoneId.of("Europe/Paris")

    /** Saturday 13 March 2027, noon UTC: 8 days before is 5 March, 12 days before is 1 March. */
    private val now = utcMillis(2027, 3, 13)

    private fun utcMillis(year: Int, month: Int, dayOfMonth: Int, hour: Int = 12): Long =
        ZonedDateTime.of(year, month, dayOfMonth, hour, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun ago(value: Int, unit: RelativeTime.Unit) = TimeLabel.Ago(RelativeTime.Bucket(value, unit))

    /** The JDK may put a no-break space (U+00A0) or a narrow one (U+202F) in a date: compared as plain spaces. */
    private fun plain(text: String) = text.replace('\u00A0', ' ').replace('\u202F', ' ')

    private fun french(epochMillis: Long, zone: ZoneId = utc) =
        plain(formatAbsoluteDate(epochMillis, Locale.FRANCE, zone))

    private fun english(epochMillis: Long, zone: ZoneId = utc) =
        plain(formatAbsoluteDate(epochMillis, Locale.US, zone))

    // ── The threshold ─────────────────────────────────────────────────────────

    @Test
    fun `the limit is seven days`() {
        assertEquals(7 * 86_400_000L, RELATIVE_TIME_LIMIT_MS)
        assertEquals(604_800_000L, RELATIVE_TIME_LIMIT_MS)
    }

    @Test
    fun `below a week the time stays relative, from seconds to days`() {
        assertEquals(ago(0, RelativeTime.Unit.SECONDS), timeLabel(now, now))
        assertEquals(ago(59, RelativeTime.Unit.SECONDS), timeLabel(now - 59 * SECOND, now))
        assertEquals(ago(5, RelativeTime.Unit.MINUTES), timeLabel(now - 5 * MINUTE, now))
        assertEquals(ago(3, RelativeTime.Unit.HOURS), timeLabel(now - 3 * HOUR, now))
        assertEquals(ago(1, RelativeTime.Unit.DAYS), timeLabel(now - DAY, now))
        assertEquals(ago(3, RelativeTime.Unit.DAYS), timeLabel(now - 3 * DAY, now))
    }

    @Test
    fun `6 days 23 hours 59 minutes is the last relative time, it reads 6 days`() {
        val age = 6 * DAY + 23 * HOUR + 59 * MINUTE
        assertEquals(ago(6, RelativeTime.Unit.DAYS), timeLabel(now - age, now))
    }

    @Test
    fun `one millisecond below seven days is still relative`() {
        assertEquals(ago(6, RelativeTime.Unit.DAYS), timeLabel(now - (RELATIVE_TIME_LIMIT_MS - 1), now))
    }

    @Test
    fun `exactly seven days is a date`() {
        val timestamp = now - RELATIVE_TIME_LIMIT_MS
        assertEquals(TimeLabel.OnDate(timestamp), timeLabel(timestamp, now))
    }

    @Test
    fun `eight days is a date, with the year, in French and in English`() {
        val timestamp = now - 8 * DAY
        assertEquals(TimeLabel.OnDate(timestamp), timeLabel(timestamp, now))
        assertEquals("5 mars 2027", french(timestamp))
        assertEquals("Mar 5, 2027", english(timestamp))
    }

    @Test
    fun `the year is shown even in the year of the date`() {
        // 12 days before now, same year: the year is still there.
        val timestamp = now - 12 * DAY
        assertEquals(TimeLabel.OnDate(timestamp), timeLabel(timestamp, now))
        assertEquals("1 mars 2027", french(timestamp))
        assertEquals("Mar 1, 2027", english(timestamp))
    }

    @Test
    fun `a date of the previous year says that year`() {
        val laterNow = utcMillis(2027, 1, 3)
        val timestamp = utcMillis(2026, 12, 26)
        assertEquals(TimeLabel.OnDate(timestamp), timeLabel(timestamp, laterNow))
        assertEquals("26 déc. 2026", french(timestamp))
        assertEquals("Dec 26, 2026", english(timestamp))
    }

    // ── The date follows the zone and the language it is given ────────────────

    @Test
    fun `the day is the one of the zone, not the one of UTC`() {
        // 23:30 UTC on 31 December is already 1 January in Paris (UTC+1 in winter).
        val timestamp = Instant.parse("2026-12-31T23:30:00Z").toEpochMilli()
        assertEquals("1 janv. 2027", french(timestamp, paris))
        assertEquals("31 déc. 2026", french(timestamp, utc))
        assertEquals("Jan 1, 2027", english(timestamp, paris))
        assertEquals("Dec 31, 2026", english(timestamp, utc))
    }

    @Test
    fun `the formatter is built at each call, so a language change is followed`() {
        val timestamp = now - 8 * DAY
        assertEquals("5 mars 2027", french(timestamp))
        assertEquals("Mar 5, 2027", english(timestamp))
        assertEquals("5 mars 2027", french(timestamp))
    }

    // ── The language of the date is the one of the displayed resources ────────

    @Test
    fun `a phone in the language of the resources keeps its own locale, region included`() {
        assertEquals(Locale.US, dateLocale(Locale.US, "en"))
        assertEquals(Locale.UK, dateLocale(Locale.UK, "en"))
        assertEquals(Locale.CANADA_FRENCH, dateLocale(Locale.CANADA_FRENCH, "fr"))
        assertEquals(Locale.FRANCE, dateLocale(Locale.FRANCE, "fr"))
    }

    @Test
    fun `a phone in another language gets the language of the resources, without a region`() {
        // A German phone shows the French resources (the default): its date must not stay in German.
        assertEquals(Locale.forLanguageTag("fr"), dateLocale(Locale.GERMANY, "fr"))
        assertEquals(Locale.FRENCH, dateLocale(Locale.GERMANY, "fr"))
        // A French phone whose resources are English (a per-app language, a second language of the list).
        assertEquals(Locale.forLanguageTag("en"), dateLocale(Locale.FRANCE, "en"))
        assertEquals(Locale.ENGLISH, dateLocale(Locale.FRANCE, "en"))
        // No language at all in the configuration.
        assertEquals(Locale.FRENCH, dateLocale(Locale.ROOT, "fr"))
    }

    @Test
    fun `a German phone reads the date in French when the interface is in French`() {
        val timestamp = now - 8 * DAY
        val locale = dateLocale(Locale.GERMANY, "fr")
        assertEquals("5 mars 2027", plain(formatAbsoluteDate(timestamp, locale, utc)))
    }

    // ── A clock that is off ───────────────────────────────────────────────────

    @Test
    fun `a slight future reads 0 seconds ago, as before the threshold`() {
        assertEquals(ago(0, RelativeTime.Unit.SECONDS), timeLabel(now + 3 * HOUR, now))
        assertEquals(ago(0, RelativeTime.Unit.SECONDS), timeLabel(now + 1, now))
    }

    @Test
    fun `a future of one millisecond below seven days is still relative, seven days is a date`() {
        assertEquals(ago(0, RelativeTime.Unit.SECONDS), timeLabel(now + RELATIVE_TIME_LIMIT_MS - 1, now))
        val timestamp = now + RELATIVE_TIME_LIMIT_MS
        assertEquals(TimeLabel.OnDate(timestamp), timeLabel(timestamp, now))
    }

    @Test
    fun `a future of 30 days is a date`() {
        val timestamp = now + 30 * DAY
        assertEquals(TimeLabel.OnDate(timestamp), timeLabel(timestamp, now))
        assertEquals("12 avr. 2027", french(timestamp))
        assertEquals("Apr 12, 2027", english(timestamp))
    }

    @Test
    fun `an out of range timestamp is a date whatever the subtraction wraps to`() {
        assertEquals(TimeLabel.OnDate(Long.MIN_VALUE), timeLabel(Long.MIN_VALUE, now))
        assertEquals(TimeLabel.OnDate(Long.MAX_VALUE), timeLabel(Long.MAX_VALUE, now))
    }

    @Test
    fun `timestamp zero is a date, 1 January 1970, and formatting it does not fail`() {
        assertEquals(TimeLabel.OnDate(0L), timeLabel(0L, now))
        assertEquals("1 janv. 1970", french(0L, utc))
        assertEquals("Jan 1, 1970", english(0L, utc))
    }
}

private const val SECOND = 1_000L
private const val MINUTE = 60 * SECOND
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

/** The date and time of the transfers history: the order of the day and the month follows the language. */
class LocalizedDateTimeTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val timestamp = ZonedDateTime.of(2027, 3, 5, 14, 30, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun plain(text: String) = text.replace('\u00A0', ' ').replace('\u202F', ' ')

    @Test
    fun `5 March is never ambiguous, in French and in American English`() {
        val fr = plain(formatLocalizedDateTime(timestamp, Locale.FRANCE, utc))
        val en = plain(formatLocalizedDateTime(timestamp, Locale.US, utc))
        assertEquals(true, fr.startsWith("5 mars 2027"), fr)
        assertEquals(true, en.startsWith("Mar 5, 2027"), en)
    }

    @Test
    fun `the time follows the zone`() {
        val paris = ZoneId.of("Europe/Paris")
        assertEquals(true, plain(formatLocalizedDateTime(timestamp, Locale.FRANCE, paris)).endsWith("15:30"))
    }
}

class LocalizedTimeTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val timestamp = ZonedDateTime.of(2027, 3, 5, 14, 30, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun reference(locale: Locale, zone: ZoneId) = java.time.format.DateTimeFormatter
        .ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(locale)
        .format(Instant.ofEpochMilli(timestamp).atZone(zone))

    @Test
    fun `the time is the short localized one of the language`() {
        assertEquals(reference(Locale.FRANCE, utc), formatLocalizedTime(timestamp, Locale.FRANCE, utc))
        assertEquals(reference(Locale.US, utc), formatLocalizedTime(timestamp, Locale.US, utc))
        assertEquals(true, formatLocalizedTime(timestamp, Locale.FRANCE, utc).startsWith("14:30"))
        assertEquals(true, formatLocalizedTime(timestamp, Locale.US, utc).startsWith("2:30"))
    }

    @Test
    fun `the time follows the zone`() {
        assertEquals(true, formatLocalizedTime(timestamp, Locale.FRANCE, ZoneId.of("Europe/Paris")).startsWith("15:30"))
    }
}
