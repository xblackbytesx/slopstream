package com.xblackbytesx.slopstream.youtube

/**
 * YouTube comment text arrives as a little HTML (`<br>`, links, `<b>`). Shown as plain text:
 * line breaks kept, tags dropped, entities decoded. Links stay as their visible text.
 */
object CommentText {
    private val BREAK = Regex("(?i)<br\\s*/?>")
    private val TAG = Regex("<[^>]+>")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);")

    fun plain(html: String): String {
        val text = TAG.replace(BREAK.replace(html, "\n"), "")
        return ENTITY.replace(text) { m ->
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
        }.trim()
    }
}
