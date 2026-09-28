package com.kft.gcs.feature.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kft.gcs.core.planning.Camera
import com.kft.gcs.core.planning.CameraOrientation
import com.kft.gcs.ui.design.ConfirmDialog
import com.kft.gcs.ui.design.KftIcons
import com.kft.gcs.ui.design.KftTheme
import com.kft.gcs.ui.design.KftToolbar
import com.kft.gcs.ui.design.MinTouchTarget
import com.kft.gcs.ui.design.NumberField
import com.kft.gcs.ui.design.SectionHeader
import com.kft.gcs.ui.design.SegmentedChoice
import com.kft.gcs.ui.design.Spacing
import com.kft.gcs.ui.design.Status
import com.kft.gcs.ui.design.StatusChip
import com.kft.gcs.ui.design.ToolbarEntry
import com.kft.gcs.ui.design.TooltipIconButton
import kotlin.math.roundToLong

/**
 * What the Plan screen can ask for. [PlanViewModel] implements it; a preview can pass an object that does nothing.
 * The map's own events (click, marker click, drag) go straight from `App()` to the ViewModel and aren't in here.
 */
interface PlanActions {
    fun onAddWaypointsClicked()
    fun onAddSurveyClicked()
    fun onGroupSelected(index: Int)
    fun onGroupRenamed(index: Int, name: String)
    fun onGroupDeleted(index: Int)
    fun onRowSelected(index: Int)
    fun onDeleteClicked(index: Int)
    fun onAltitudeChanged(text: String)
    fun onSpeedChanged(text: String)
    fun onDeleteCornerClicked()
    fun onSurveyNumberChanged(field: SurveyField, value: Double)
    fun onHeightModeSelected(mode: HeightMode)
    fun onCameraSelected(name: String)
    fun onOrientationSelected(orientation: CameraOrientation)
    fun onReturnHomeChanged(on: Boolean)
    fun onCameraSaved(camera: Camera)
    fun onCameraDeleted(name: String)
    fun onBatteryMinutesChanged(minutes: Double?)
    fun onMaxGsdChanged(cm: Double?)
    fun onUndoClicked()
    fun onRedoClicked()
    fun onUploadClicked()
    fun onPreviewDismissed()
    fun onUploadConfirmed()
    fun onReadClicked()
    fun onClearClicked()
    fun onCancelTransferClicked()
    fun onSaveClicked()
    fun onExportClicked(format: ExportFormat)
    fun onOpenClicked()
    fun onImportClicked()
    fun onImportAsSurveys()
    fun onImportAsWaypoints()
    fun onImportDismissed()
}

/**
 * Collects the ViewModel's state and snackbar messages and hands them to [PlanScreen]. The ViewModel is passed in by
 * `App()`, which also feeds the same ViewModel's overlays and map callbacks to the one shared map (ADR-001 F10).
 */
@Composable
fun PlanRoute(viewModel: PlanViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.effects.collect { snackbar.showSnackbar(it) } }
    Box(Modifier.fillMaxSize()) {
        PlanScreen(state, viewModel)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * Stateless, map-first: a floating toolbar top-left and the mission panel on the right, over the map `App()` draws
 * underneath. The rest of the screen is empty, so clicks reach the map. The same layout serves desktop and tablet
 * (a phone layout is P1). No flight-action buttons (spec S9).
 *
 * The panel scrolls, except a survey's stats, which are pinned to its bottom: they're what the operator checks after
 * every change (photos, time, batteries), so they must stay in view while the settings above scroll.
 */
@Composable
fun PlanScreen(state: PlanUiState, actions: PlanActions) {
    var confirmClear by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Toolbar(
            state, actions, onClear = { confirmClear = true },
            Modifier.align(Alignment.TopStart).padding(Spacing.m).padding(end = PANEL_WIDTH + Spacing.xl),
        )
        // A Surface, not a plain background: M3 Surface blocks pointer events, so a click on the panel can't fall
        // through to the map underneath and add a waypoint or a corner there.
        Surface(
            Modifier.align(Alignment.TopEnd).padding(Spacing.m).width(PANEL_WIDTH).fillMaxHeight(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 4.dp,
        ) {
            Column {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Spacing.m),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    Text("Mission", style = MaterialTheme.typography.titleMedium)
                    Text(state.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.transfer?.let { progress ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(progress, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                            TextButton(onClick = actions::onCancelTransferClicked) { Text("Cancel") }
                        }
                    }
                    GroupList(state.groups, actions)
                    HorizontalDivider()
                    state.survey?.let { SurveyEditor(it, state.groups.firstOrNull { g -> g.selected }?.index ?: 0, actions) }
                        ?: WaypointEditor(state, actions)
                }
                state.survey?.stats?.takeIf { it.isNotEmpty() }?.let { StatsFooter(it) }
            }
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "Clear the mission?",
            text = state.clearWarning,
            confirmLabel = "Clear",
            onConfirm = { confirmClear = false; actions.onClearClicked() },
            onDismiss = { confirmClear = false },
            dismissLabel = "Keep",
            destructive = true,
        )
    }
    state.preview?.let { UploadPreviewDialog(it, actions) }
    state.importDialog?.let { ImportAsDialog(it, actions) }
}

/**
 * One row, in groups: plan edits | undo, redo | files | the vehicle. Files and the vehicle use different icons
 * (folder / save / export against download / upload), so "save to a file" and "send to the aircraft" never look alike.
 * The vehicle's sync state sits beside the toolbar as a chip. If the window is narrow, the vehicle group is the first
 * to move into the "more" menu (KftToolbar keeps the order as priority).
 */
@Composable
private fun Toolbar(state: PlanUiState, actions: PlanActions, onClear: () -> Unit, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        KftToolbar(
            listOf(
                ToolbarEntry.Action(KftIcons.AddWaypoint, "Add waypoints", onClick = actions::onAddWaypointsClicked),
                ToolbarEntry.Action(KftIcons.AddSurvey, "Add survey", onClick = actions::onAddSurveyClicked),
                ToolbarEntry.Divider,
                ToolbarEntry.Action(KftIcons.Undo, "Undo (Ctrl+Z)", state.canUndo, actions::onUndoClicked),
                ToolbarEntry.Action(KftIcons.Redo, "Redo (Ctrl+Shift+Z)", state.canRedo, actions::onRedoClicked),
                ToolbarEntry.Divider,
                ToolbarEntry.Action(KftIcons.Open, "Open plan file", onClick = actions::onOpenClicked),
                ToolbarEntry.Action(KftIcons.Import, "Import area or points (KML, KMZ, GeoJSON, shapefile, CSV)", onClick = actions::onImportClicked),
                ToolbarEntry.Action(KftIcons.Save, "Save plan file", onClick = actions::onSaveClicked),
                ToolbarEntry.Menu(
                    KftIcons.Export, "Export",
                    listOf(
                        "QGC .plan (plain items)" to { actions.onExportClicked(ExportFormat.QGC_PLAN) },
                        "Mission Planner .waypoints" to { actions.onExportClicked(ExportFormat.WAYPOINTS) },
                    ),
                ),
                ToolbarEntry.Divider,
                ToolbarEntry.Action(KftIcons.Download, "Read mission from vehicle", state.canRead, actions::onReadClicked),
                ToolbarEntry.Action(KftIcons.Upload, "Upload mission to vehicle", state.canUpload, actions::onUploadClicked),
                ToolbarEntry.Action(KftIcons.Delete, "Clear mission on vehicle", state.canClear, onClear),
            ),
            Modifier.weight(1f, fill = false),
        )
        state.sync?.let {
            StatusChip(it.text, if (it.warning) Status.WARN else Status.OK, icon = if (it.warning) KftIcons.SyncProblem else KftIcons.Sync)
        }
    }
}

@Composable
private fun GroupList(groups: List<GroupHeader>, actions: PlanActions) {
    var renaming by remember { mutableStateOf<GroupHeader?>(null) }
    groups.forEach { g ->
        Row(
            Modifier.fillMaxWidth().selectedRow(g.selected).clickable { actions.onGroupSelected(g.index) }
                .padding(start = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(g.name, style = MaterialTheme.typography.titleSmall)
                Text("${g.kind} · ${g.summary}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            TooltipIconButton(KftIcons.Edit, "Rename", { renaming = g })
            TooltipIconButton(KftIcons.Close, "Delete group", { actions.onGroupDeleted(g.index) })
        }
    }
    renaming?.let { g ->
        var name by remember(g) { mutableStateOf(g.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename group") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { renaming = null; actions.onGroupRenamed(g.index, name) }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

/** The Pass 8 editor for the selected waypoint group: rows, then the selected row's altitude and speed. */
@Composable
private fun WaypointEditor(state: PlanUiState, actions: PlanActions) {
    state.rows.forEach { row ->
        Row(
            Modifier.fillMaxWidth().selectedRow(row.selected).clickable { actions.onRowSelected(row.index) }.padding(start = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("${row.seq}  ${row.title}", style = MaterialTheme.typography.bodyMedium)
                Text(row.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            TooltipIconButton(KftIcons.Close, "Delete item", { actions.onDeleteClicked(row.index) })
        }
    }
    state.form?.let { form ->
        SectionHeader("${form.title} (item ${state.rows.getOrNull(form.index)?.seq ?: ""})")
        NumberField("Altitude above home", form.altitude, actions::onAltitudeChanged, "m", Modifier.fillMaxWidth())
        NumberField("Speed from here (blank = keep)", form.speed, actions::onSpeedChanged, "m/s", Modifier.fillMaxWidth())
        form.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * The survey panel, in collapsible sections in the order an operator decides things: camera, height, overlaps and
 * angle, flight, start corner (chosen on the map), then the rarely touched battery and GSD limits (collapsed at first). Problems come
 * first, above the sections, so they're seen without scrolling. The stats are the panel's pinned footer.
 */
@Composable
private fun SurveyEditor(panel: SurveyPanel, group: Int, actions: PlanActions) {
    val s = panel.settings
    panel.error?.let { WarningLine(it) }
    panel.warnings.forEach { WarningLine(it) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${panel.cornerCount} corners", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = actions::onDeleteCornerClicked, enabled = panel.cornerSelected) { Text("Delete selected corner") }
    }

    Section("Camera") { CameraSection(panel, actions) }
    Section("Altitude & GSD") {
        SegmentedChoice(listOf("Set altitude" to HeightMode.ALTITUDE, "Set GSD" to HeightMode.GSD), s.heightMode, actions::onHeightModeSelected, Modifier.fillMaxWidth())
        if (s.heightMode == HeightMode.ALTITUDE) {
            SurveyNumber("Altitude above home", "m", s.altitudeM, SurveyField.ALTITUDE, group, actions, Modifier.fillMaxWidth())
            panel.gsdCm?.let { Readout("GSD", "${oneDecimalText(it)} cm/px") }
        } else {
            SurveyNumber("GSD", "cm/px", s.gsdCm, SurveyField.GSD, group, actions, Modifier.fillMaxWidth())
            panel.altitudeM?.let { Readout("Altitude above home", "${oneDecimalText(it)} m") }
        }
    }
    Section("Overlap & angle") {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            SurveyNumber("Side overlap", "%", s.sideOverlapPct, SurveyField.SIDE_OVERLAP, group, actions, Modifier.weight(1f))
            SurveyNumber("Front overlap", "%", s.frontOverlapPct, SurveyField.FRONT_OVERLAP, group, actions, Modifier.weight(1f))
        }
        SurveyNumber("Grid angle", "°", s.gridAngleDeg, SurveyField.GRID_ANGLE, group, actions, Modifier.fillMaxWidth())
    }
    Section("Flight") {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            SurveyNumber("Speed", "m/s", s.speedMs, SurveyField.SPEED, group, actions, Modifier.weight(1f))
            SurveyNumber(if (panel.isPlane) "Lead-in" else "Run-in / out", "m", s.turnaroundM, SurveyField.TURNAROUND, group, actions, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clickable { actions.onReturnHomeChanged(!s.returnHome) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(s.returnHome, actions::onReturnHomeChanged)
            Text("Return to launch at the end (mission item)", style = MaterialTheme.typography.bodySmall)
        }
    }
    // Chosen on the map (the corners of the area); the panel only says which one it is.
    Section("Start corner") {
        Text(
            panel.startCorner?.let { "Starts at the $it corner (highlighted on the map). Tap a grey dot at another corner to start there." }
                ?: "Add the area's corners first.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Section("Battery & limits (kept between runs)", startExpanded = false) {
        OptionalNumberField(if (panel.isPlane) "Plane battery, usable" else "Copter battery, usable", "min", panel.batteryMinutes, actions::onBatteryMinutesChanged, Modifier.fillMaxWidth())
        OptionalNumberField("Warn above GSD", "cm/px", panel.maxGsdCm, actions::onMaxGsdChanged, Modifier.fillMaxWidth())
    }
}

@Composable
private fun CameraSection(panel: SurveyPanel, actions: PlanActions) {
    val s = panel.settings
    var cameraMenu by remember { mutableStateOf(false) }
    var addingCamera by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { cameraMenu = true }, Modifier.fillMaxWidth()) {
            Text(s.camera.name.ifEmpty { "Camera" }, Modifier.weight(1f))
            Icon(KftIcons.ExpandMore, contentDescription = "Choose camera")
        }
        DropdownMenu(cameraMenu, onDismissRequest = { cameraMenu = false }) {
            panel.cameras.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.name + if (c.unverified) "  (unverified)" else if (c.custom) "  (custom)" else "") },
                    onClick = { cameraMenu = false; actions.onCameraSelected(c.name) },
                )
            }
            DropdownMenuItem(
                text = { Text("Custom camera…") },
                leadingIcon = { Icon(KftIcons.Add, contentDescription = null) },
                onClick = { cameraMenu = false; addingCamera = true },
            )
        }
    }
    if (s.camera.unverified) WarningLine("Preset not yet checked against the maker's spec sheet.")
    if (panel.cameras.any { it.custom && it.name == s.camera.name }) {
        TextButton(onClick = { actions.onCameraDeleted(s.camera.name) }) { Text("Delete this custom camera") }
    }
    SegmentedChoice(
        listOf("Landscape" to CameraOrientation.LANDSCAPE, "Portrait" to CameraOrientation.PORTRAIT),
        s.orientation, actions::onOrientationSelected, Modifier.fillMaxWidth(),
    )
    if (addingCamera) CustomCameraDialog(onSave = { addingCamera = false; actions.onCameraSaved(it) }, onDismiss = { addingCamera = false })
}

/**
 * A collapsible panel section. Each keeps its own open/closed state for as long as the panel is shown; that's a
 * view preference, not plan data, so it isn't in the ViewModel or the undo history.
 */
@Composable
private fun Section(title: String, startExpanded: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember(title) { mutableStateOf(startExpanded) }
    SectionHeader(title, expanded = expanded, onToggle = { expanded = !expanded })
    if (expanded) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.s), content = content)
    HorizontalDivider()
}

/** "GSD 1.4 cm/px": a value the plan computed, shown under the one the operator typed. */
@Composable
private fun Readout(label: String, value: String) {
    Row {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A warning in the panel: the warning icon (not only the colour) and the text. */
@Composable
private fun WarningLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Icon(KftIcons.Warn, contentDescription = "Warning", Modifier.size(18.dp), tint = KftTheme.status.warn)
        Text(text, color = KftTheme.status.warn, style = MaterialTheme.typography.bodySmall)
    }
}

/** The survey's numbers, pinned under the scrolling settings: three columns of label over value, compact enough
 * to leave the settings room. */
@Composable
private fun StatsFooter(stats: List<Pair<String, String>>) {
    HorizontalDivider()
    FlowRow(
        Modifier.fillMaxWidth().padding(Spacing.m),
        maxItemsInEachRow = 3,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        stats.forEach { (label, value) ->
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * A survey number. The field keeps the operator's own text while they type ("1" on the way to "12", "5."), sends only
 * values in the field's range, and says why otherwise. When the value changes from outside (undo, another group), the
 * text follows, unless it already means that value.
 */
@Composable
private fun SurveyNumber(label: String, unit: String, value: Double, field: SurveyField, group: Int, actions: PlanActions, modifier: Modifier = Modifier) {
    var text by remember(group, field) { mutableStateOf(numberText(value)) }
    LaunchedEffect(value) { if (text.trim().toDoubleOrNull() != value) text = numberText(value) }
    val parsed = text.trim().toDoubleOrNull()
    val error = when {
        parsed == null -> "a number, please"
        parsed !in field.range -> "${numberText(field.range.start)} to ${numberText(field.range.endInclusive)}"
        else -> null
    }
    NumberField(
        label, text, { t -> text = t; t.trim().toDoubleOrNull()?.let { actions.onSurveyNumberChanged(field, it) } },
        unit, modifier, error,
    )
}

/** A setting that may be unset: blank = null. */
@Composable
private fun OptionalNumberField(label: String, unit: String, value: Double?, onValue: (Double?) -> Unit, modifier: Modifier) {
    var text by remember { mutableStateOf(value?.let(::numberText) ?: "") }
    LaunchedEffect(value) { if (text.trim().toDoubleOrNull() != value) text = value?.let(::numberText) ?: "" }
    NumberField(
        label, text,
        { t ->
            text = t
            if (t.isBlank()) onValue(null) else t.trim().toDoubleOrNull()?.let(onValue)
        },
        unit, modifier,
    )
}

/** Whole numbers without ".0", others to two decimals: 70, 8.5, 2.74. */
private fun numberText(v: Double): String =
    if (v == v.roundToLong().toDouble()) v.roundToLong().toString() else ((v * 100).roundToLong() / 100.0).toString()

private val PANEL_WIDTH = 360.dp

/** The fields a camera needs. Save is enabled only when all parse and the camera's own checks accept them. */
@Composable
private fun CustomCameraDialog(onSave: (Camera) -> Unit, onDismiss: () -> Unit) {
    val labels = listOf(
        "Name", "Sensor width (mm)", "Sensor height (mm)", "Image width (px)", "Image height (px)",
        "Focal length (mm, real, not 35 mm equivalent)", "Minimum time between photos (s)", "MB per photo",
    )
    var values by remember { mutableStateOf(List(labels.size) { "" }) }
    val camera = runCatching {
        Camera(
            values[1].toDouble(), values[2].toDouble(), values[3].toInt(), values[4].toInt(), values[5].toDouble(),
            values[6].ifBlank { "0" }.toDouble(), values[7].ifBlank { "0" }.toDouble(), values[0].trim(),
        ).takeIf { it.name.isNotEmpty() }
    }.getOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom camera") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                labels.forEachIndexed { i, label ->
                    OutlinedTextField(values[i], { v -> values = values.mapIndexed { k, old -> if (k == i) v else old } }, label = { Text(label) }, singleLine = true)
                }
            }
        },
        confirmButton = { TextButton(onClick = { camera?.let(onSave) }, enabled = camera != null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Every item the upload will send, then the warnings, then confirm. Nothing is sent before "Upload". */
/** Survey areas or waypoints: the same file can be either, so the operator says which. */
@Composable
private fun ImportAsDialog(dialog: ImportDialog, actions: PlanActions) {
    AlertDialog(
        onDismissRequest = actions::onImportDismissed,
        title = { Text(dialog.title) },
        text = {
            Text(
                "It holds ${dialog.summary}. Add " + (if (dialog.canSurvey) "each area as a survey, or " else "") +
                    "the points and lines as waypoints? Waypoints get the default altitude above home; altitudes in the file aren't used.",
            )
        },
        confirmButton = {
            Row {
                if (dialog.canSurvey) TextButton(onClick = actions::onImportAsSurveys) { Text("Survey areas") }
                TextButton(onClick = actions::onImportAsWaypoints) { Text("Waypoints") }
            }
        },
        dismissButton = { TextButton(onClick = actions::onImportDismissed) { Text("Cancel") } },
    )
}

@Composable
private fun UploadPreviewDialog(preview: UploadPreview, actions: PlanActions) {
    AlertDialog(
        onDismissRequest = actions::onPreviewDismissed,
        title = { Text("Upload ${preview.lines.size - 1} items?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                preview.warnings.forEach { WarningLine(it) }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(preview.lines) { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { TextButton(onClick = actions::onUploadConfirmed) { Text("Upload") } },
        dismissButton = { TextButton(onClick = actions::onPreviewDismissed) { Text("Cancel") } },
    )
}


/**
 * The selected group or waypoint row: a slightly raised fill that the normal text colour reads on in every theme,
 * plus a primary outline, so the selection shows even where the fill difference is small (light theme, sunlight).
 */
@Composable
private fun Modifier.selectedRow(selected: Boolean): Modifier =
    if (!selected) this else background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
        .border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
