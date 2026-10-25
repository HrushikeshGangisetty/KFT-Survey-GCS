package com.kft.gcs.feature.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kft.gcs.ui.design.ConfirmDialog
import com.kft.gcs.ui.design.KftIcons
import com.kft.gcs.ui.design.KftTheme
import com.kft.gcs.ui.design.MinTouchTarget
import com.kft.gcs.ui.design.Spacing
import com.kft.gcs.ui.design.Status
import com.kft.gcs.ui.design.StatusChip

/** Wires the stateless [SetupScreen] to its ViewModels, and shows their messages as snackbars. */
@Composable
fun SetupRoute(setup: SetupViewModel, calibration: CalibrationViewModel, preArm: PreArmViewModel) {
    val state by setup.state.collectAsStateWithLifecycle()
    val cal by calibration.state.collectAsStateWithLifecycle()
    val pre by preArm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(setup) { setup.effects.collect { (it as SetupEffect.ShowMessage).let { m -> snackbar.showSnackbar(m.text) } } }
    LaunchedEffect(calibration) { calibration.effects.collect { (it as SetupEffect.ShowMessage).let { m -> snackbar.showSnackbar(m.text) } } }
    LaunchedEffect(preArm) { preArm.effects.collect { (it as SetupEffect.ShowMessage).let { m -> snackbar.showSnackbar(m.text) } } }
    Box(Modifier.fillMaxSize()) {
        SetupScreen(
            state, cal,
            SetupActions(
                onPage = setup::onPageSelected, onDownload = setup::onDownloadClicked, onField = setup::onFieldClicked,
                onEditText = setup::onEditTextChanged, onEditSet = setup::onEditSetClicked, onEditConfirm = setup::onEditConfirmClicked,
                onEditDismiss = setup::onEditDismissed,
            ),
            CalibrationActions(
                onAccelStart = calibration::onAccelStartClicked, onAccelPlaced = calibration::onAccelPlacedClicked,
                onLevel = calibration::onLevelClicked, onSimpleAccel = calibration::onSimpleAccelClicked, onBaro = calibration::onBaroClicked,
                onCompassStart = calibration::onCompassStartClicked, onRadioStart = calibration::onRadioStartClicked,
                onRadioSweepDone = calibration::onRadioSweepDone, onRadioCentred = calibration::onRadioCentred,
                onRadioSave = calibration::onRadioSaveClicked, onCancel = calibration::onCancelClicked, onReboot = calibration::onRebootClicked,
                onRebootConfirm = calibration::onRebootConfirmed, onRebootDismiss = calibration::onRebootDismissed,
            ),
            pre, preArm::onRunChecksClicked,
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/** The Setup tab's events that aren't calibrations. */
class SetupActions(
    val onPage: (SetupPage) -> Unit = {},
    val onDownload: () -> Unit = {},
    val onField: (String) -> Unit = {},
    val onEditText: (String) -> Unit = {},
    val onEditSet: () -> Unit = {},
    val onEditConfirm: () -> Unit = {},
    val onEditDismiss: () -> Unit = {},
)

/**
 * Vehicle setup: a page list on the left, the page on the right. The calibration pages draw [cal], the pre-arm page
 * [preArm]; the others draw [state]. Everything that talks to the vehicle is disabled, with the reason shown, unless
 * the vehicle is connected, logged in and disarmed.
 */
@Composable
fun SetupScreen(
    state: SetupUiState,
    cal: CalibrationUiState,
    actions: SetupActions,
    calActions: CalibrationActions,
    preArm: PreArmUiState = PreArmUiState(),
    onRunPreArm: () -> Unit = {},
) {
    Row(Modifier.fillMaxSize()) {
        PageList(state, cal, actions, calActions)
        VerticalDivider()
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.l),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                PageHeader(state, actions)
                val calibrationPage = state.page in CALIBRATION_PAGES
                when {
                    calibrationPage -> cal.hint?.takeIf { cal.running == null }
                    state.page == SetupPage.PREARM -> preArm.hint
                    else -> state.notice
                }?.let { Banner(it, Status.WARN) }
                cal.rebootAdvice?.let { advice ->
                    Banner(advice, Status.WARN) { TextButton(onClick = calActions.onReboot, enabled = cal.canReboot) { Text("Reboot now") } }
                }
                when (state.page) {
                    SetupPage.ACCEL -> AccelPage(cal, calActions)
                    SetupPage.COMPASS -> CompassPage(cal, calActions)
                    SetupPage.BARO -> BaroPage(cal, calActions)
                    SetupPage.RADIO -> RadioPage(cal, calActions)
                    SetupPage.FLIGHT_MODES, SetupPage.FAILSAFES -> ParamPage(state, actions)
                    SetupPage.HARDWARE -> HardwarePage(state)
                    SetupPage.PREARM -> PreArmPage(preArm, onRunPreArm)
                }
                if (calibrationPage && cal.log.isNotEmpty()) VehicleMessages(cal)
            }
        }
    }
    state.edit?.let { EditDialog(it, actions) }
    if (cal.confirmReboot) {
        ConfirmDialog(
            title = "Reboot the flight controller?",
            text = "The autopilot restarts. The link drops for a few seconds, then the GCS logs in again by itself. Only possible while disarmed.",
            confirmLabel = "Reboot",
            onConfirm = calActions.onRebootConfirm,
            onDismiss = calActions.onRebootDismiss,
            dismissLabel = "Don't reboot",
        )
    }
}

private val CALIBRATION_PAGES = setOf(SetupPage.ACCEL, SetupPage.COMPASS, SetupPage.BARO, SetupPage.RADIO)

/** The pages that show parameter values (the radio page reads the RCx_ ranges), so their header offers "Read". */
private val PARAM_PAGES = setOf(SetupPage.RADIO, SetupPage.FLIGHT_MODES, SetupPage.FAILSAFES, SetupPage.HARDWARE)

@Composable
private fun PageList(state: SetupUiState, cal: CalibrationUiState, actions: SetupActions, calActions: CalibrationActions) {
    Column(Modifier.width(240.dp).fillMaxHeight().padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text("Vehicle setup", style = MaterialTheme.typography.titleLarge)
        Text(state.vehicle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Scrolls on a short tablet screen; the reboot button stays pinned below.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            SetupPage.entries.groupBy { it.group }.forEach { (group, pages) ->
                Text(group, Modifier.padding(top = Spacing.m, start = Spacing.s), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                pages.forEach { page ->
                    NavigationDrawerItem(
                        label = { Text(page.title) },
                        selected = page == state.page,
                        onClick = { actions.onPage(page) },
                        // A mark on the page whose calibration is running, so it can be found again from another page.
                        badge = if (cal.running?.let { pageOf(it) } == page) { { Icon(KftIcons.Sync, contentDescription = "Running", tint = KftTheme.status.warn) } } else null,
                    )
                }
            }
        }
        OutlinedButton(onClick = calActions.onReboot, enabled = cal.canReboot, modifier = Modifier.fillMaxWidth()) {
            Icon(KftIcons.Reboot, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.s))
            Text("Reboot FC")
        }
    }
}

private fun pageOf(c: Calibration) = when (c) {
    Calibration.ACCEL, Calibration.LEVEL, Calibration.SIMPLE_ACCEL -> SetupPage.ACCEL
    Calibration.COMPASS -> SetupPage.COMPASS
    Calibration.BARO -> SetupPage.BARO
    Calibration.RADIO -> SetupPage.RADIO
}

@Composable
private fun PageHeader(state: SetupUiState, actions: SetupActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(state.page.title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
        // Only the pages that show parameters need them read.
        if (state.page in PARAM_PAGES) {
            Text(state.params, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = actions.onDownload, enabled = state.canDownload) {
                Icon(KftIcons.Download, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text("Read")
            }
        }
    }
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
}

/** A tinted strip with an icon and a sentence, for "why is this off" and "you should reboot". */
@Composable
internal fun Banner(text: String, status: Status, action: (@Composable () -> Unit)? = null) {
    val c = KftTheme.status
    val (accent, icon) = when (status) {
        Status.CRITICAL -> c.critical to KftIcons.Critical
        Status.OK -> c.ok to KftIcons.Ok
        Status.WARN -> c.warn to KftIcons.Warn
        Status.NEUTRAL -> MaterialTheme.colorScheme.outline to KftIcons.About
    }
    Surface(shape = MaterialTheme.shapes.medium, color = accent.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = accent)
            Spacer(Modifier.width(Spacing.s))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            action?.invoke()
        }
    }
}

/** A titled card: the building block of every page. */
@Composable
internal fun SetupCard(title: String, description: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
        }
    }
}

@Composable
private fun ParamPage(state: SetupUiState, actions: SetupActions) {
    state.problems.forEach { Banner("The FC will refuse to arm: $it", Status.CRITICAL) }
    state.modeSwitch?.let { Banner(it, Status.NEUTRAL) }
    state.sections.forEach { section ->
        SetupCard(section.title, section.note) {
            section.rows.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider()
                val slot = row.param.removePrefix("FLTMODE").toIntOrNull()?.minus(1)
                FieldRowView(row, enabled = state.canWrite && row.available, active = slot != null && slot == state.activeSlot) { actions.onField(row.param) }
            }
        }
    }
}

@Composable
private fun FieldRowView(row: FieldRow, enabled: Boolean, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(row.label, style = MaterialTheme.typography.bodyLarge)
                if (active) StatusChip("Selected by the switch", Status.OK)
            }
            Text(listOfNotNull(row.param, row.help).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            if (row.available) row.value else "Not on this vehicle",
            style = MaterialTheme.typography.bodyLarge,
            color = if (row.available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (enabled) Icon(KftIcons.Edit, contentDescription = "Change ${row.label}", Modifier.padding(start = Spacing.s).size(18.dp))
    }
}

@Composable
private fun HardwarePage(state: SetupUiState) {
    SetupCard("Flight controller", if (state.board.isEmpty()) "Shown once the vehicle has sent its version (AUTOPILOT_VERSION)." else null) {
        state.board.forEach { (k, v) -> KeyValue(k, v) }
    }
    SetupCard(
        "Sensors",
        if (state.sensors.isEmpty()) "Read the parameters to list the sensors." else "From each sensor's id parameter, as ArduPilot detected them at boot.",
    ) {
        state.sensors.forEachIndexed { i, s ->
            if (i > 0) HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${s.sensor} · ${s.chip}", style = MaterialTheme.typography.bodyLarge)
                    Text(s.bus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(s.param, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${s.id} (0x${s.id.toString(16).padStart(6, '0')})", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
internal fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, Modifier.width(140.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

/** The FC's own messages during the calibration ("Place vehicle nose DOWN and press any key."), newest last. */
@Composable
private fun VehicleMessages(cal: CalibrationUiState) {
    SetupCard("Messages from the vehicle") {
        cal.log.forEach { m ->
            Text(m.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

/** Choose the value (radio list, number or bits), then confirm. Nothing is sent before the second step. */
@Composable
private fun EditDialog(edit: FieldEdit, actions: SetupActions) {
    val confirming = edit.confirm != null
    AlertDialog(
        onDismissRequest = actions.onEditDismiss,
        title = { Text(edit.label) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(listOfNotNull(edit.param, edit.help).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Now: ${edit.current}", style = MaterialTheme.typography.bodyMedium)
                if (confirming) {
                    Text(edit.confirm.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                } else {
                    when (val kind = edit.kind) {
                        is FieldKind.Choice -> kind.options.forEach { (code, label) ->
                            val selected = edit.text.trim() == code.toString()
                            ChoiceLine(label, selected, Modifier.selectable(selected, role = Role.RadioButton) { actions.onEditText(code.toString()) }) {
                                RadioButton(selected, onClick = null)
                            }
                        }
                        is FieldKind.Bits -> kind.bits.forEach { (bit, label) ->
                            val mask = edit.text.trim().toLongOrNull() ?: 0L
                            val on = (mask shr bit) and 1L == 1L
                            ChoiceLine(label, on, Modifier.toggleable(on, role = Role.Checkbox) { actions.onEditText((mask xor (1L shl bit)).toString()) }) {
                                Checkbox(on, onCheckedChange = null)
                            }
                        }
                        is FieldKind.Number -> OutlinedTextField(
                            value = edit.text,
                            onValueChange = actions.onEditText,
                            label = { Text("New value") },
                            suffix = { Text(kind.unit) },
                            supportingText = { Text("${number(kind.range.start)} to ${number(kind.range.endInclusive)} ${kind.unit}".trimEnd()) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                    }
                }
                edit.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            if (confirming) TextButton(onClick = actions.onEditConfirm) { Text("Send to vehicle") }
            else TextButton(onClick = actions.onEditSet) { Text("Next") }
        },
        dismissButton = {
            if (confirming) TextButton(onClick = { actions.onEditText(edit.text) }) { Text("Back") }
            else TextButton(onClick = actions.onEditDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ChoiceLine(label: String, selected: Boolean, modifier: Modifier, control: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = MinTouchTarget), verticalAlignment = Alignment.CenterVertically) {
        control()
        Spacer(Modifier.width(Spacing.s))
        Text(label, style = if (selected) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium)
    }
}
