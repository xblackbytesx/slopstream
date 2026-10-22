package com.xblackbytesx.slopstream.youtube

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import javax.xml.parsers.DocumentBuilderFactory

/** One `<entry>` of a YouTube Atom feed. */
data class RssEntry(
    val videoId: String,
    val channelId: String,
    val title: String,
    val channelName: String,
    val published: Instant,
    val link: String,
    val thumbnailUrl: String?,
)

data class RssFeed(
    val channelId: String?,
    val channelName: String?,
    val entries: List<RssEntry>,
)

/**
 * Parses YouTube's Atom feeds (`/feeds/videos.xml?channel_id=` or `?playlist_id=`). The DOM
 * parser ships on both the JVM and Android; the feed is at most 15 entries, so DOM is cheap.
 */
object RssParser {
    private const val ATOM = "http://www.w3.org/2005/Atom"
    private const val YT = "http://www.youtube.com/xml/schemas/2015"
    private const val MEDIA = "http://search.yahoo.com/mrss/"

    fun parse(input: InputStream): RssFeed {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // Android's parser rejects unknown features; the JVM honours them. Either way no
            // DTD or external entity in a feed is ever resolved.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        }
        val doc = factory.newDocumentBuilder().parse(input)
        val feed = doc.documentElement

        val feedChannelId = feed.child(YT, "channelId")?.text()
        val feedAuthor = feed.child(ATOM, "author")?.child(ATOM, "name")?.text()

        val entries = feed.children(ATOM, "entry").mapNotNull { e ->
            val videoId = e.child(YT, "videoId")?.text() ?: return@mapNotNull null
            val published = e.child(ATOM, "published")?.text()?.let(::parseInstant) ?: return@mapNotNull null
            val author = e.child(ATOM, "author")?.child(ATOM, "name")?.text()
            RssEntry(
                videoId = videoId,
                channelId = e.child(YT, "channelId")?.text() ?: feedChannelId.orEmpty(),
                title = e.child(ATOM, "title")?.text().orEmpty(),
                channelName = author ?: feedAuthor.orEmpty(),
                published = published,
                link = e.children(ATOM, "link").firstOrNull()?.getAttribute("href").orEmpty(),
                thumbnailUrl = e.child(MEDIA, "group")?.child(MEDIA, "thumbnail")?.getAttribute("url")?.takeIf { it.isNotBlank() },
            )
        }
        return RssFeed(feedChannelId?.takeIf { it.isNotBlank() }, feedAuthor?.takeIf { it.isNotBlank() }, entries)
    }

    private fun parseInstant(s: String): Instant? = runCatching { OffsetDateTime.parse(s.trim()).toInstant() }.getOrNull()

    private fun Element.children(ns: String, name: String): List<Element> {
        val out = ArrayList<Element>()
        var n: Node? = firstChild
        while (n != null) {
            if (n is Element && n.namespaceURI == ns && n.localName == name) out += n
            n = n.nextSibling
        }
        return out
    }

    private fun Element.child(ns: String, name: String): Element? = children(ns, name).firstOrNull()

    private fun Element.text(): String = textContent.trim()
}
