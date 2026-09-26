package com.kft.gcs.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One thing on a [KftToolbar]. */
sealed interface ToolbarEntry {
    /** An icon button; [label] is its tooltip, and its text when it moves into the overflow menu. */
    data class Action(val icon: ImageVector, val label: String, val enabled: Boolean = true, val onClick: () -> Unit) : ToolbarEntry

    /** An icon button that opens a menu of [items] (label to action), e.g. Export → the file formats. */
    data class Menu(val icon: ImageVector, val label: String, val items: List<Pair<String, () -> Unit>>, val enabled: Boolean = true) : ToolbarEntry

    /** A thin line between groups of actions. */
    data object Divider : ToolbarEntry
}

/**
 * A floating single-row toolbar of icon buttons with tooltips. If the row doesn't fit (a narrow window, a tablet in
 * portrait), the entries from the end move into a "more" menu, where they're listed with their names. So the order
 * of [entries] is also their priority: put what's used most first.
 */
@Composable
fun KftToolbar(entries: List<ToolbarEntry>, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 4.dp) {
        BoxWithConstraints(Modifier.padding(horizontal = Spacing.xs)) {
            val (shown, overflow) = splitForWidth(entries, maxWidth)
            Row(verticalAlignment = Alignment.CenterVertically) {
                shown.forEach { ToolbarItem(it) }
                if (overflow.isNotEmpty()) OverflowMenu(overflow)
            }
        }
    }
}

@Composable
private fun ToolbarItem(entry: ToolbarEntry) {
    when (entry) {
        is ToolbarEntry.Action -> TooltipIconButton(entry.icon, entry.label, entry.onClick, enabled = entry.enabled)
        is ToolbarEntry.Menu -> {
            var open by remember { mutableStateOf(false) }
            Box {
                TooltipIconButton(entry.icon, entry.label, { open = true }, enabled = entry.enabled)
                DropdownMenu(open, onDismissRequest = { open = false }) {
                    entry.items.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() }) }
                }
            }
        }
        ToolbarEntry.Divider -> VerticalDivider(Modifier.height(24.dp).padding(horizontal = DIVIDER_GAP))
    }
}

@Composable
private fun OverflowMenu(entries: List<ToolbarEntry>) {
    var open by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton(KftIcons.More, "More", { open = true })
        DropdownMenu(open, onDismissRequest = { open = false }) {
            entries.forEach { entry ->
                when (entry) {
                    is ToolbarEntry.Action -> DropdownMenuItem(
                        text = { Text(entry.label) },
                        leadingIcon = { Icon(entry.icon, contentDescription = null) },
                        enabled = entry.enabled,
                        onClick = { open = false; entry.onClick() },
                    )
                    // A sub-menu inside a menu is fiddly on a touch screen, so its items are listed flat: "Export: …".
                    is ToolbarEntry.Menu -> entry.items.forEach { (label, action) ->
                        DropdownMenuItem(
                            text = { Text("${entry.label}: $label") },
                            leadingIcon = { Icon(entry.icon, contentDescription = null) },
                            enabled = entry.enabled,
                            onClick = { open = false; action() },
                        )
                    }
                    ToolbarEntry.Divider -> Unit
                }
            }
        }
    }
}

/**
 * Which entries fit in [available] width, and which go to the overflow menu. Buttons are [MinTouchTarget] wide and
 * dividers [DIVIDER_WIDTH]. When anything overflows, room is kept for the "more" button itself. Dividers never
 * start or end the visible row and never go into the menu.
 */
internal fun splitForWidth(entries: List<ToolbarEntry>, available: Dp): Pair<List<ToolbarEntry>, List<ToolbarEntry>> {
    fun width(e: ToolbarEntry) = if (e is ToolbarEntry.Divider) DIVIDER_WIDTH.value else MinTouchTarget.value
    if (entries.sumOf { width(it).toDouble() } <= available.value) return entries to emptyList()
    var used = MinTouchTarget.value
    val shown = entries.takeWhile { used += width(it); used <= available.value }
    return shown.dropLastWhile { it is ToolbarEntry.Divider } to entries.drop(shown.size).filter { it !is ToolbarEntry.Divider }
}

private val DIVIDER_GAP = Spacing.s
internal val DIVIDER_WIDTH = 1.dp + DIVIDER_GAP * 2
