package com.kft.gcs.feature.params

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kft.gcs.ui.design.KftIcons
import com.kft.gcs.ui.design.KftToolbar
import com.kft.gcs.ui.design.MinTouchTarget
import com.kft.gcs.ui.design.Spacing
import com.kft.gcs.ui.design.Status
import com.kft.gcs.ui.design.StatusChip
import com.kft.gcs.ui.design.ToolbarEntry
import org.koin.compose.viewmodel.koinViewModel

/** Gets the ViewModel from Koin and wires the stateless [ParamsScreen] to it. */
@Composable
fun ParamsRoute(viewModel: ParamsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ParamsEffect.ShowMessage -> snackbar.showSnackbar(effect.text)
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        ParamsScreen(
            state,
            ParamsActions(
                onDownload = viewModel::onDownloadClicked,
                onQueryChanged = viewModel::onQueryChanged,
                onParamClicked = viewModel::onParamClicked,
                onEditTextChanged = viewModel::onEditTextChanged,
                onEditSet = viewModel::onEditSetClicked,
                onEditConfirm = viewModel::onEditConfirmClicked,
                onEditDismiss = viewModel::onEditDismissed,
                onSaveFile = viewModel::onSaveFileClicked,
                onLoadFile = viewModel::onLoadFileClicked,
                onFileLoadConfirm = viewModel::onFileLoadConfirmed,
                onFileLoadDismiss = viewModel::onFileLoadDismissed,
                onImportMetadata = viewModel::onImportMetadataClicked,
            ),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/** The screen's events, grouped so the screen takes two arguments instead of twelve. */
class ParamsActions(
    val onDownload: () -> Unit = {},
    val onQueryChanged: (String) -> Unit = {},
    val onParamClicked: (String) -> Unit = {},
    val onEditTextChanged: (String) -> Unit = {},
    val onEditSet: () -> Unit = {},
    val onEditConfirm: () -> Unit = {},
    val onEditDismiss: () -> Unit = {},
    val onSaveFile: () -> Unit = {},
    val onLoadFile: () -> Unit = {},
    val onFileLoadConfirm: () -> Unit = {},
    val onFileLoadDismiss: () -> Unit = {},
    val onImportMetadata: () -> Unit = {},
)

/**
 * Stateless: title and status, the vehicle/file actions as the same icon toolbar the Plan screen uses (download from
 * the vehicle | open, save a file), the search box and the parameter list, plus the edit and file dialogs when open.
 */
@Composable
fun ParamsScreen(state: ParamsUiState, actions: ParamsActions) {
    Column(Modifier.fillMaxSize().padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Column(Modifier.weight(1f)) {
                Text("Parameters", style = MaterialTheme.typography.titleLarge)
                Text(state.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.metadata?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            KftToolbar(
                listOf(
                    ToolbarEntry.Action(KftIcons.Download, "Download from vehicle", state.canDownload, actions.onDownload),
                    ToolbarEntry.Divider,
                    ToolbarEntry.Action(KftIcons.Open, "Load .param file (writes to vehicle)", state.canWrite, actions.onLoadFile),
                    ToolbarEntry.Action(KftIcons.Save, "Save .param file", state.canSave, actions.onSaveFile),
                    ToolbarEntry.Divider,
                    ToolbarEntry.Action(KftIcons.Descriptions, "Import parameter descriptions (apm.pdef.json / .xml)", onClick = actions.onImportMetadata),
                ),
            )
        }
        state.writeHint?.let { StatusChip(it, Status.WARN) }
        // The field keeps its own text and reports changes. Fed back through the ViewModel's StateFlow (an async
        // hop), fast typing lost characters: an older value arrived after newer keystrokes and replaced them.
        var query by remember { mutableStateOf(state.query) }
        OutlinedTextField(
            query, { query = it; actions.onQueryChanged(it) }, Modifier.fillMaxWidth(),
            label = { Text("Search by name or description") }, singleLine = true,
            leadingIcon = { Icon(KftIcons.Search, contentDescription = null) },
        )
        Surface(Modifier.fillMaxSize(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
            // An empty panel looks broken; say what fills it. (A search with no match keeps the status line's count.)
            if (state.rows.isEmpty() && state.query.isEmpty()) {
                Text(
                    "Download reads every parameter from the vehicle.",
                    Modifier.padding(Spacing.l),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn {
                // One header per group (the name's prefix), pinned while its rows scroll: with 1400 parameters the
                // operator always sees which family they're in.
                state.rows.groupBy { it.group }.forEach { (group, rows) ->
                    stickyHeader(key = "group-$group") { GroupHeader(group, rows.size) }
                    items(rows, key = { it.name }) { row ->
                        ParamLine(row, enabled = state.canWrite, onClick = { actions.onParamClicked(row.name) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    state.edit?.let { EditDialog(it, actions) }
    state.fileLoad?.let { FileLoadDialog(it, actions) }
}

@Composable
private fun GroupHeader(group: String, count: Int) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(
            "${group}_  ·  $count",
            Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * One parameter: name and value in monospace (so digits and names line up down the list), with what the metadata
 * adds: the display name under the name, the value's documented name and units after it ("1 · Servo", "0.1 s"),
 * and "restart" / "read-only" chips. The vehicle's refusal note goes underneath. At least 48 dp tall: rows are the
 * touch targets for editing on the tablet.
 */
@Composable
private fun ParamLine(row: ParamRow, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Column(Modifier.width(280.dp)) {
                Text(row.name, fontFamily = FontFamily.Monospace)
                row.displayName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            }
            Text(
                row.value + (row.units?.let { " $it" } ?: "") + (row.valueLabel?.let { "  ·  $it" } ?: ""),
                Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                fontWeight = if (row.modified) FontWeight.Bold else null,
            )
            if (row.readOnly) StatusChip("read-only", Status.NEUTRAL)
            if (row.rebootRequired) StatusChip("restart", Status.NEUTRAL)
            if (row.modified) StatusChip("modified", Status.NEUTRAL, icon = KftIcons.Edit)
        }
        row.note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * Two steps in one dialog: choose the value and press Set, then read the question and press Confirm. With metadata
 * the value is chosen the way the parameter works: from its documented values (dropdown), as bits (checkboxes), or
 * typed. Read-only parameters show their help and a Close button only.
 */
@Composable
private fun EditDialog(edit: ParamEdit, actions: ParamsActions) {
    // The value text lives here, like the search text (see ParamsScreen): the dropdown and the checkboxes change
    // this text too, and every change is reported to the ViewModel, which checks it on Set.
    var text by remember(edit.name) { mutableStateOf(edit.text) }
    val setText = { t: String -> text = t; actions.onEditTextChanged(t) }
    AlertDialog(
        onDismissRequest = actions.onEditDismiss,
        title = {
            Column {
                Text(edit.name, fontFamily = FontFamily.Monospace)
                edit.displayName?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        text = {
            Column(Modifier.widthIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                edit.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                edit.facts?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("Current value ${edit.current} (${edit.type})", style = MaterialTheme.typography.bodySmall)
                if (edit.readOnly) {
                    StatusChip("Read-only: ArduPilot doesn't let this be changed", Status.NEUTRAL)
                } else {
                    when {
                        edit.choices.isNotEmpty() -> ValueDropdown(edit.choices, text, setText)
                        edit.bits.isNotEmpty() -> edit.bits.forEach { b ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clickable { setText(toggleBit(text, b.bit)) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(hasBit(text, b.bit), { setText(toggleBit(text, b.bit)) })
                                Text("${b.bit}: ${b.label}")
                            }
                        }
                        else -> Unit
                    }
                    // Always there, so a value the metadata doesn't list can still be typed; for bitmasks it shows the sum.
                    OutlinedTextField(
                        text, setText, singleLine = true, label = { Text("Value") },
                        isError = edit.error != null, supportingText = edit.error?.let { { Text(it) } },
                    )
                    edit.confirm?.let { Text(it, fontWeight = FontWeight.Bold) }
                }
            }
        },
        confirmButton = {
            when {
                edit.readOnly -> TextButton(actions.onEditDismiss) { Text("Close") }
                edit.confirm == null -> TextButton(actions.onEditSet) { Text("Set") }
                else -> TextButton(actions.onEditConfirm) { Text("Confirm") }
            }
        },
        dismissButton = { if (!edit.readOnly) TextButton(actions.onEditDismiss) { Text("Cancel") } },
    )
}

/** The documented values as a dropdown ("1 · Servo"); the typed value's own name shows on the button. */
@Composable
private fun ValueDropdown(choices: List<Pair<String, String>>, text: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = choices.firstOrNull { (code, _) -> code == text.trim() }
    Box {
        OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth()) {
            Text(current?.let { (code, label) -> "$code · $label" } ?: "$text (not a documented value)", Modifier.weight(1f))
            Icon(KftIcons.ExpandMore, contentDescription = "Choose a value")
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            choices.forEach { (code, label) ->
                DropdownMenuItem(text = { Text("$code · $label") }, onClick = { open = false; onPick(code) })
            }
        }
    }
}

@Composable
private fun FileLoadDialog(load: FileLoad, actions: ParamsActions) {
    AlertDialog(
        onDismissRequest = actions.onFileLoadDismiss,
        title = { Text("Write ${load.changes.size} parameters from ${load.fileName}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                load.skipped?.let { StatusChip(it, Status.WARN) }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(load.changes, key = { it.name }) { c ->
                        Text("${c.name}  ${c.from} → ${c.to}", fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = { TextButton(actions.onFileLoadConfirm) { Text("Write to vehicle") } },
        dismissButton = { TextButton(actions.onFileLoadDismiss) { Text("Cancel") } },
    )
}
