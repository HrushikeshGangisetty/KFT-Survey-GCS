package com.kft.gcs.feature.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kft.gcs.core.planning.Camera
import com.kft.gcs.core.planning.CameraOrientation
import com.kft.gcs.core.planning.EntryCorner
import com.kft.gcs.ui.design.ConfirmDialog
import com.kft.gcs.ui.design.KftIcons
import com.kft.gcs.ui.design.KftTheme
import com.kft.gcs.ui.design.KftToolbar
import com.kft.gcs.ui.design.Spacing
import com.kft.gcs.ui.design.Status
import com.kft.gcs.ui.design.StatusChip
import com.kft.gcs.ui.design.ToolbarEntry
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
    fun onEntrySelected(entry: EntryCorner)
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
 */
@Composable
fun PlanScreen(state: PlanUiState, actions: PlanActions) {
    var confirmClear by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Toolbar(state, actions, onClear = { confirmClear = true }, Modifier.align(Alignment.TopStart).padding(12.dp).padding(end = 372.dp))
        // A Surface, not a plain background: M3 Surface blocks pointer events, so a click on the panel can't fall
        // through to the map underneath and add a waypoint or a corner there.
        Surface(
            Modifier.align(Alignment.TopEnd).padding(12.dp).width(348.dp).fillMaxHeight(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Modifier.fillMaxWidth().clickable { actions.onGroupSelected(g.index) }
                .selectedRow(g.selected)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(g.name, fontWeight = FontWeight.Bold)
                Text("${g.kind} · ${g.summary}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { renaming = g }) { Text("✎") }
            TextButton(onClick = { actions.onGroupDeleted(g.index) }) { Text("✕") }
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
            Modifier.fillMaxWidth().clickable { actions.onRowSelected(row.index) }
                .selectedRow(row.selected)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("${row.seq}  ${row.title}", style = MaterialTheme.typography.bodyMedium)
                Text(row.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { actions.onDeleteClicked(row.index) }) { Text("✕") }
        }
    }
    state.form?.let { form ->
        Text("${form.title} (item ${state.rows.getOrNull(form.index)?.seq ?: ""})")
        OutlinedTextField(
            value = form.altitude, onValueChange = actions::onAltitudeChanged, singleLine = true,
            label = { Text("Altitude above home (m)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        OutlinedTextField(
            value = form.speed, onValueChange = actions::onSpeedChanged, singleLine = true,
            label = { Text("Speed from here (m/s, blank = keep)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        form.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/** The survey panel: camera, height, overlaps, angle, entry, speed, turns, then live stats and warnings. */
@Composable
private fun SurveyEditor(panel: SurveyPanel, group: Int, actions: PlanActions) {
    val s = panel.settings
    var cameraMenu by remember { mutableStateOf(false) }
    var addingCamera by remember { mutableStateOf(false) }

    Text("Camera", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    Box {
        OutlinedButton(onClick = { cameraMenu = true }, Modifier.fillMaxWidth()) { Text("${s.camera.name.ifEmpty { "Camera" }} ▾") }
        DropdownMenu(cameraMenu, onDismissRequest = { cameraMenu = false }) {
            panel.cameras.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.name + if (c.unverified) "  (unverified)" else if (c.custom) "  (custom)" else "") },
                    onClick = { cameraMenu = false; actions.onCameraSelected(c.name) },
                )
            }
            DropdownMenuItem(text = { Text("+ Custom camera…") }, onClick = { cameraMenu = false; addingCamera = true })
        }
    }
    if (s.camera.unverified) Text("Preset not yet checked against the maker's spec sheet.", color = KftTheme.status.warn, style = MaterialTheme.typography.labelSmall)
    if (panel.cameras.any { it.custom && it.name == s.camera.name }) {
        TextButton(onClick = { actions.onCameraDeleted(s.camera.name) }) { Text("Delete this custom camera") }
    }
    Chips(listOf("Landscape" to CameraOrientation.LANDSCAPE, "Portrait" to CameraOrientation.PORTRAIT), s.orientation, actions::onOrientationSelected)

    Chips(listOf("Set altitude" to HeightMode.ALTITUDE, "Set GSD" to HeightMode.GSD), s.heightMode, actions::onHeightModeSelected)
    if (s.heightMode == HeightMode.ALTITUDE) {
        NumberField("Altitude above home (m)", s.altitudeM, SurveyField.ALTITUDE, group, actions)
        panel.gsdCm?.let { Text("GSD ${oneDecimalText(it)} cm/px") }
    } else {
        NumberField("GSD (cm/px)", s.gsdCm, SurveyField.GSD, group, actions)
        panel.altitudeM?.let { Text("Altitude ${oneDecimalText(it)} m above home") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Side overlap %", s.sideOverlapPct, SurveyField.SIDE_OVERLAP, group, actions, Modifier.weight(1f))
        NumberField("Front overlap %", s.frontOverlapPct, SurveyField.FRONT_OVERLAP, group, actions, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Grid angle °", s.gridAngleDeg, SurveyField.GRID_ANGLE, group, actions, Modifier.weight(1f))
        NumberField("Speed m/s", s.speedMs, SurveyField.SPEED, group, actions, Modifier.weight(1f))
    }
    NumberField(if (panel.isPlane) "Lead-in (m)" else "Run-in / run-out (m)", s.turnaroundM, SurveyField.TURNAROUND, group, actions)
    Text("Start corner", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    Chips(
        listOf("↙" to EntryCorner.BOTTOM_LEFT, "↘" to EntryCorner.BOTTOM_RIGHT, "↖" to EntryCorner.TOP_LEFT, "↗" to EntryCorner.TOP_RIGHT),
        s.entry, actions::onEntrySelected,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(s.returnHome, actions::onReturnHomeChanged)
        Text("Return to launch at the end (mission item)", style = MaterialTheme.typography.bodySmall)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${panel.cornerCount} corners", Modifier.weight(1f))
        TextButton(onClick = actions::onDeleteCornerClicked, enabled = panel.cornerSelected) { Text("Delete selected corner") }
    }

    panel.error?.let { Text(it, color = KftTheme.status.warn, style = MaterialTheme.typography.bodySmall) }
    panel.warnings.forEach { Text("⚠ $it", color = KftTheme.status.warn, style = MaterialTheme.typography.bodySmall) }
    panel.stats.forEach { (label, value) ->
        Row {
            Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.bodySmall)
        }
    }

    HorizontalDivider()
    Text("Settings (kept between runs)", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OptionalNumberField(if (panel.isPlane) "Plane battery, usable min" else "Copter battery, usable min", panel.batteryMinutes, actions::onBatteryMinutesChanged, Modifier.weight(1f))
        OptionalNumberField("Warn above GSD cm/px", panel.maxGsdCm, actions::onMaxGsdChanged, Modifier.weight(1f))
    }

    if (addingCamera) CustomCameraDialog(onSave = { addingCamera = false; actions.onCameraSaved(it) }, onDismiss = { addingCamera = false })
}

@Composable
private fun <T> Chips(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (label, value) -> FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) }) }
    }
}

/**
 * A survey number. The field keeps the operator's own text while they type ("1" on the way to "12", "5."), sends only
 * values in the field's range, and says why otherwise. When the value changes from outside (undo, another group), the
 * text follows, unless it already means that value.
 */
@Composable
private fun NumberField(label: String, value: Double, field: SurveyField, group: Int, actions: PlanActions, modifier: Modifier = Modifier) {
    var text by remember(group, field) { mutableStateOf(numberText(value)) }
    LaunchedEffect(value) { if (text.trim().toDoubleOrNull() != value) text = numberText(value) }
    val parsed = text.trim().toDoubleOrNull()
    val error = when {
        parsed == null -> "a number, please"
        parsed !in field.range -> "${numberText(field.range.start)} to ${numberText(field.range.endInclusive)}"
        else -> null
    }
    OutlinedTextField(
        value = text,
        onValueChange = { t -> text = t; t.trim().toDoubleOrNull()?.let { actions.onSurveyNumberChanged(field, it) } },
        modifier = modifier,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

/** A setting that may be unset: blank = null. */
@Composable
private fun OptionalNumberField(label: String, value: Double?, onValue: (Double?) -> Unit, modifier: Modifier) {
    var text by remember { mutableStateOf(value?.let(::numberText) ?: "") }
    LaunchedEffect(value) { if (text.trim().toDoubleOrNull() != value) text = value?.let(::numberText) ?: "" }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            if (t.isBlank()) onValue(null) else t.trim().toDoubleOrNull()?.let(onValue)
        },
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

/** Whole numbers without ".0", others to two decimals: 70, 8.5, 2.74. */
private fun numberText(v: Double): String =
    if (v == v.roundToLong().toDouble()) v.roundToLong().toString() else ((v * 100).roundToLong() / 100.0).toString()

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
@Composable
private fun UploadPreviewDialog(preview: UploadPreview, actions: PlanActions) {
    AlertDialog(
        onDismissRequest = actions::onPreviewDismissed,
        title = { Text("Upload ${preview.lines.size - 1} items?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                preview.warnings.forEach { Text("⚠ $it", color = KftTheme.status.warn) }
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
