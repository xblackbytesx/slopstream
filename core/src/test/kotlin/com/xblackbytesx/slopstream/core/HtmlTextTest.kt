package com.xblackbytesx.slopstream.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlTextTest {
    @Test
    fun paragraphsBecomeBlankLines() {
        assertEquals(
            "First paragraph.\n\nSecond, with a\nbreak.",
            HtmlText.plain("<p>First paragraph.</p><p>Second, with a<br/>break.</p>"),
        )
        assertEquals("One\n\nTwo", HtmlText.plain("<p>One</p>\n<p></p>\n<p></p><p>Two</p>"))
    }

    @Test
    fun listsBecomeBullets() {
        assertEquals("Gear:\n\n• Camera\n• Lens", HtmlText.plain("<p>Gear:</p><ul><li>Camera</li><li>Lens</li></ul>"))
    }

    @Test
    fun shortenedLinksBecomeTheirAddress() {
        assertEquals(
            "Shop: https://example.com/a?b=1&c=2 now",
            HtmlText.plain("Shop: <a href=\"https://example.com/a?b=1&amp;c=2\">example.com/a…</a> now"),
        )
        assertEquals(
            "Site https://www.example.com/x",
            HtmlText.plain("Site <a href='https://www.example.com/x'>https://www.example.com/…</a>"),
        )
    }

    @Test
    fun wordedLinksKeepTheirWords() {
        assertEquals("see 2:15 and #teardown", HtmlText.plain("see <a href=\"https://www.youtube.com/watch?v=x&amp;t=135\">2:15</a> and <a href=\"/hashtag/teardown\">#teardown</a>"))
    }

    @Test
    fun entitiesDecodedOnce() {
        assertEquals(
            "Second & <third> \"quoted\" it's é 😀 &amp;",
            HtmlText.plain("Second &amp; &lt;third&gt; &quot;quoted&quot; it&#39;s &#233; &#x1F600; &amp;amp;"),
        )
        assertEquals("<b> stays &bogus;", HtmlText.plain("&lt;b&gt; stays &bogus;"))
    }
}
