package com.xblackbytesx.slopstream.ui.player

import com.xblackbytesx.slopstream.ui.player.DescriptionLine.Chapter
import com.xblackbytesx.slopstream.ui.player.DescriptionLine.Gap
import com.xblackbytesx.slopstream.ui.player.DescriptionLine.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DescriptionTextTest {
    @Test
    fun chaptersParagraphsAndLinks() {
        val text = """
            Today we tear it down.


            Chapters:
            0:00 Intro
            4:12 - Teardown
            Bend test | 12:05
            (1:02:33) Outro

            Shop: https://example.com/gear. Thanks!
        """.trimIndent()
        assertEquals(
            listOf(
                Text("Today we tear it down."),
                Gap,
                Text("Chapters:"),
                Chapter("Intro", 0),
                Chapter("Teardown", 252_000),
                Chapter("Bend test", 725_000),
                Chapter("Outro", 3_753_000),
                Gap,
                Text("Shop: https://example.com/gear. Thanks!", listOf(6..29)),
            ),
            DescriptionText.lines(text, durationSec = 4_000),
        )
    }

    @Test
    fun aTimestampMidSentenceOrPastTheEndStaysText() {
        assertNull(DescriptionText.chapter("At 4:12 it bends, and again later", 600))
        assertNull(DescriptionText.chapter("20:00 Past the end", 600))
        assertNull(DescriptionText.chapter("1:75 not a time", 600))
        assertNull(DescriptionText.chapter("2024:10 a year", 600_000))
        // Live or of unknown length: nothing to jump to.
        assertNull(DescriptionText.chapter("0:30 Intro", 0))
    }

    @Test
    fun aLineThatIsOnlyATimestampKeepsIt() {
        assertEquals(Chapter("5:00", 300_000), DescriptionText.chapter("5:00", 600))
    }

    @Test
    fun linksLoseSentencePunctuationButKeepTheirBrackets() {
        val line = "See (https://en.wikipedia.org/wiki/Foo_(bar)), or https://x.org/a?b=1!"
        val links = DescriptionText.links(line).map { line.substring(it) }
        assertEquals(listOf("https://en.wikipedia.org/wiki/Foo_(bar)", "https://x.org/a?b=1"), links)
    }

    @Test
    fun noBlankEdges() {
        assertEquals(listOf(Text("One")), DescriptionText.lines("\n\nOne\n\n\n", 60))
        assertEquals(emptyList<DescriptionLine>(), DescriptionText.lines("", 60))
    }
}
