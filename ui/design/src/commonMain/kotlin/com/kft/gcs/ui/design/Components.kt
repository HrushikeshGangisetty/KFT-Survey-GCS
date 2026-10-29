package com.kft.gcs.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What a status chip, a stat tile or a message means. Drives colour **and** icon, never colour alone. */
enum class Status { NEUTRAL, OK, WARN, CRITICAL }

/** One destination in [KftNavigationRail]. */
data class RailItem(val label: String, val icon: ImageVector, val selected: Boolean, val onClick: () -> Unit)

/**
 * The app's left rail: logo on top, destinations as icon + label (labels always shown, because a field operator
 * shouldn't have to guess an icon), and [footer] pinned to the bottom for app-wide actions (theme, About).
 */
@Composable
fun KftNavigationRail(
    items: List<RailItem>,
    modifier: Modifier = Modifier,
    extra: @Composable ColumnScope.() -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    // The darkest surface, with a hairline on its right edge: the rail is the frame around the display, not a panel.
    Row(modifier) {
        NavigationRail(
            Modifier.fillMaxHeight(),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            header = { KftLogo(Modifier.width(52.dp).padding(vertical = Spacing.m)) },
        ) {
            items.forEach { item ->
                NavigationRailItem(
                    selected = item.selected,
                    onClick = item.onClick,
                    icon = { Icon(item.icon, contentDescription = null) },
                    label = { Text(item.label.uppercase(), style = MaterialTheme.typography.instrumentLabel) },
                )
            }
            // Under the tabs: things that apply to every tab, today the vehicle switcher.
            extra()
            Spacer(Modifier.weight(1f))
            footer()
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** One vehicle in [VehicleSwitcher]. [color] is the vehicle's colour on the map, so the two always match. */
data class VehicleChip(val label: String, val color: Color, val selected: Boolean, val connected: Boolean, val onClick: () -> Unit)

/**
 * The vehicle switcher: one round button per vehicle, in its map colour, with its label. The selected one has a ring
 * (shape, not colour alone, says which); one whose heartbeat is lost is faded. Lives in the rail because the selection
 * applies to every tab: Fly's HUD, Plan's transfers and Params all act on the selected vehicle.
 */
@Composable
fun VehicleSwitcher(vehicles: List<VehicleChip>, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.xs), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Vehicle", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        vehicles.forEach { v ->
            Tooltip("Vehicle ${v.label}" + (if (v.selected) " (selected)" else "") + (if (v.connected) "" else " · heartbeat lost"), Modifier) {
                Surface(
                    onClick = v.onClick,
                    modifier = Modifier.size(MinTouchTarget).padding(Spacing.xs).semantics { selected = v.selected },
                    shape = CircleShape,
                    color = v.color.copy(alpha = if (v.connected) 1f else 0.4f),
                    border = if (v.selected) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else null,
                ) {
                    // Black on every vehicle colour: they are all light, saturated map colours (ui:map VehicleColors).
                    Box(contentAlignment = Alignment.Center) { Text(v.label, style = MaterialTheme.typography.labelLarge, color = Color.Black) }
                }
            }
        }
    }
}

/**
 * A card floating over the map (HUD, map buttons, panels). Opaque on purpose: text over a half-transparent card
 * changes contrast with whatever map is underneath, and satellite imagery can make it unreadable. M3 Surface also
 * swallows clicks, so a tap on the card never reaches the map below it. A hairline outline and a small shadow set it
 * off the map ([panelBorder]), the way instrument panes are framed.
 */
@Composable
fun MapCard(modifier: Modifier = Modifier, padding: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, border = panelBorder(), shadowElevation = 2.dp) {
        Column(if (padding) Modifier.padding(Spacing.m) else Modifier, content = content)
    }
}

/** The 1 dp outline around every floating card, toolbar and side panel, so they all look like one family. */
@Composable
fun panelBorder() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

/**
 * An icon button with its name in a tooltip (hover on desktop, long-press on the tablet). Every icon-only button
 * goes through this, so none is ever a mystery. The button itself is 48 dp ([MinTouchTarget]).
 */
@Composable
fun TooltipIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Tooltip(label, modifier) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = label) }
    }
}

/**
 * [label] as a tooltip over [content]. Per platform because M3's TooltipBox (material3 1.9.0) never opens on mouse
 * hover on desktop, found on the Pass 19a screenshots: desktop uses Compose Desktop's `TooltipArea`, which does, and
 * Android keeps M3's long-press tooltip.
 */
@Composable
internal expect fun Tooltip(label: String, modifier: Modifier, content: @Composable () -> Unit)

/**
 * A section title in a panel: a short accent bar and the title in tracked-out letters, so sections scan like the
 * pages of an instrument. Not capitals, unlike [StatTile] labels: some titles are sentences ("FC (MAVLink, from the
 * flight controller)") and read badly shouted. With [onToggle] it becomes a collapse control: the whole row is the
 * touch target (at least 48 dp tall), and a chevron shows the state.
 */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, expanded: Boolean = true, onToggle: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().heightIn(min = if (onToggle != null) MinTouchTarget else 0.dp)
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 3.dp, height = 14.dp).background(MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(Spacing.s))
        Text(
            title, Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 0.5.sp),
            color = MaterialTheme.colorScheme.primary,
        )
        if (onToggle != null) Icon(if (expanded) KftIcons.ExpandLess else KftIcons.ExpandMore, contentDescription = if (expanded) "Collapse" else "Expand")
    }
}

/**
 * A number with its unit written after it ("70 %", "120 m"), so labels don't have to carry units in brackets.
 * [error] turns the field red and says why underneath. Holds no state: the caller keeps the text, because only it
 * knows what counts as valid.
 */
@Composable
fun NumberField(label: String, value: String, onValueChange: (String) -> Unit, unit: String, modifier: Modifier = Modifier, error: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        suffix = { Text(unit) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

/** One choice out of a few (landscape/portrait, altitude/GSD): M3 segmented buttons with our check icon. */
@Composable
fun <T> SegmentedChoice(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier) {
        options.forEachIndexed { i, (label, value) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = { if (value == selected) Icon(KftIcons.Check, contentDescription = null, Modifier.size(18.dp)) },
                label = { Text(label) },
            )
        }
    }
}

/**
 * A short status ("Link OK", "GPS 3D · 14"), readable over any map. Colour and icon both come from [status]:
 * OK = outlined + check, WARN = tinted + triangle, CRITICAL = filled solid + octagon, NEUTRAL = plain outline.
 * The text stays in the normal text colour except on the solid critical fill, so it's always easy to read.
 */
@Composable
fun StatusChip(text: String, status: Status, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = KftTheme.status
    val base = MaterialTheme.colorScheme.surfaceContainer
    val onBase = MaterialTheme.colorScheme.onSurface
    val accent = when (status) {
        Status.OK -> c.ok
        Status.WARN -> c.warn
        Status.CRITICAL -> c.critical
        Status.NEUTRAL -> MaterialTheme.colorScheme.outline
    }
    val (background, content) = when (status) {
        Status.CRITICAL -> c.critical to c.onCritical
        Status.WARN -> c.warn.copy(alpha = 0.16f).compositeOver(base) to onBase
        else -> base to onBase
    }
    Surface(modifier, shape = MaterialTheme.shapes.small, color = background, border = BorderStroke(1.dp, accent), shadowElevation = 1.dp) {
        Row(Modifier.heightIn(min = 32.dp).padding(horizontal = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon ?: statusIcon(status), contentDescription = null, Modifier.size(18.dp), tint = if (status == Status.CRITICAL) content else accent)
            Spacer(Modifier.width(Spacing.xs + 2.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = content)
        }
    }
}

/**
 * One HUD number: a small label over a big tabular value ("Alt" / "12.3 m"). A warn/critical value changes colour
 * and gets the status icon beside its label, so it's noticed without relying on colour.
 */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, status: Status = Status.NEUTRAL) {
    val c = KftTheme.status
    val color = when (status) {
        Status.WARN -> c.warn
        Status.CRITICAL -> c.critical
        else -> MaterialTheme.colorScheme.onSurface
    }
    // One minimum width for every tile, so the columns line up and a value going from "–" to "12.3 m" barely moves.
    Column(modifier.widthIn(min = 88.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(label.uppercase(), style = MaterialTheme.typography.instrumentLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (status == Status.WARN || status == Status.CRITICAL) Icon(statusIcon(status), contentDescription = null, Modifier.size(14.dp), tint = color)
        }
        Text(value, style = MaterialTheme.typography.hudValue, color = color, maxLines = 1)
    }
}

/**
 * "Are you sure?" before anything that can't be undone or that talks to the aircraft. [destructive] colours the
 * confirm button critical (Clear, Delete). The dismiss button is always there and always says what it keeps.
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Cancel",
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = KftTheme.status.critical) else ButtonDefaults.textButtonColors(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

@Composable
internal fun statusIcon(status: Status): ImageVector = when (status) {
    Status.OK -> KftIcons.Ok
    Status.WARN -> KftIcons.Warn
    Status.CRITICAL -> KftIcons.Critical
    Status.NEUTRAL -> KftIcons.About
}
