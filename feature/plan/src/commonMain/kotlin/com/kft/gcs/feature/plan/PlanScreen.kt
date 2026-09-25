package com.kft.gcs.feature.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
        PlanScreen(
            state = state,
            onRowSelected = viewModel::onRowSelected,
            onDelete = viewModel::onDeleteClicked,
            onAltitudeChanged = viewModel::onAltitudeChanged,
            onSpeedChanged = viewModel::onSpeedChanged,
            onUpload = viewModel::onUploadClicked,
            onRead = viewModel::onReadClicked,
            onClear = viewModel::onClearClicked,
            onCancelTransfer = viewModel::onCancelTransferClicked,
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * Stateless: the editor panel on the right, over the map. The map itself is drawn underneath by `App()`, and the
 * rest of this screen is empty so clicks reach it. No flight-action buttons (spec S9).
 */
@Composable
fun PlanScreen(
    state: PlanUiState,
    onRowSelected: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onAltitudeChanged: (String) -> Unit,
    onSpeedChanged: (String) -> Unit,
    onUpload: () -> Unit,
    onRead: () -> Unit,
    onClear: () -> Unit,
    onCancelTransfer: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        // A Surface, not a plain background: M3 Surface blocks pointer events, so a click on the panel can't fall
        // through to the map underneath and add a waypoint there.
        Surface(
            Modifier.align(Alignment.TopEnd).padding(12.dp).width(320.dp).fillMaxHeight(),
            shape = RoundedCornerShape(12.dp),
            color = Color.Black.copy(alpha = 0.75f),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mission", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(state.hint, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onUpload, enabled = state.canUpload) { Text("Upload") }
                    OutlinedButton(onClick = onRead, enabled = state.canRead) { Text("Read") }
                    OutlinedButton(onClick = { confirmClear = true }, enabled = state.canClear) { Text("Clear") }
                }
                state.transfer?.let { progress ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(progress, Modifier.weight(1f), color = MaterialTheme.colorScheme.secondary)
                        TextButton(onClick = onCancelTransfer) { Text("Cancel") }
                    }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(state.rows, key = { it.index }) { row ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onRowSelected(row.index) }
                                .background(if (row.selected) Color.White.copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${row.seq}  ${row.title}", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                Text(row.detail, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { onDelete(row.index) }) { Text("✕") }
                        }
                    }
                }
                state.form?.let { form ->
                    Text("${form.title} (item ${state.rows.getOrNull(form.index)?.seq ?: ""})", color = Color.White)
                    OutlinedTextField(
                        value = form.altitude, onValueChange = onAltitudeChanged, singleLine = true,
                        label = { Text("Altitude above home (m)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(
                        value = form.speed, onValueChange = onSpeedChanged, singleLine = true,
                        label = { Text("Speed from here (m/s, blank = keep)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    form.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the mission?") },
            text = { Text("This deletes the mission on the vehicle and empties the editor.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep") } },
        )
    }
}
