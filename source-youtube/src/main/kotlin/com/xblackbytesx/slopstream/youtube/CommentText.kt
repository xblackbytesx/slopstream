package com.xblackbytesx.slopstream.youtube

import com.xblackbytesx.slopstream.core.HtmlText

/** YouTube comment text arrives as a little HTML (`<br>`, links, `<b>`): shown as plain text. */
object CommentText {
    fun plain(html: String): String = HtmlText.plain(html)
}
