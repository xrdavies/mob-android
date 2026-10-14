package com.xrdavies.mob.ui.common

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

fun parseTime(iso: String?): Instant? = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

/** "now", "5m", "3h", "2d", then a short date. */
fun relativeTime(iso: String?, now: Instant = Instant.now()): String {
    val t = parseTime(iso) ?: return ""
    val d = Duration.between(t, now)
    return when {
        d.toMinutes() < 1 -> "now"
        d.toHours() < 1 -> "${d.toMinutes()}m"
        d.toDays() < 1 -> "${d.toHours()}h"
        d.toDays() < 7 -> "${d.toDays()}d"
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(t)
    }
}

fun shortDateTime(iso: String?): String {
    val t = parseTime(iso) ?: return ""
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(t)
}

/** "1:05" or "12s" for a turn's duration. */
fun duration(from: String?, to: String?): String {
    val a = parseTime(from) ?: return ""
    val b = parseTime(to) ?: Instant.now()
    val s = Duration.between(a, b).seconds.coerceAtLeast(0)
    return if (s < 60) "${s}s" else "${s / 60}:${"%02d".format(s % 60)}"
}

fun tokens(n: Long?): String = when {
    n == null -> "0"
    n >= 1_000_000 -> "%.1fM".format(n / 1e6)
    n >= 1_000 -> "%.1fk".format(n / 1e3)
    else -> n.toString()
}
