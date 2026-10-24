package com.xblackbytesx.slopstream.ui

import android.app.Application
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.xblackbytesx.slopstream.data.db.VideoRow
import com.xblackbytesx.slopstream.sampleRows
import com.xblackbytesx.slopstream.ui.components.VideoActions
import com.xblackbytesx.slopstream.ui.components.VideoGrid
import com.xblackbytesx.slopstream.ui.components.tileKey
import com.xblackbytesx.slopstream.ui.theme.SlopstreamTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Back from the player on a TV: the tile just played is in view and has the remote's focus. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
class GridFocusTest {
    @get:Rule val rule = createComposeRule()

    private val rows: List<VideoRow> = (1..40).map { i ->
        sampleRows[i % sampleRows.size].copy(remoteId = "v$i", title = "Video number $i")
    }

    private val noop = object : VideoActions {
        override fun play(row: VideoRow) = Unit
        override fun menu(row: VideoRow) = Unit
        override fun prefetch(row: VideoRow) = Unit
    }

    @Test
    fun theTileLastPlayedIsScrolledToAndFocused() {
        val data = flowOf(PagingData.from(rows))
        lateinit var state: LazyGridState
        rule.setContent {
            SlopstreamTheme(isTv = true) {
                val items = data.collectAsLazyPagingItems()
                state = rememberLazyGridState()
                VideoGrid(items, noop, state = state, restoreFocusKey = rows[29].tileKey())
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Video number 30").assertIsFocused()
        assertTrue("grid stayed at the top: ${state.firstVisibleItemIndex}", state.firstVisibleItemIndex > 0)
    }
}
