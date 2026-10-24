package com.xblackbytesx.slopstream.ui.comments

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.xblackbytesx.slopstream.data.Sources
import com.xblackbytesx.slopstream.ui.components.ScreenHeader
import com.xblackbytesx.slopstream.ui.nav.CommentsRoute
import com.xblackbytesx.slopstream.ui.theme.LocalDims
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class CommentsViewModel @Inject constructor(savedState: SavedStateHandle, sources: Sources) : ViewModel() {
    val route: CommentsRoute = savedState.toRoute()
    val loader = CommentsLoader(sources[route.source], route.videoId, viewModelScope).also { it.start() }
}

/** A video's comments, from the video menu (decision 20). */
@Composable
fun CommentsScreen(vm: CommentsViewModel = hiltViewModel()) {
    val state by vm.loader.state.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    val dims = LocalDims.current
    LaunchedEffect(state.nodes.isNotEmpty()) { if (state.nodes.isNotEmpty()) runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Comments", status = vm.route.title.ifBlank { null })
        CommentsList(
            state,
            onMore = vm.loader::more,
            onReplies = vm.loader::toggleReplies,
            onMoreReplies = vm.loader::moreReplies,
            modifier = Modifier.padding(horizontal = dims.gutter).widthIn(max = 820.dp),
            firstFocus = first,
        )
    }
}
