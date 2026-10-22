package com.xblackbytesx.slopstream.ui.components

import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** "12 min ago", "3 h ago", "Yesterday", "4 days ago", then a date. */
fun relativeTime(epochMs: Long, now: Long = System.currentTimeMillis(), approximate: Boolean = false): String {
    val d = Duration.ofMillis(now - epochMs)
    val prefix = if (approximate) "~" else ""
    return when {
        d.isNegative -> "Scheduled " + DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))
        d.toMinutes() < 1 -> "Just now"
        d.toMinutes() < 60 -> "$prefix${d.toMinutes()} min ago"
        d.toHours() < 24 -> "$prefix${d.toHours()} h ago"
        d.toDays() < 2 -> "Yesterday"
        d.toDays() < 7 -> "$prefix${d.toDays()} days ago"
        d.toDays() < 28 -> "$prefix${d.toDays() / 7} wk ago"
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))
    }
}

/** 4:05, 1:02:33 */
fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatPosition(ms: Long): String = formatDuration((ms / 1000).coerceAtLeast(0))

/**
 * True when every word of [query] appears in [text], ignoring case and accents, so "cafe"
 * finds "Café Racer". An empty query matches everything.
 */
fun matchesWords(text: String, query: String): Boolean {
    val haystack = fold(text)
    return query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.all { fold(it) in haystack }
}

private val MARKS = Regex("\\p{M}+")

private fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase()
