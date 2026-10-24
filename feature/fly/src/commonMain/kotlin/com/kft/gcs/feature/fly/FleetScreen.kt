package com.kft.gcs.feature.fly

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kft.gcs.core.mavlink.VehicleId
import com.kft.gcs.core.vehicle.Severity
import com.kft.gcs.ui.design.KftIcons
import com.kft.gcs.ui.design.SectionHeader
import com.kft.gcs.ui.design.Spacing
import com.kft.gcs.ui.design.StatTile
import com.kft.gcs.ui.design.Status
import com.kft.gcs.ui.design.StatusChip

/**
 * Collects the window's [FleetViewModel] and draws [FleetScreen]. [colorOf] turns a vehicle's slot into its colour;
 * `App()` passes the map's own vehicle colours, so a drone is the same colour here, on the map and in the switcher.
 */
@Composable
fun FleetRoute(viewModel: FleetViewModel, colorOf: (slot: Int) -> Color) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    FleetScreen(state, colorOf, viewModel::onVehicleSelected, viewModel::onClearAlertsClicked)
}

/**
 * The fleet at a glance: one card per vehicle (tap it to select that vehicle everywhere), then the alert list.
 * Stateless. No flight-action buttons (spec S9).
 */
@Composable
fun FleetScreen(state: FleetUiState, colorOf: (Int) -> Color, onSelect: (VehicleId) -> Unit, onClearAlerts: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Text("Fleet", style = MaterialTheme.typography.titleLarge)
        if (state.rows.isEmpty()) Text("No vehicle yet. Connect on Links.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.rows.forEach { row -> VehicleCard(row, colorOf(row.slot)) { onSelect(row.id) } }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionHeader("Alerts") }
            TextButton(onClick = onClearAlerts, enabled = state.alerts.isNotEmpty()) { Text("Clear") }
        }
        if (state.alerts.isEmpty()) Text("Nothing has happened yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.alerts.forEach { a ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                Text(a.time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                StatusChip("${a.vehicle}: ${a.text}", a.severity.status())
            }
        }
    }
}

@Composable
private fun VehicleCard(row: FleetRow, color: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        // The selected vehicle's card is outlined (shape, not colour alone, says which).
        border = if (row.selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                // The vehicle's colour and label, as on the map.
                Surface(Modifier.size(28.dp), shape = CircleShape, color = color) {}
                Text("Vehicle ${row.label}" + if (row.selected) " (selected)" else "", style = MaterialTheme.typography.titleMedium)
                StatusChip(row.link, if (row.connected) Status.OK else Status.CRITICAL, icon = if (row.connected) KftIcons.Link else KftIcons.LinkOff)
                row.login?.let { StatusChip(it, if (row.loginWarning) Status.WARN else Status.OK, icon = KftIcons.Login) }
                if (row.connected) SigningChip(row.signing)
                row.fcSignatureFailures?.takeIf { it > 0 }?.let { StatusChip("FC rejected $it bad frames", Status.WARN) }
                row.conditions.forEach { (text, severity) -> StatusChip(text, severity.status()) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                StatTile("Mode", row.mode)
                StatTile("State", if (row.armed) "ARMED" else "Disarmed", status = if (row.armed) Status.WARN else Status.NEUTRAL)
                StatTile("Battery", row.battery)
                StatTile("Mission", row.mission)
                StatTile("Photos", row.photos)
            }
        }
    }
}

private fun Severity.status() = when (this) {
    Severity.ERROR -> Status.CRITICAL
    Severity.WARNING -> Status.WARN
    Severity.INFO -> Status.OK
}
