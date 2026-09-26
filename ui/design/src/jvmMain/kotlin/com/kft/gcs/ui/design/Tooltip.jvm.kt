package com.kft.gcs.ui.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Desktop: shows on mouse hover after half a second, styled like M3's plain tooltip (inverse surface, small text). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal actual fun Tooltip(label: String, modifier: Modifier, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = {
            Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.inverseSurface) {
                Text(label, Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xs), style = MaterialTheme.typography.bodySmall)
            }
        },
        modifier = modifier,
        delayMillis = 500,
        content = content,
    )
}
