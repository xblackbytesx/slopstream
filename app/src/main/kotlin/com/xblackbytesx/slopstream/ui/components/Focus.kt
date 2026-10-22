package com.xblackbytesx.slopstream.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.xblackbytesx.slopstream.ui.theme.LocalIsTv
import com.xblackbytesx.slopstream.ui.theme.Palette

/**
 * The focus look on TV: a slight lift and an accent ring, animated in the draw phase only so a
 * fast D-pad scroll never recomposes the grid. On touch devices it does nothing.
 */
@Composable
fun Modifier.focusLift(interaction: MutableInteractionSource, shape: Shape, scale: Float = 1.05f): Modifier {
    if (!LocalIsTv.current) return this
    val focused by interaction.collectIsFocusedAsState()
    val s by animateFloatAsState(if (focused) scale else 1f, spring(stiffness = 600f), label = "lift")
    return this
        .graphicsLayer { scaleX = s; scaleY = s }
        .then(if (focused) Modifier.border(2.5.dp, Palette.Accent, shape) else Modifier)
}
