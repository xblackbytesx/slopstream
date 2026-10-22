package com.xblackbytesx.slopstream.ui.player

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xblackbytesx.slopstream.ui.theme.Palette

/**
 * Shorts from the channels you follow, newest first, one after another: swipe up/down on a
 * phone, D-pad up/down (or next/previous) on a TV. Each one loops until you move on.
 */
@Composable
fun ShortsPlayerScreen(onBack: () -> Unit, enterPip: () -> Unit, vm: ShortsPlayerViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val state by vm.engine.state.collectAsStateWithLifecycle()
    val list = items
    if (list == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.Accent) }
        return
    }
    var index by remember { mutableIntStateOf(vm.startIndex) }
    LaunchedEffect(index) { vm.onPage(index) }
    val row = list.getOrNull(index)

    fun next() { if (index < list.lastIndex) index++ }
    fun previous() { if (index > 0) index-- }

    var drag by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier.fillMaxSize().pointerInput(list.size) {
            detectVerticalDragGestures(
                onDragStart = { drag = 0f },
                onVerticalDrag = { _, dy -> drag += dy },
                onDragEnd = {
                    val threshold = size.height / 8f
                    if (drag < -threshold) next() else if (drag > threshold) previous()
                },
            )
        },
    ) {
        PlayerChrome(
            engine = vm.engine,
            state = state,
            title = row?.title.orEmpty(),
            subtitle = row?.channelName.orEmpty(),
            matchFrameRate = false,
            onBack = onBack,
            enterPip = enterPip,
            portraitShorts = true,
            onPrevious = ::previous,
            onNext = ::next,
        )
    }
}
