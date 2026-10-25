package com.kft.gcs.feature.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kft.gcs.core.vehicle.SensorState
import com.kft.gcs.ui.design.KftIcons
import com.kft.gcs.ui.design.KftTheme
import com.kft.gcs.ui.design.Spacing
import com.kft.gcs.ui.design.Status
import com.kft.gcs.ui.design.StatusChip

/** The verdict, every failure in the FC's latest report, and the sensor health from SYS_STATUS. */
@Composable
internal fun PreArmPage(state: PreArmUiState, onRun: () -> Unit) {
    val verdict = when (state.readiness) {
        Readiness.READY -> Status.OK
        Readiness.NOT_READY -> Status.CRITICAL
        Readiness.CHECKS_OFF -> Status.WARN
        Readiness.ARMED, Readiness.UNKNOWN -> Status.NEUTRAL
    }
    Banner(state.readiness.label, verdict) {
        Button(onClick = onRun, enabled = state.canRun) {
            Icon(KftIcons.Sync, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text("Run checks now")
        }
    }
    if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
    SetupCard(
        "Why it won't arm",
        when {
            state.failures.isNotEmpty() -> "Every failure in the flight controller's latest report. It reports again every 30 s while disarmed, and when you run the checks."
            state.readiness == Readiness.READY -> "Nothing: every check passes. The pilot arms on the RC."
            else -> "No report heard yet. Run the checks to get one."
        },
    ) {
        state.failures.forEachIndexed { i, failure ->
            if (i > 0) HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Icon(KftIcons.Critical, contentDescription = null, Modifier.size(18.dp), tint = KftTheme.status.critical)
                Spacer(Modifier.width(Spacing.s))
                Text(failure, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    SensorsCard(state)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SensorsCard(state: PreArmUiState) {
    SetupCard(
        "Sensor health",
        if (state.sensors.isEmpty()) "Shown once the vehicle sends its status (SYS_STATUS)." else "As the flight controller reports it (SYS_STATUS), updated live.",
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            state.sensors.forEach { s ->
                when (s.state) {
                    SensorState.HEALTHY -> StatusChip(s.name, Status.OK, icon = KftIcons.Ok)
                    SensorState.UNHEALTHY -> StatusChip("${s.name}: unhealthy", Status.CRITICAL, icon = KftIcons.Critical)
                    SensorState.DISABLED -> StatusChip("${s.name}: off", Status.NEUTRAL)
                }
            }
        }
    }
}
