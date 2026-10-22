package com.xblackbytesx.slopstream.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Palette {
    val Background = Color(0xFF0E0F12)
    val Surface = Color(0xFF16181D)
    val SurfaceHigh = Color(0xFF1F2229)
    val Outline = Color(0xFF2A2E37)
    val Text = Color(0xFFEDEFF3)
    val TextDim = Color(0xFFA7ADB8)
    val TextFaint = Color(0xFF6B7280)
    val Accent = Color(0xFF5EEAD4)
    val OnAccent = Color(0xFF0E0F12)
    val Live = Color(0xFFF43F5E)
    val Scrim = Color(0xCC000000)
}

/** Sizes that differ between a phone held at arm's length and a TV across the room. */
@Immutable
data class Dims(
    val gutter: Dp,
    val tileMinWidth: Dp,
    val shortTileMinWidth: Dp,
    val gridSpacing: Dp,
    val corner: Dp,
)

val PhoneDims = Dims(gutter = 16.dp, tileMinWidth = 300.dp, shortTileMinWidth = 150.dp, gridSpacing = 16.dp, corner = 12.dp)
val TvDims = Dims(gutter = 48.dp, tileMinWidth = 176.dp, shortTileMinWidth = 120.dp, gridSpacing = 20.dp, corner = 10.dp)

/** True on a television: D-pad first, 10-foot sizing, left rail instead of a bottom bar. */
val LocalIsTv = staticCompositionLocalOf { false }
val LocalDims = staticCompositionLocalOf { PhoneDims }

private val colors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.OnAccent,
    secondary = Palette.Accent,
    onSecondary = Palette.OnAccent,
    background = Palette.Background,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextDim,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Palette.SurfaceHigh,
    outline = Palette.Outline,
    outlineVariant = Palette.Outline,
    error = Palette.Live,
)

private val AppTypography: Typography = run {
    fun s(size: Int, weight: FontWeight, line: Int = (size * 1.3f).toInt(), tracking: Float = 0f) =
        TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.sp)
    Typography(
        displaySmall = s(32, FontWeight.SemiBold, tracking = -0.5f),
        headlineSmall = s(24, FontWeight.SemiBold, tracking = -0.2f),
        titleLarge = s(20, FontWeight.SemiBold),
        titleMedium = s(16, FontWeight.Medium),
        titleSmall = s(14, FontWeight.Medium),
        bodyLarge = s(16, FontWeight.Normal),
        bodyMedium = s(14, FontWeight.Normal),
        bodySmall = s(12, FontWeight.Normal),
        labelLarge = s(14, FontWeight.Medium),
        labelMedium = s(12, FontWeight.Medium),
        labelSmall = s(11, FontWeight.SemiBold, tracking = 0.3f),
    )
}

@Composable
fun SlopstreamTheme(isTv: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colors,
        // TVs already report a density that sizes dp for 3 m away, so text needs no extra scale.
        typography = AppTypography,
        shapes = Shapes(
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp),
        ),
    ) {
        CompositionLocalProvider(
            LocalIsTv provides isTv,
            LocalDims provides if (isTv) TvDims else PhoneDims,
            LocalContentColor provides Palette.Text,
            content = content,
        )
    }
}
