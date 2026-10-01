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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kft.gcs.core.mavlink.STANDARD_BAUD_RATES
import com.kft.gcs.core.mavlink.SerialPortInfo
import com.kft.gcs.ui.design.KftIcons
import com.kft.gcs.ui.design.KftTheme
import com.kft.gcs.ui.design.SectionHeader
import com.kft.gcs.ui.design.SegmentedChoice
import com.kft.gcs.ui.design.Spacing
import com.kft.gcs.ui.design.Status
import com.kft.gcs.ui.design.StatusChip
import com.kft.gcs.ui.design.TooltipIconButton
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
            onConnectSerial = viewModel::onConnectSerialClicked,
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
    onConnectSerial: () -> Unit,
) {
    // One scroll container for everything; the profile list is short, so it's a plain Column, not a LazyColumn
    // (a LazyColumn inside a vertical scroll crashes on an unbounded height).
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        Text("Links", style = MaterialTheme.typography.titleLarge)
        LinkStatusCard(state.link, state.canDisconnect, onDisconnect)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val profiles = @Composable { mod: Modifier -> ProfileList(state.profiles, onConnect, onDelete, mod) }
            val form = @Composable { mod: Modifier ->
                NewProfileForm(
                    state.form, state.serialPorts, onFormKindChanged, onFormNameChanged, onFormHostChanged,
                    onFormPortChanged, onFormSerialPortChanged, onFormBaudChanged, onRefreshSerialPorts, onConnectSerial, onSaveProfile, mod,
                )
            }
            if (maxWidth > 720.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                    profiles(Modifier.weight(1f))
                    form(Modifier.widthIn(max = 480.dp))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
                    profiles(Modifier.fillMaxWidth())
                    form(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** The link as a status: icon and colour from the tone (never colour alone), then details and the login line. */
@Composable
private fun LinkStatusCard(link: LinkStatusUi, canDisconnect: Boolean, onDisconnect: () -> Unit) {
    val tint = when (link.tone) {
        Tone.IDLE, Tone.BUSY -> MaterialTheme.colorScheme.onSurfaceVariant
        Tone.OK -> KftTheme.status.ok
        Tone.WARNING -> KftTheme.status.warn
    }
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(Spacing.l), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Icon(
                when (link.tone) {
                    Tone.IDLE -> KftIcons.LinkOff
                    Tone.WARNING -> KftIcons.Warn
                    else -> KftIcons.Link
                },
                contentDescription = null, Modifier.size(28.dp), tint = tint,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(link.headline, style = MaterialTheme.typography.titleMedium)
                link.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                link.login?.let { StatusChip(it, if (link.loginWarning) Status.WARN else Status.OK, icon = KftIcons.Login) }
            }
            if (canDisconnect) OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
        }
    }
}

@Composable
private fun ProfileList(rows: List<ProfileRow>, onConnect: (String) -> Unit, onDelete: (String) -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        SectionHeader("Profiles")
        if (rows.isEmpty()) Text("No profiles. Add one with the form.", style = MaterialTheme.typography.bodySmall)
        rows.forEach { row ->
            key(row.id) {
                Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        Modifier.padding(start = Spacing.l, end = Spacing.s, top = Spacing.s, bottom = Spacing.s),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(row.name, style = MaterialTheme.typography.bodyLarge)
                            Text(row.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (row.isActive) {
                            StatusChip("Active", Status.OK)
                        } else {
                            TooltipIconButton(KftIcons.Delete, "Delete profile", { onDelete(row.id) })
                            Button(onClick = { onConnect(row.id) }) { Text("Connect") }
                        }
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
    onConnectSerial: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier,
) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            SectionHeader("New profile")
            SegmentedChoice(LinkKind.entries.map { it.label to it }, form.kind, onKindChanged, Modifier.fillMaxWidth())
            OwnTextField(form.name, form.generation, onNameChanged, "Name (optional)")
            if (form.needsHost) OwnTextField(form.host, form.generation, onHostChanged, "Host")
            if (form.isSerial) {
                Text("Device", style = MaterialTheme.typography.labelLarge)
                PortDropdown(form.serialPort, serialPorts, onSerialPortChanged, onRefreshSerialPorts)
                val note = serialPorts.firstOrNull { it.name == form.serialPort }?.let(::bluetoothNote)
                if (note != null) {
                    // Over Bluetooth the tablet or PC side has no baud rate; the module's own UART setting is what counts.
                    Text(note, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Baud rate (57600 for a SiK radio; ignored over the FC's own USB)", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        STANDARD_BAUD_RATES.forEach { baud ->
                            FilterChip(selected = form.baud == baud, onClick = { onBaudChanged(baud) }, label = { Text("$baud") })
                        }
                    }
                }
                form.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            } else if (form.isT12) {
                Text(T12_NOTE, style = MaterialTheme.typography.bodySmall)
            } else {
                OwnTextField(form.port, form.generation, onPortChanged, "Port", KeyboardType.Number, form.error)
            }
            if (form.isSerial) {
                // Serial connects straight from the form (pick the port, Connect); saving a profile is optional.
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    OutlinedButton(onClick = onSave) { Text("Save profile") }
                    Button(onClick = onConnectSerial) { Text("Connect") }
                }
            } else {
                Button(onClick = onSave, modifier = Modifier.align(Alignment.End)) { Text("Save profile") }
            }
        }
    }
}

/**
 * The serial ports as a dropdown. Opening it re-lists the ports, so a radio plugged in a moment ago is there without
 * a Refresh button. Ports held by this app (the T12 link, another Serial link) are shown but can't be picked.
 */
@Composable
private fun PortDropdown(selected: String, ports: List<SerialPortInfo>, onPick: (String) -> Unit, onOpen: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { onOpen(); open = true }, Modifier.fillMaxWidth()) {
            Text(ports.firstOrNull { it.name == selected }?.let(::portLabel) ?: "No serial port found · plug it in and open this list again")
        }
        DropdownMenu(open, { open = false }) {
            ports.forEach { p ->
                DropdownMenuItem(text = { Text(portLabel(p)) }, enabled = p.inUseBy == null, onClick = { onPick(p.name); open = false })
            }
        }
    }
}

/**
 * A form field that keeps its own text and reports each change, like the Params search field. Fed back through the
 * ViewModel's StateFlow (an async hop), fast typing lost characters: an older value arrived after newer keystrokes
 * and replaced them. [value] is read again only when [generation] changes, i.e. when the ViewModel rewrote the form.
 */
@Composable
private fun OwnTextField(
    value: String,
    generation: Int,
    onChange: (String) -> Unit,
    label: String,
    keyboard: KeyboardType = KeyboardType.Text,
    error: String? = null,
) {
    var text by remember(generation) { mutableStateOf(value) }
    OutlinedTextField(
        text, { text = it; onChange(it) }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
    )
}

/** What a T12 profile does, and the open question about it (ADR-004, Step 0). */
private const val T12_NOTE =
    "MAVLink over the Skydroid T12's own USB link (plug the controller in over USB). The same USB link carries the " +
        "T12's video, and the app shares it between the two. Not yet confirmed on hardware that the T12 carries " +
        "telemetry this way: if no vehicle is heard, connect telemetry over UDP or a radio instead."
