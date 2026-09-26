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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
)

/** Stateless: the toolbar, the search box and the parameter list, plus the edit and file dialogs when open. */
@Composable
fun ParamsScreen(state: ParamsUiState, actions: ParamsActions) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Parameters", style = MaterialTheme.typography.titleLarge)
            Text(state.status, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(actions.onDownload, enabled = state.canDownload) { Text("Download") }
            OutlinedButton(actions.onLoadFile, enabled = state.canWrite) { Text("Load file…") }
            OutlinedButton(actions.onSaveFile, enabled = state.canSave) { Text("Save file…") }
        }
        state.writeHint?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium) }
        OutlinedTextField(
            state.query, actions.onQueryChanged, Modifier.fillMaxWidth(),
            label = { Text("Search by name") }, singleLine = true,
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.rows, key = { it.name }) { row ->
                ParamLine(row, enabled = state.canWrite, onClick = { actions.onParamClicked(row.name) })
                HorizontalDivider()
            }
        }
    }
    state.edit?.let { EditDialog(it, actions) }
    state.fileLoad?.let { FileLoadDialog(it, actions) }
}

@Composable
private fun ParamLine(row: ParamRow, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.name, Modifier.width(220.dp), fontFamily = FontFamily.Monospace)
            Text(row.value, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontWeight = if (row.modified) FontWeight.Bold else null)
            if (row.modified) Text("modified", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        }
        row.note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Two steps in one dialog: type the value and press Set, then read the question and press Confirm. */
@Composable
private fun EditDialog(edit: ParamEdit, actions: ParamsActions) {
    AlertDialog(
        onDismissRequest = actions.onEditDismiss,
        title = { Text(edit.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Current value ${edit.current} (${edit.type})")
                OutlinedTextField(
                    edit.text, actions.onEditTextChanged, singleLine = true,
                    isError = edit.error != null, supportingText = edit.error?.let { { Text(it) } },
                )
                edit.confirm?.let { Text(it, fontWeight = FontWeight.Bold) }
            }
        },
        confirmButton = {
            if (edit.confirm == null) TextButton(actions.onEditSet) { Text("Set") }
            else TextButton(actions.onEditConfirm) { Text("Confirm") }
        },
        dismissButton = { TextButton(actions.onEditDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FileLoadDialog(load: FileLoad, actions: ParamsActions) {
    AlertDialog(
        onDismissRequest = actions.onFileLoadDismiss,
        title = { Text("Write ${load.changes.size} parameters from ${load.fileName}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                load.skipped?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
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
