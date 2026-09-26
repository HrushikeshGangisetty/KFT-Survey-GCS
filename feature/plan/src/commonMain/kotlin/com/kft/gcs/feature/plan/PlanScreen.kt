package com.kft.gcs.feature.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kft.gcs.core.planning.Camera
import com.kft.gcs.core.planning.CameraOrientation
import com.kft.gcs.core.planning.EntryCorner
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
            color = Color.Black.copy(alpha = 0.78f),
        ) {
            Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mission", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(state.hint, style = MaterialTheme.typography.bodySmall, color = Dim)
                state.transfer?.let { progress ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(progress, Modifier.weight(1f), color = MaterialTheme.colorScheme.secondary)
                        TextButton(onClick = actions::onCancelTransferClicked) { Text("Cancel") }
                    }
                }
                GroupList(state.groups, actions)
                HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
                state.survey?.let { SurveyEditor(it, state.groups.firstOrNull { g -> g.selected }?.index ?: 0, actions) }
                    ?: WaypointEditor(state, actions)
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the mission?") },
            text = { Text(state.clearWarning) },
            confirmButton = { TextButton(onClick = { confirmClear = false; actions.onClearClicked() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep") } },
        )
    }
    state.preview?.let { UploadPreviewDialog(it, actions) }
}

/**
 * Plan edits, file actions (disk icon) and vehicle actions (arrow icons), in that order, so "save to a file" and
 * "send to the aircraft" never look alike.
 */
@Composable
private fun Toolbar(state: PlanUiState, actions: PlanActions, onClear: () -> Unit, modifier: Modifier) {
    var exportMenu by remember { mutableStateOf(false) }
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha = 0.78f)) {
        FlowRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = actions::onAddWaypointsClicked) { Text("+ Waypoints") }
            OutlinedButton(onClick = actions::onAddSurveyClicked) { Text("+ Survey") }
            TextButton(onClick = actions::onUndoClicked, enabled = state.canUndo) { Text("↶ Undo") }
            TextButton(onClick = actions::onRedoClicked, enabled = state.canRedo) { Text("↷ Redo") }
            OutlinedButton(onClick = actions::onOpenClicked) { IconText(Disk, "Open") }
            OutlinedButton(onClick = actions::onSaveClicked) { IconText(Disk, "Save") }
            Box {
                OutlinedButton(onClick = { exportMenu = true }) { IconText(Disk, "Export ▾") }
                DropdownMenu(exportMenu, onDismissRequest = { exportMenu = false }) {
                    DropdownMenuItem(text = { Text("QGC .plan (plain items)") }, onClick = { exportMenu = false; actions.onExportClicked(ExportFormat.QGC_PLAN) })
                    DropdownMenuItem(text = { Text("Mission Planner .waypoints") }, onClick = { exportMenu = false; actions.onExportClicked(ExportFormat.WAYPOINTS) })
                }
            }
            FilledTonalButton(onClick = actions::onUploadClicked, enabled = state.canUpload) { IconText(ArrowUp, "Upload") }
            FilledTonalButton(onClick = actions::onReadClicked, enabled = state.canRead) { IconText(ArrowDown, "Read") }
            OutlinedButton(onClick = onClear, enabled = state.canClear) { Text("Clear") }
            state.sync?.let {
                Text(
                    it.text,
                    Modifier.align(Alignment.CenterVertically).padding(horizontal = 6.dp),
                    color = if (it.warning) MaterialTheme.colorScheme.secondary else Color(0xFF81C784),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun GroupList(groups: List<GroupHeader>, actions: PlanActions) {
    var renaming by remember { mutableStateOf<GroupHeader?>(null) }
    groups.forEach { g ->
        Row(
            Modifier.fillMaxWidth().clickable { actions.onGroupSelected(g.index) }
                .background(if (g.selected) Color.White.copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(g.name, color = Color.White, fontWeight = FontWeight.Bold)
                Text("${g.kind} · ${g.summary}", color = Dim, style = MaterialTheme.typography.labelSmall)
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
                .background(if (row.selected) Color.White.copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("${row.seq}  ${row.title}", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Text(row.detail, color = Dim, style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { actions.onDeleteClicked(row.index) }) { Text("✕") }
        }
    }
    state.form?.let { form ->
        Text("${form.title} (item ${state.rows.getOrNull(form.index)?.seq ?: ""})", color = Color.White)
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

    Text("Camera", color = Dim, style = MaterialTheme.typography.labelSmall)
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
    if (s.camera.unverified) Text("Preset not yet checked against the maker's spec sheet.", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall)
    if (panel.cameras.any { it.custom && it.name == s.camera.name }) {
        TextButton(onClick = { actions.onCameraDeleted(s.camera.name) }) { Text("Delete this custom camera") }
    }
    Chips(listOf("Landscape" to CameraOrientation.LANDSCAPE, "Portrait" to CameraOrientation.PORTRAIT), s.orientation, actions::onOrientationSelected)

    Chips(listOf("Set altitude" to HeightMode.ALTITUDE, "Set GSD" to HeightMode.GSD), s.heightMode, actions::onHeightModeSelected)
    if (s.heightMode == HeightMode.ALTITUDE) {
        NumberField("Altitude above home (m)", s.altitudeM, SurveyField.ALTITUDE, group, actions)
        panel.gsdCm?.let { Text("GSD ${oneDecimalText(it)} cm/px", color = Color.White) }
    } else {
        NumberField("GSD (cm/px)", s.gsdCm, SurveyField.GSD, group, actions)
        panel.altitudeM?.let { Text("Altitude ${oneDecimalText(it)} m above home", color = Color.White) }
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
    Text("Start corner", color = Dim, style = MaterialTheme.typography.labelSmall)
    Chips(
        listOf("↙" to EntryCorner.BOTTOM_LEFT, "↘" to EntryCorner.BOTTOM_RIGHT, "↖" to EntryCorner.TOP_LEFT, "↗" to EntryCorner.TOP_RIGHT),
        s.entry, actions::onEntrySelected,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(s.returnHome, actions::onReturnHomeChanged)
        Text("Return to launch at the end (mission item)", color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${panel.cornerCount} corners", Modifier.weight(1f), color = Color.White)
        TextButton(onClick = actions::onDeleteCornerClicked, enabled = panel.cornerSelected) { Text("Delete selected corner") }
    }

    panel.error?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
    panel.warnings.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
    panel.stats.forEach { (label, value) ->
        Row {
            Text(label, Modifier.weight(1f), color = Dim, style = MaterialTheme.typography.bodySmall)
            Text(value, color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
    }

    HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
    Text("Settings (kept between runs)", color = Dim, style = MaterialTheme.typography.labelSmall)
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
                preview.warnings.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.secondary) }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(preview.lines) { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { TextButton(onClick = actions::onUploadConfirmed) { Text("Upload") } },
        dismissButton = { TextButton(onClick = actions::onPreviewDismissed) { Text("Cancel") } },
    )
}

@Composable
private fun IconText(icon: ImageVector, text: String) {
    Icon(icon, contentDescription = null, Modifier.size(18.dp))
    Text(" $text")
}

private val Dim = Color.White.copy(alpha = 0.7f)

/** Our own line icons (no icon library, and nothing copied from another GCS). 24 × 24, drawn in the text colour. */
private fun lineIcon(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
        stroke = SolidColor(Color.White), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw,
    ).build()

/** A floppy disk: files on this computer or tablet. */
private val Disk = lineIcon("disk") {
    moveTo(4f, 3f); lineTo(17f, 3f); lineTo(21f, 7f); lineTo(21f, 21f); lineTo(3f, 21f); lineTo(3f, 3f); close()
    moveTo(7f, 3f); lineTo(7f, 8f); lineTo(15f, 8f); lineTo(15f, 3f)
    moveTo(7f, 21f); lineTo(7f, 14f); lineTo(17f, 14f); lineTo(17f, 21f)
}

/** Arrow up to a bar: to the vehicle. */
private val ArrowUp = lineIcon("to-vehicle") {
    moveTo(12f, 21f); lineTo(12f, 7f); moveTo(6f, 13f); lineTo(12f, 7f); lineTo(18f, 13f); moveTo(4f, 3f); lineTo(20f, 3f)
}

/** Arrow down from a bar: from the vehicle. */
private val ArrowDown = lineIcon("from-vehicle") {
    moveTo(12f, 3f); lineTo(12f, 17f); moveTo(6f, 11f); lineTo(12f, 17f); lineTo(18f, 11f); moveTo(4f, 21f); lineTo(20f, 21f)
}
