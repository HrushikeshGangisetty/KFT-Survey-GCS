package com.kft.gcs.ui.video

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import com.kft.gcs.ui.design.KftIcons
import com.kft.gcs.ui.design.MapCard
import com.kft.gcs.ui.design.Spacing
import com.kft.gcs.ui.design.TooltipIconButton

/**
 * Where the picture-in-picture window is: its top-left corner and size inside the area it floats over ([area], set
 * by the host). Kept by the host above the Fly screen, so the window stays put across tab switches.
 */
@Stable
class PipState {
    var area by mutableStateOf(DpSize.Zero)
    private var rawOffset by mutableStateOf(DpOffset(Dp.Unspecified, Dp.Unspecified))
    var size by mutableStateOf(DpSize(320.dp, 180.dp))
        private set

    /** The top-left corner, kept inside [area]. The first time: bottom right, clear of the map's attribution line. */
    val offset: DpOffset
        get() {
            val wanted = if (rawOffset.x == Dp.Unspecified) DpOffset(area.width - size.width - Spacing.m, area.height - size.height - Spacing.xl) else rawOffset
            return DpOffset(wanted.x.coerceIn(0.dp, max(0.dp, area.width - size.width)), wanted.y.coerceIn(0.dp, max(0.dp, area.height - size.height)))
        }

    fun moveBy(by: DpOffset) {
        val o = offset
        rawOffset = DpOffset(o.x + by.x, o.y + by.y)
    }

    /** Grows or shrinks from the bottom-right corner, between 160×90 dp and the whole area. */
    fun resizeBy(by: DpOffset) {
        val o = offset
        size = DpSize(
            (size.width + by.x).coerceIn(MIN.width, max(MIN.width, area.width - o.x)),
            (size.height + by.y).coerceIn(MIN.height, max(MIN.height, area.height - o.y)),
        )
        rawOffset = o
    }

    private companion object {
        val MIN = DpSize(160.dp, 90.dp)
    }
}

private fun Dp.coerceIn(low: Dp, high: Dp) = min(max(this, low), high)

/** Places a composable in the picture-in-picture window. The map and the video both use it, whichever is small. */
fun Modifier.pip(state: PipState): Modifier = offset { state.offset.let { IntOffset(it.x.roundToPx(), it.y.roundToPx()) } }
    .size(state.size)

/**
 * The window's controls, drawn over whichever of video and map is small: tap the window to swap the two, drag its top
 * bar to move it, drag the bottom-right corner to resize it, and the bar's buttons for statistics and hiding.
 * Tapping swaps rather than passing the tap on, so a small map can't be panned by accident.
 */
@Composable
fun PipControls(state: PipState, onSwap: () -> Unit, onToggleStats: () -> Unit, onHide: () -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    fun Offset.toDp() = with(density) { DpOffset(x.toDp(), y.toDp()) }
    Box(
        modifier.pip(state)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
            .clickable(onClickLabel = "Swap video and map", onClick = onSwap),
    ) {
        MapCard(
            Modifier.fillMaxWidth().pointerInput(state) { detectDragGestures { change, drag -> change.consume(); state.moveBy(drag.toDp()) } },
            padding = false,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TooltipIconButton(KftIcons.Swap, "Swap video and map", onSwap)
                TooltipIconButton(KftIcons.Stats, "Frame rate and latency", onToggleStats)
                TooltipIconButton(KftIcons.Close, "Hide video", onHide)
            }
        }
        Surface(
            Modifier.align(Alignment.BottomEnd).size(28.dp)
                .pointerInput(state) { detectDragGestures { change, drag -> change.consume(); state.resizeBy(drag.toDp()) } },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
        ) {
            // open_in_full points to the top right; the handle sits bottom right, so it's turned a quarter.
            Icon(KftIcons.Resize, contentDescription = "Resize", Modifier.fillMaxSize().rotate(90f))
        }
    }
}
