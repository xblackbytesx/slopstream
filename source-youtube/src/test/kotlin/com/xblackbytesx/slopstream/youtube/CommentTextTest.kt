package com.xblackbytesx.slopstream.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

class CommentTextTest {
    @Test
    fun htmlBecomesPlainText() {
        assertEquals(
            "First line\nSecond & <third> \"quoted\" it's é 😀",
            CommentText.plain("First line<br>Second &amp; &lt;third&gt; &quot;quoted&quot; it&#39;s &#233; &#x1F600;"),
        )
        assertEquals("see 2:15 here", CommentText.plain("see <a href=\"https://www.youtube.com/watch?v=x&amp;t=135\">2:15</a> here"))
        assertEquals("<b> stays &bogus;", CommentText.plain("&lt;b&gt; stays &bogus;"))
    }
}
