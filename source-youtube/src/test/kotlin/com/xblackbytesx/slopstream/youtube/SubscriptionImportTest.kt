package com.xblackbytesx.slopstream.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SubscriptionImportTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    @Test
    fun newPipeJsonKeepsYoutubeChannelsOnce() {
        val r = SubscriptionImport.fromNewPipeJson(fixture("newpipe_subscriptions.json"))
        assertEquals(listOf("UCaaaaaaaaaaaaaaaaaaaaaa", "UCbbbbbbbbbbbbbbbbbbbbbb"), r.subscriptions.map { it.channelId })
        assertEquals("Fixture Channel", r.subscriptions.first().name)
        assertEquals(listOf("https://www.youtube.com/@somehandle"), r.unresolved.map { it.first })
        assertEquals(1, r.skippedOtherServices)
    }

    @Test
    fun takeoutCsvHandlesBomHeaderAndQuotes() {
        val r = SubscriptionImport.fromTakeoutCsv(fixture("takeout_subscriptions.csv"))
        assertEquals(2, r.subscriptions.size)
        assertEquals("Comma, \"Quoted\" Name", r.subscriptions[1].name)
    }

    @Test
    fun wrongFilesAreRejected() {
        assertThrows(ImportFormatException::class.java) { SubscriptionImport.fromNewPipeJson("{\"foo\":1}") }
        assertThrows(ImportFormatException::class.java) { SubscriptionImport.fromNewPipeJson("not json") }
        assertThrows(ImportFormatException::class.java) { SubscriptionImport.fromTakeoutCsv("a,b,c\n1,2,3") }
    }

    @Test
    fun idsAreExtractedFromUrls() {
        assertEquals("UCaaaaaaaaaaaaaaaaaaaaaa", YoutubeIds.channelIdFrom("https://m.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa/videos"))
        assertEquals(null, YoutubeIds.channelIdFrom("https://www.youtube.com/@handle"))
        assertEquals("dQw4w9WgXcQ", YoutubeIds.videoIdFrom("https://youtu.be/dQw4w9WgXcQ?t=1"))
        assertEquals("dQw4w9WgXcQ", YoutubeIds.videoIdFrom("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("UULFaaaaaaaaaaaaaaaaaaaaaa", YoutubeIds.uploadsPlaylist("UCaaaaaaaaaaaaaaaaaaaaaa", YoutubeIds.Upload.VIDEOS))
    }
}
