package com.kft.gcs.feature.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kft.gcs.core.mavlink.STANDARD_BAUD_RATES
import com.kft.gcs.core.mavlink.SerialPortInfo
import org.koin.compose.viewmodel.koinViewModel

/**
 * Thin wrapper: gets the ViewModel from Koin, collects its state and effects, and hands plain values and
 * lambdas to [ConnectionsScreen]. All the logic is in the ViewModel; all the drawing is in the screen.
 */
@Composable
fun ConnectionsRoute(viewModel: ConnectionsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ConnectionsEffect.ShowMessage -> snackbar.showSnackbar(effect.text)
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        ConnectionsScreen(
            state = state,
            onConnect = viewModel::onConnectClicked,
            onDisconnect = viewModel::onDisconnectClicked,
            onDelete = viewModel::onDeleteClicked,
            onFormKindChanged = viewModel::onFormKindChanged,
            onFormNameChanged = viewModel::onFormNameChanged,
            onFormHostChanged = viewModel::onFormHostChanged,
            onFormPortChanged = viewModel::onFormPortChanged,
            onSaveProfile = viewModel::onSaveProfileClicked,
            onFormSerialPortChanged = viewModel::onFormSerialPortChanged,
            onFormBaudChanged = viewModel::onFormBaudChanged,
            onRefreshSerialPorts = viewModel::onRefreshSerialPortsClicked,
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/** Stateless: draws [state], reports clicks. Two columns on a tablet or desktop, one column on a phone. */
@Composable
fun ConnectionsScreen(
    state: ConnectionsUiState,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onDelete: (String) -> Unit,
    onFormKindChanged: (LinkKind) -> Unit,
    onFormNameChanged: (String) -> Unit,
    onFormHostChanged: (String) -> Unit,
    onFormPortChanged: (String) -> Unit,
    onSaveProfile: () -> Unit,
    onFormSerialPortChanged: (String) -> Unit,
    onFormBaudChanged: (Int) -> Unit,
    onRefreshSerialPorts: () -> Unit,
) {
    // One scroll container for everything; the profile list is short, so it's a plain Column, not a LazyColumn
    // (a LazyColumn inside a vertical scroll crashes on an unbounded height).
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LinkStatusCard(state.link, state.canDisconnect, onDisconnect)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val profiles = @Composable { mod: Modifier -> ProfileList(state.profiles, onConnect, onDelete, mod) }
            val form = @Composable { mod: Modifier ->
                NewProfileForm(
                    state.form, state.serialPorts, onFormKindChanged, onFormNameChanged, onFormHostChanged,
                    onFormPortChanged, onFormSerialPortChanged, onFormBaudChanged, onRefreshSerialPorts, onSaveProfile, mod,
                )
            }
            if (maxWidth > 720.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    profiles(Modifier.weight(1f))
                    form(Modifier.widthIn(max = 380.dp))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    profiles(Modifier.fillMaxWidth())
                    form(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun LinkStatusCard(link: LinkStatusUi, canDisconnect: Boolean, onDisconnect: () -> Unit) {
    val accent = when (link.tone) {
        Tone.IDLE -> MaterialTheme.colorScheme.outline
        Tone.BUSY -> MaterialTheme.colorScheme.primary
        Tone.OK -> Color(0xFF66BB6A)
        Tone.WARNING -> MaterialTheme.colorScheme.secondary
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(link.headline, style = MaterialTheme.typography.titleMedium, color = accent)
                link.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                link.login?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (link.loginWarning) MaterialTheme.colorScheme.secondary else Color.Unspecified,
                    )
                }
            }
            if (canDisconnect) OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
        }
    }
}

@Composable
private fun ProfileList(rows: List<ProfileRow>, onConnect: (String) -> Unit, onDelete: (String) -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Profiles", style = MaterialTheme.typography.titleSmall)
        if (rows.isEmpty()) Text("No profiles. Add one with the form.", style = MaterialTheme.typography.bodySmall)
        rows.forEach { row ->
            key(row.id) {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(row.name, style = MaterialTheme.typography.bodyLarge)
                            Text(row.summary, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onDelete(row.id) }, enabled = !row.isActive) { Text("Delete") }
                        Button(onClick = { onConnect(row.id) }, enabled = !row.isActive) { Text(if (row.isActive) "Active" else "Connect") }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewProfileForm(
    form: ProfileForm,
    serialPorts: List<SerialPortInfo>,
    onKindChanged: (LinkKind) -> Unit,
    onNameChanged: (String) -> Unit,
    onHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onSerialPortChanged: (String) -> Unit,
    onBaudChanged: (Int) -> Unit,
    onRefreshSerialPorts: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier,
) {
    Card(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("New profile", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LinkKind.entries.forEach { kind ->
                    FilterChip(selected = form.kind == kind, onClick = { onKindChanged(kind) }, label = { Text(kind.label) })
                }
            }
            OutlinedTextField(form.name, onNameChanged, Modifier.fillMaxWidth(), label = { Text("Name (optional)") }, singleLine = true)
            if (form.needsHost) {
                OutlinedTextField(form.host, onHostChanged, Modifier.fillMaxWidth(), label = { Text("Host") }, singleLine = true)
            }
            if (form.isSerial) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Device", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = onRefreshSerialPorts) { Text("Refresh") }
                }
                if (serialPorts.isEmpty()) Text("No serial ports found. Plug in the radio or flight controller, then Refresh.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    serialPorts.forEach { p ->
                        FilterChip(
                            selected = form.serialPort == p.name,
                            onClick = { onSerialPortChanged(p.name) },
                            label = { Text(if (p.description.isBlank()) p.name else "${p.name} · ${p.description}") },
                        )
                    }
                }
                Text("Baud rate (57600 for a SiK radio; ignored over the FC's own USB)", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    STANDARD_BAUD_RATES.forEach { baud ->
                        FilterChip(selected = form.baud == baud, onClick = { onBaudChanged(baud) }, label = { Text("$baud") })
                    }
                }
                form.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            } else {
                OutlinedTextField(
                    form.port, onPortChanged, Modifier.fillMaxWidth(), label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = form.error != null,
                    supportingText = form.error?.let { { Text(it) } },
                )
            }
            Button(onClick = onSave, modifier = Modifier.align(Alignment.End)) { Text("Save profile") }
        }
    }
}
