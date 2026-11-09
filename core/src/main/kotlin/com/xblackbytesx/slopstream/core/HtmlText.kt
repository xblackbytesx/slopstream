package com.xblackbytesx.slopstream.core

/**
 * The little HTML services send for comments and descriptions (`<p>`, `<br>`, links, `<b>`), as
 * plain text: paragraphs become blank lines, line breaks stay, tags go, entities are decoded.
 * A link keeps its visible text, except when that text is a URL, which services shorten
 * ("youtube.com/watch?v=…"): then it becomes the full address it points to.
 */
object HtmlText {
    private val BREAK = Regex("(?i)<br\\s*/?>")
    private val PARAGRAPH_END = Regex("(?i)</(p|div|h[1-6]|ul|ol)>")
    private val ITEM = Regex("(?i)<li[^>]*>")
    private val LINK = Regex("(?is)<a\\s[^>]*?href\\s*=\\s*(\"[^\"]*\"|'[^']*')[^>]*>(.*?)</a>")
    private val TAG = Regex("<[^>]+>")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);")
    private val BLANK_LINES = Regex("\n[ \t]*\n(?:[ \t]*\n)+")

    fun plain(html: String): String {
        var text = LINK.replace(html) { m ->
            // Still encoded: decoded once with the rest below.
            val href = m.groupValues[1].drop(1).dropLast(1).trim()
            val shown = m.groupValues[2]
            val visible = decode(TAG.replace(shown, "")).trim()
            if (looksLikeUrl(visible) && href.startsWith("http")) href else shown
        }
        text = BREAK.replace(text, "\n")
        text = PARAGRAPH_END.replace(text, "\n\n")
        text = ITEM.replace(text, "\n• ")
        text = decode(TAG.replace(text, ""))
        return BLANK_LINES.replace(text, "\n\n").trim()
    }

    private fun looksLikeUrl(s: String) =
        s.startsWith("http://") || s.startsWith("https://") || s.startsWith("www.") || (s.endsWith("…") || s.endsWith("...")) && '/' in s

    private fun decode(s: String): String = ENTITY.replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> " "
            else -> {
                val code = if (e.startsWith("#x")) e.drop(2).toIntOrNull(16) else e.drop(1).toIntOrNull()
                code?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
            }
        }
    }
}
