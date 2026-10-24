package com.kft.gcs.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kft.gcs.ui.design.ConfirmDialog
import com.kft.gcs.ui.design.KftIcons
import com.kft.gcs.ui.design.SectionHeader
import com.kft.gcs.ui.design.Spacing
import com.kft.gcs.ui.design.Status
import com.kft.gcs.ui.design.StatusChip
import com.kft.gcs.ui.design.TooltipIconButton

/** Wires the stateless [SigningKeysScreen] to the window-wide ViewModel that `App()` creates. */
@Composable
fun SigningKeysRoute(viewModel: SigningKeysViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SigningKeysScreen(
        state,
        SigningKeysActions(
            onCreate = viewModel::onCreateClicked,
            onUnlock = viewModel::onUnlockClicked,
            onLock = viewModel::onLockClicked,
            onImport = viewModel::onImportClicked,
            onPassphrase = viewModel::onPassphraseSubmitted,
            onPassphraseCancel = viewModel::onPassphraseCancelled,
            onRemove = viewModel::onRemoveClicked,
            onRemoveConfirm = viewModel::onRemoveConfirmed,
            onRemoveCancel = viewModel::onRemoveCancelled,
        ),
    )
}

/** The start-up prompt, shown over any tab while the vault exists and is locked ([SigningKeysUiState.promptUnlock]). */
@Composable
fun SigningUnlockPrompt(viewModel: SigningKeysViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.promptUnlock) SigningUnlockDialog(state, viewModel::onUnlockClicked, viewModel::onSkipUnlockClicked)
}

/** The screen's events, grouped so the screen takes two arguments. */
class SigningKeysActions(
    val onCreate: (pin: String, confirm: String) -> Unit = { _, _ -> },
    val onUnlock: (String) -> Unit = {},
    val onLock: () -> Unit = {},
    val onImport: () -> Unit = {},
    val onPassphrase: (String) -> Unit = {},
    val onPassphraseCancel: () -> Unit = {},
    val onRemove: (String) -> Unit = {},
    val onRemoveConfirm: () -> Unit = {},
    val onRemoveCancel: () -> Unit = {},
)

/**
 * The signing keys (PRD FR-9). Three looks: no vault yet (make one with a PIN), locked (enter the PIN), unlocked (the
 * list, import, lock). Key bytes never appear here: the state has none.
 */
@Composable
fun SigningKeysScreen(state: SigningKeysUiState, actions: SigningKeysActions) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Text("Signing keys", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Each drone that signs its MAVLink has its own key. They are stored encrypted on this device and unlocked with a PIN. " +
                    "A drone with no key here can't be controlled by this app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (state.phase) {
                VaultPhase.NO_VAULT -> CreateSection(state, actions)
                VaultPhase.LOCKED -> UnlockSection(state, actions)
                VaultPhase.UNLOCKED -> KeysSection(state, actions)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
    }
    if (state.askPassphrase) PassphraseDialog(state.busy, actions.onPassphrase, actions.onPassphraseCancel)
    state.confirmRemove?.let { key ->
        ConfirmDialog(
            title = "Remove “${key.label}”?",
            text = "This device will no longer be able to sign for ${key.shortId}. To fly it again you must import its key bundle.",
            confirmLabel = "Remove",
            onConfirm = actions.onRemoveConfirm,
            onDismiss = actions.onRemoveCancel,
            dismissLabel = "Keep",
            destructive = true,
        )
    }
}

@Composable
private fun CreateSection(state: SigningKeysUiState, actions: SigningKeysActions) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    SectionHeader("Create the key vault")
    Text(
        "Choose a PIN of at least $MIN_PIN_LENGTH characters. There is no way to recover it: if it is forgotten the vault is wiped and the key bundles imported again.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SecretField("PIN", pin, { pin = it }, enabled = !state.busy)
    SecretField("Repeat the PIN", confirm, { confirm = it }, enabled = !state.busy, onDone = { actions.onCreate(pin, confirm) })
    Button(onClick = { actions.onCreate(pin, confirm) }, enabled = !state.busy) { Text("Create vault") }
}

@Composable
private fun UnlockSection(state: SigningKeysUiState, actions: SigningKeysActions) {
    var pin by remember { mutableStateOf("") }
    StatusChip("Locked", Status.WARN, icon = KftIcons.Lock)
    SecretField("PIN", pin, { pin = it }, enabled = !state.busy, onDone = { actions.onUnlock(pin) })
    Button(onClick = { actions.onUnlock(pin) }, enabled = !state.busy && pin.isNotEmpty()) { Text("Unlock") }
}

@Composable
private fun KeysSection(state: SigningKeysUiState, actions: SigningKeysActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        StatusChip("Unlocked", Status.OK, icon = KftIcons.LockOpen)
        OutlinedButton(onClick = actions.onLock) { Text("Lock now") }
    }
    SectionHeader("Keys on this device")
    if (state.keys.isEmpty()) {
        Text("None yet. Import the key bundle made by the provisioning tool.", style = MaterialTheme.typography.bodyMedium)
    }
    state.keys.forEachIndexed { i, key ->
        if (i > 0) HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(key.label, style = MaterialTheme.typography.titleMedium)
                Text("${key.shortId} · ${key.version}", style = MaterialTheme.typography.bodyMedium)
                Text(key.lastUsed, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TooltipIconButton(KftIcons.Delete, "Remove ${key.label}", { actions.onRemove(key.droneId) })
        }
    }
    Button(onClick = actions.onImport, enabled = !state.busy) {
        Icon(KftIcons.Import, contentDescription = null)
        Text("Import key bundle…", Modifier.padding(start = Spacing.s))
    }
}

/** The start-up dialog: PIN and Unlock, or Skip (vehicles that sign then show "Keys locked"). */
@Composable
internal fun SigningUnlockDialog(state: SigningKeysUiState, onUnlock: (String) -> Unit, onSkip: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onSkip,
        icon = { Icon(KftIcons.Lock, contentDescription = null) },
        title = { Text("Unlock signing keys") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text("Enter the PIN to talk to drones that sign their MAVLink. Skip to fly drones that don't.")
                SecretField("PIN", pin, { pin = it }, enabled = !state.busy, onDone = { onUnlock(pin) })
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { onUnlock(pin) }, enabled = !state.busy && pin.isNotEmpty()) { Text("Unlock") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("Skip") } },
    )
}

@Composable
private fun PassphraseDialog(busy: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Bundle passphrase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text("Type the six words that were read out with this bundle. They work once, for this file only.")
                SecretField("Passphrase", passphrase, { passphrase = it }, enabled = !busy, onDone = { onSubmit(passphrase) })
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(passphrase) }, enabled = !busy && passphrase.isNotBlank()) { Text("Import") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** A masked text field for a PIN or passphrase. Not remembered by the keyboard, and Done submits. */
@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean, onDone: (() -> Unit)? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = Modifier.fillMaxWidth(),
    )
}
