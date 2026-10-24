package com.xblackbytesx.slopstream.ui

import com.xblackbytesx.slopstream.FakeSource
import com.xblackbytesx.slopstream.core.Comment
import com.xblackbytesx.slopstream.core.CommentsPage
import com.xblackbytesx.slopstream.core.SourceException
import com.xblackbytesx.slopstream.ui.comments.CommentsLoader
import com.xblackbytesx.slopstream.ui.comments.compact
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

fun comment(id: String, replies: CommentsPage.Cursor? = null, count: Int = 0) = Comment(
    id = id, author = "@person$id", avatarUrl = null, text = "Comment $id", likes = 1200, published = "2 days ago",
    replyCount = count, pinned = id == "c1", heartedByUploader = id == "c2", byUploader = false, replies = replies,
)

/** Pages served from memory, the way a source hands them out; counts calls. */
class CommentsFake : FakeSource() {
    val calls = ArrayList<Any?>()
    var failNext = false

    override suspend fun comments(videoId: String, from: CommentsPage.Cursor?): CommentsPage {
        calls += from?.handle
        if (failNext) {
            failNext = false
            throw SourceException("YouTube said no")
        }
        return when (from?.handle) {
            null -> CommentsPage(listOf(comment("c1"), comment("c2", CommentsPage.Cursor("r1"), 3)), CommentsPage.Cursor("p2"))
            "p2" -> CommentsPage(listOf(comment("c3")), null)
            "r1" -> CommentsPage(listOf(comment("r-a"), comment("r-b")), CommentsPage.Cursor("r2"))
            "r2" -> CommentsPage(listOf(comment("r-c")), null)
            else -> error("unknown page")
        }
    }
}

class CommentsLoaderTest {
    @Test
    fun pagesLoadOnlyWhenAsked() = runTest(UnconfinedTestDispatcher()) {
        val source = CommentsFake()
        val loader = CommentsLoader(source, "v1", this)
        assertTrue("nothing before the panel opens", source.calls.isEmpty())
        loader.start()
        loader.start()
        assertEquals(listOf<Any?>(null), source.calls)
        assertEquals(listOf("c1", "c2"), loader.state.value.nodes.map { it.comment.id })
        loader.more()
        assertEquals(listOf("c1", "c2", "c3"), loader.state.value.nodes.map { it.comment.id })
        assertNull(loader.state.value.next)
    }

    @Test
    fun repliesOpenCloseAndPage() = runTest(UnconfinedTestDispatcher()) {
        val source = CommentsFake()
        val loader = CommentsLoader(source, "v1", this)
        loader.start()
        loader.toggleReplies("c2")
        assertEquals(listOf("r-a", "r-b"), loader.state.value.nodes[1].replies.map { it.id })
        loader.moreReplies("c2")
        assertEquals(3, loader.state.value.nodes[1].replies.size)
        loader.toggleReplies("c2")
        assertFalse(loader.state.value.nodes[1].open)
        val before = source.calls.size
        loader.toggleReplies("c2")
        assertEquals("reopening does not fetch again", before, source.calls.size)
    }

    @Test
    fun anErrorCanBeRetried() = runTest(UnconfinedTestDispatcher()) {
        val source = CommentsFake().apply { failNext = true }
        val loader = CommentsLoader(source, "v1", this)
        loader.start()
        assertEquals("YouTube said no", loader.state.value.error)
        loader.more()
        assertNull(loader.state.value.error)
        assertEquals(2, loader.state.value.nodes.size)
        assertEquals("1.2K", compact(1200))
        assertEquals("3M", compact(3_000_000))
    }
}
