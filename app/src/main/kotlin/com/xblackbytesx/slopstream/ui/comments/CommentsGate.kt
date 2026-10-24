package com.xblackbytesx.slopstream.ui.comments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.data.Settings
import com.xblackbytesx.slopstream.data.Sources
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Whether a video's menu offers Comments: the setting is on and its service has them. */
@HiltViewModel
class CommentsGate @Inject constructor(settings: Settings, private val sources: Sources) : ViewModel() {
    val on: StateFlow<Boolean> = settings.settings.map { it.showComments }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun offers(source: String): Boolean = on.value &&
        SourceKey.entries.firstOrNull { it.id == source }?.takeIf { it in sources.keys }?.let { sources[it].capabilities.hasComments } == true
}
