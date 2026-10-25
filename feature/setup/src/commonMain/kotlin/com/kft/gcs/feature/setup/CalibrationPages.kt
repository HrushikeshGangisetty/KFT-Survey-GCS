package com.kft.gcs.feature.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.ui.design.Spacing
import com.kft.gcs.ui.design.Status

/** The calibration pages' events. */
class CalibrationActions(
    val onAccelStart: () -> Unit = {},
    val onAccelPlaced: () -> Unit = {},
    val onLevel: () -> Unit = {},
    val onSimpleAccel: () -> Unit = {},
    val onBaro: () -> Unit = {},
    val onCompassStart: () -> Unit = {},
    val onHeading: (String) -> Unit = {},
    val onFixedYaw: () -> Unit = {},
    val onRadioStart: () -> Unit = {},
    val onRadioSweepDone: () -> Unit = {},
    val onRadioCentred: () -> Unit = {},
    val onRadioSave: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onReboot: () -> Unit = {},
    val onRebootConfirm: () -> Unit = {},
    val onRebootDismiss: () -> Unit = {},
)

@Composable
private fun OutcomeBanner(outcome: Outcome?) {
    outcome?.let { Banner(it.text, if (it.ok) Status.OK else Status.CRITICAL) }
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
internal fun AccelPage(cal: CalibrationUiState, actions: CalibrationActions) {
    OutcomeBanner(cal.imuResult)
    SetupCard(
        "Accelerometer (6 positions)",
        "The flight controller asks for six positions in turn: level, left side, right side, nose down, nose up, on its back. " +
            "Put the vehicle in each one, keep it still, and press the button. Propellers off.",
    ) {
        if (cal.running == Calibration.ACCEL) {
            val position = cal.accel.waitingFor ?: cal.accel.sampling
            if (position != null) {
                Text("Position ${position.wire} of 6", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                LinearProgressIndicator(progress = { (position.wire - 1) / 6f }, modifier = Modifier.fillMaxWidth())
            }
            when {
                cal.accel.waitingFor != null -> Text("Place the vehicle ${cal.accel.waitingFor.label}, keep it still, then press the button.", style = MaterialTheme.typography.titleMedium)
                cal.accel.sampling != null -> Text("Measuring ${cal.accel.sampling.label}. Keep it still…", style = MaterialTheme.typography.titleMedium)
                else -> Text("Calibrating the gyros first. Keep the vehicle still…", style = MaterialTheme.typography.titleMedium)
            }
            Buttons {
                Button(onClick = actions.onAccelPlaced, enabled = cal.accel.waitingFor != null) {
                    Text(cal.accel.waitingFor?.let { "Vehicle is ${it.label}" } ?: "Waiting for the vehicle…")
                }
                OutlinedButton(onClick = actions.onCancel) { Text("Cancel") }
            }
        } else {
            Button(onClick = actions.onAccelStart, enabled = cal.canStart) { Text("Start accelerometer calibration") }
        }
    }
    SetupCard("Level", "Set the vehicle on a level surface in its flying attitude. What the FC reads now becomes level. Takes a few seconds.") {
        RunButton("Calibrate level", Calibration.LEVEL, cal, actions.onLevel)
    }
    SetupCard(
        "Large vehicle (simple)",
        "For a vehicle too large to turn over: accelerometer offsets from one level position. Less complete than the 6-position calibration.",
    ) {
        RunButton("Simple accelerometer calibration", Calibration.SIMPLE_ACCEL, cal, actions.onSimpleAccel)
    }
}

/** A one-command calibration's button: disabled while anything runs, a spinner while this one does. */
@Composable
private fun RunButton(text: String, which: Calibration, cal: CalibrationUiState, onClick: () -> Unit) {
    if (cal.running == which) {
        Text("Running. Keep the vehicle still…", style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(Modifier.fillMaxWidth())
    } else {
        OutlinedButton(onClick = onClick, enabled = cal.canStart) { Text(text) }
    }
}

@Composable
internal fun CompassPage(cal: CalibrationUiState, actions: CalibrationActions) {
    OutcomeBanner(cal.compass.result)
    SetupCard(
        "Calibrate the compasses",
        "Calibrates every compass at once. Away from metal, cars and buildings, turn the vehicle slowly through every orientation " +
            "(each side facing down in turn, rotating around) until every bar is full. The FC saves the result itself; a reboot then applies it.",
    ) {
        if (cal.running == Calibration.COMPASS) {
            if (cal.compass.rows.isEmpty()) Text("Starting…", style = MaterialTheme.typography.bodyMedium)
            Buttons { OutlinedButton(onClick = actions.onCancel) { Text("Cancel") } }
        } else {
            Button(onClick = actions.onCompassStart, enabled = cal.canStart) { Text("Start compass calibration") }
        }
        cal.compass.rows.forEach { row ->
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Compass ${row.number}", Modifier.width(96.dp), style = MaterialTheme.typography.bodyLarge)
                Column(Modifier.weight(1f)) {
                    LinearProgressIndicator(progress = { row.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Text(listOfNotNull("${row.percent} %", row.status, row.fitness).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * Large Vehicle MagCal: for an airframe too big to turn through every orientation. The operator points the vehicle at a
 * known true heading (a surveyed line, a runway, a compass bearing corrected for declination) and types it.
 */
@Composable
internal fun FixedYawCard(cal: CalibrationUiState, actions: CalibrationActions) {
    SetupCard(
        "Large vehicle: calibrate from a known heading",
        "For a vehicle you can't rotate. Point it at a heading you know, measured from true north, level and away from metal. " +
            "The FC needs a GPS position to look up the Earth's field here, then sets and saves the offsets of every compass used for heading." +
            (cal.compass.headingNow?.let { " It reads $it now." } ?: ""),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = cal.compass.heading,
                onValueChange = actions.onHeading,
                label = { Text("True heading") },
                suffix = { Text("°") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.widthIn(max = 200.dp),
            )
            RunButton("Calibrate from this heading", Calibration.FIXED_YAW_COMPASS, cal, actions.onFixedYaw)
        }
    }
}

@Composable
internal fun BaroPage(cal: CalibrationUiState, actions: CalibrationActions) {
    OutcomeBanner(cal.baroResult)
    val plane = cal.vehicleKind == VehicleKind.PLANE
    SetupCard(
        "Ground pressure",
        "Sets the current air pressure as ground level, so altitude reads 0 here. ArduPilot also does this at every boot and when arming; " +
            "use it when the vehicle has been powered for a long time before flying." +
            if (plane) " On a Plane this also zeroes the airspeed sensor: cover the pitot tube from wind first." else "",
    ) {
        RunButton("Calibrate barometer", Calibration.BARO, cal, actions.onBaro)
    }
}

@Composable
internal fun RadioPage(cal: CalibrationUiState, actions: CalibrationActions) {
    val radio = cal.radio
    OutcomeBanner(radio.result)
    SetupCard(
        "Calibrate the radio",
        "Records the range of every RC channel. Propellers off, transmitter on. While it runs the FC refuses to arm (\"RC calibrating\").",
    ) {
        val (instruction, button) = when (radio.step) {
            RadioStep.IDLE -> null to null
            RadioStep.SWEEP -> "Move every stick, switch and knob to both ends a few times, holding each end a moment. Then press Next." to ("Next" to actions.onRadioSweepDone)
            RadioStep.CENTRE -> "Centre the sticks, put the throttle all the way down and the switches to their normal position. Then press Next." to ("Next" to actions.onRadioCentred)
            RadioStep.REVIEW -> "Check the ranges below, then save them to the vehicle." to ("Save to vehicle" to actions.onRadioSave)
            RadioStep.SAVING -> "Saving…" to null
        }
        instruction?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        when {
            cal.running != Calibration.RADIO -> Button(onClick = actions.onRadioStart, enabled = cal.canStart) { Text("Start radio calibration") }
            radio.step == RadioStep.SAVING -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> Buttons {
                button?.let { (label, onClick) ->
                    Button(onClick = onClick, enabled = radio.step != RadioStep.REVIEW || radio.review?.problems?.isEmpty() == true) { Text(label) }
                }
                OutlinedButton(onClick = actions.onCancel) { Text("Cancel") }
            }
        }
        radio.review?.takeIf { radio.step == RadioStep.REVIEW }?.let { review ->
            review.problems.forEach { Banner(it, Status.CRITICAL) }
            review.warnings.forEach { Banner(it, Status.WARN) }
            if (review.writes.isNotEmpty()) Text("Will write: " + review.writes.joinToString(", ") { (n, v) -> "$n ${v.toInt()}" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
    SetupCard("Channels", if (radio.channels.isEmpty()) "No RC signal: is the receiver bound and the transmitter on?" else null) {
        radio.channels.forEach { ChannelBar(it) }
    }
}

/**
 * One channel: a track from 800 to 2200 µs with the swept range shaded and a tick at the live value. Numbers beside it,
 * so nothing depends on reading the colours.
 */
@Composable
private fun ChannelBar(ch: RadioChannel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(listOfNotNull("Ch ${ch.number}", ch.role).joinToString(" "), Modifier.width(112.dp), style = MaterialTheme.typography.bodyMedium)
        BoxWithConstraints(Modifier.weight(1f).height(16.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant)) {
            fun x(pwm: Int) = maxWidth * ((pwm - 800).coerceIn(0, 1400) / 1400f)
            if (ch.min != null && ch.max != null) {
                Box(Modifier.offset(x = x(ch.min)).width(x(ch.max) - x(ch.min)).fillMaxHeight().background(MaterialTheme.colorScheme.primaryContainer))
            }
            ch.pwm?.let { Box(Modifier.offset(x = x(it) - 2.dp).width(4.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary)) }
        }
        Text(
            listOfNotNull(ch.min?.let { "$it" }, ch.pwm?.let { "$it" } ?: "—", ch.max?.let { "$it" }).joinToString(" · "),
            Modifier.width(150.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
        )
    }
}
