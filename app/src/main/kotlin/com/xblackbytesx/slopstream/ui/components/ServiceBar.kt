package com.xblackbytesx.slopstream.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.data.ServiceState
import com.xblackbytesx.slopstream.ui.theme.LocalDims
import com.xblackbytesx.slopstream.ui.theme.Palette

/**
 * Picks the service a top-level screen shows (one at a time, never mixed), and asks to sign in
 * again when the shown service's session was rejected. Nothing at all with one service.
 */
@Composable
fun ServiceBar(state: ServiceState, onSelect: (SourceKey) -> Unit, onSignIn: (SourceKey) -> Unit) {
    if (state.visible.size < 2 && !state.expired) return
    Column(
        Modifier.padding(horizontal = LocalDims.current.gutter).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.visible.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.visible.forEach { key ->
                    FilterChip(
                        selected = key == state.current,
                        onClick = { onSelect(key) },
                        label = { Text(key.label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.Accent, selectedLabelColor = Palette.OnAccent),
                    )
                }
            }
        }
        if (state.expired) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${state.current.label} signed you out. This is what was fetched before.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Live,
                    modifier = Modifier.widthIn(max = 520.dp),
                )
                SecondaryButton("Sign in again", { onSignIn(state.current) }, icon = Icons.AutoMirrored.Filled.Login)
            }
        }
    }
}
