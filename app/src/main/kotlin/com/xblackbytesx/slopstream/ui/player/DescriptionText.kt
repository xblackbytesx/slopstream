package com.xblackbytesx.slopstream.ui.player

/** One line of a description as the Description tab shows it. */
sealed interface DescriptionLine {
    /** Text, with the web addresses in it ([links], as ranges of [text]). */
    data class Text(val text: String, val links: List<IntRange> = emptyList()) : DescriptionLine

    /**
     * A line that names a moment of the video ("4:12 Teardown"): selecting it jumps there.
     * [text] is the line without its timestamp ("Teardown").
     */
    data class Chapter(val text: String, val atMs: Long) : DescriptionLine

    /** Where a paragraph ends. */
    data object Gap : DescriptionLine
}

/**
 * Splits a plain-text description into lines for the Description tab. A line that starts or
 * ends with a timestamp within the video's length becomes a [DescriptionLine.Chapter], the way
 * creators list chapters ("0:00 Intro", "Teardown - 4:12", "(1:02:33) Outro"); a timestamp in the
 * middle of a sentence stays text. Blank lines fold into one [DescriptionLine.Gap].
 */
object DescriptionText {
    // Not part of a longer number or time: "10:30:15:00" and "2024:10" are not timestamps.
    private val STAMP = """(?<![\d:.])(?:(\d{1,2}):)?(\d{1,3}):(\d{2})(?![\d:])"""
    private val LEADING = Regex("""^[\s•·*\-–—>(\[]*$STAMP""")
    private val TRAILING = Regex("""$STAMP[\s)\]]*$""")
    private val URL = Regex("""https?://[^\s<>"]+""")

    fun lines(text: String, durationSec: Long): List<DescriptionLine> {
        val out = ArrayList<DescriptionLine>()
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            if (line.isBlank()) {
                if (out.isNotEmpty() && out.last() != DescriptionLine.Gap) out += DescriptionLine.Gap
                continue
            }
            out += chapter(line, durationSec) ?: DescriptionLine.Text(line, links(line))
        }
        if (out.lastOrNull() == DescriptionLine.Gap) out.removeAt(out.lastIndex)
        return out
    }

    /** [line] as a chapter, when it is one. A video of unknown length (live) has none. */
    internal fun chapter(line: String, durationSec: Long): DescriptionLine.Chapter? {
        if (durationSec <= 0) return null
        val m = LEADING.find(line) ?: TRAILING.find(line) ?: return null
        val (h, min, s) = m.destructured
        val hours = h.toLongOrNull() ?: 0
        val minutes = min.toLong()
        val seconds = s.toLong()
        if (seconds >= 60 || (h.isNotEmpty() && minutes >= 60)) return null
        val at = hours * 3600 + minutes * 60 + seconds
        if (at > durationSec) return null
        val label = line.removeRange(m.range).trim { it.isWhitespace() || it in SEPARATORS }
        return DescriptionLine.Chapter(label.ifEmpty { line.trim() }, at * 1000)
    }

    private const val SEPARATORS = "-–—|:•·*()[]>"

    /** Web addresses, without the punctuation that ends the sentence around them. */
    internal fun links(line: String): List<IntRange> = URL.findAll(line).map { m ->
        var end = m.range.last
        while (end > m.range.first && line[end] in ".,;:!?'\"") end--
        // A closing bracket belongs to the address only when it opened one: wikipedia.org/a_(b)
        if (line[end] == ')' && line.substring(m.range.first, end + 1).count { it == '(' } < line.substring(m.range.first, end + 1).count { it == ')' }) end--
        m.range.first..end
    }.toList()
}
