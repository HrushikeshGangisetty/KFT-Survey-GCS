package com.kft.gcs.feature.params

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kft.gcs.core.geoio.encodeParamFile
import com.kft.gcs.core.geoio.parseParamFile
import com.kft.gcs.core.vehicle.Param
import com.kft.gcs.core.vehicle.ParamRepository
import com.kft.gcs.core.vehicle.ParamSetResult
import com.kft.gcs.core.vehicle.VehicleState
import com.kft.gcs.core.vehicle.formatParamValue
import com.kft.gcs.core.vehicle.parseParamInput
import com.kft.gcs.core.vehicle.valueText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The platform's "Save as…" and "Open…" dialogs, for `.param` files. The app backs it with the same dialogs as plan
 * files; it's declared here because features never import each other (CLAUDE.md §2). Both return null on cancel.
 */
interface ParamFiles {
    /** Asks where to save, suggesting [suggestedName], writes [text] there, and returns the chosen file's name. */
    suspend fun save(suggestedName: String, text: String): String?

    /** Asks for a file and returns (its name, its text). */
    suspend fun open(): Pair<String, String>?
}

sealed interface ParamsEffect {
    data class ShowMessage(val text: String) : ParamsEffect
}

/**
 * The Params screen. The parameter list lives here, not in the repository: it's a snapshot the operator asked for,
 * and only this screen uses it. Every write goes through [ParamRepository.set], which reports what the vehicle
 * actually holds afterwards, and that is what the list shows.
 *
 * "Modified" compares with the values at download time, so after a set the operator sees what this session
 * changed. Without ArduPilot's parameter metadata (see the pass 18 log: GPL-derived, not bundled) there is no
 * default to compare with instead.
 */
class ParamsViewModel(
    private val vehicle: StateFlow<VehicleState>,
    private val repository: ParamRepository,
    private val files: ParamFiles,
) : ViewModel() {

    private data class Model(
        val params: List<Param> = emptyList(),
        val downloaded: Map<String, Float> = emptyMap(),
        val notes: Map<String, String> = emptyMap(),
        val query: String = "",
        val progress: String? = null, // non-null while a download or a batch of writes runs
        val edit: ParamEdit? = null,
        val fileLoad: FileLoad? = null,
    )

    private val model = MutableStateFlow(Model())

    val state: StateFlow<ParamsUiState> = combine(model, vehicle, ::buildUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), buildUiState(model.value, vehicle.value))

    private val _effects = Channel<ParamsEffect>(Channel.BUFFERED)
    val effects: Flow<ParamsEffect> = _effects.receiveAsFlow()

    fun onDownloadClicked() = runExclusive("Downloading…") {
        repository.downloadAll { done, total -> model.update { it.copy(progress = "Downloading $done / $total") } }
            .onSuccess { list ->
                model.update { it.copy(params = list, downloaded = list.associate { p -> p.name to p.value }, notes = emptyMap()) }
                say("Downloaded ${list.size} parameters")
            }
            .onFailure { say("Download failed: ${it.message}") }
    }

    fun onQueryChanged(query: String) = model.update { it.copy(query = query) }

    fun onParamClicked(name: String) {
        if (!canWrite()) return
        val p = param(name) ?: return
        model.update { it.copy(edit = ParamEdit(p.name, p.type.name, p.valueText, p.valueText, error = null, confirm = null)) }
    }

    fun onEditTextChanged(text: String) = model.update { m -> m.copy(edit = m.edit?.copy(text = text, error = null, confirm = null)) }

    /** First step: check the value. Nothing is sent until [onEditConfirmClicked]. */
    fun onEditSetClicked() {
        val edit = model.value.edit ?: return
        val p = param(edit.name) ?: return
        parseParamInput(edit.text, p.type)
            .onSuccess { value ->
                val error = if (value == p.value) "That's the current value" else null
                val confirm = if (error == null) "Change ${p.name} from ${p.valueText} to ${formatParamValue(value, p.type)} on the vehicle?" else null
                model.update { it.copy(edit = edit.copy(error = error, confirm = confirm)) }
            }
            .onFailure { e -> model.update { it.copy(edit = edit.copy(error = e.message, confirm = null)) } }
    }

    fun onEditConfirmClicked() {
        val edit = model.value.edit?.takeIf { it.confirm != null } ?: return
        val p = param(edit.name) ?: return
        val value = parseParamInput(edit.text, p.type).getOrNull() ?: return
        model.update { it.copy(edit = null) }
        runExclusive("Writing ${p.name}…") {
            val result = write(p, value)
            say(if (result is ParamSetResult.Applied) "${p.name} set to ${result.param.valueText}" else "${p.name}: ${(result as ParamSetResult.NotApplied).reason}")
        }
    }

    fun onEditDismissed() = model.update { it.copy(edit = null) }

    fun onSaveFileClicked() {
        val params = model.value.params
        if (params.isEmpty()) return say("Download the parameters first")
        viewModelScope.launch {
            val name = files.save("vehicle.param", encodeParamFile(params.associate { it.name to it.valueText })) ?: return@launch
            say("Saved ${params.size} parameters to $name")
        }
    }

    /** Reads a `.param` file and lists what it would change. Nothing is sent until [onFileLoadConfirmed]. */
    fun onLoadFileClicked() {
        if (model.value.params.isEmpty()) return say("Download the parameters first, so the file can be compared with the vehicle")
        if (!canWrite()) return
        viewModelScope.launch {
            val (name, text) = files.open() ?: return@launch
            val file = parseParamFile(text)
            val byName = model.value.params.associateBy { it.name }
            val changes = mutableListOf<ParamChange>()
            var unknown = 0
            var invalid = 0
            for ((key, value) in file.values) {
                val p = byName[key] ?: run { unknown++; null } ?: continue
                // Checked like typed input: a fraction for an integer parameter would be truncated by the vehicle.
                val checked = parseParamInput(value.toString(), p.type).getOrNull() ?: run { invalid++; null } ?: continue
                if (checked != p.value) changes += ParamChange(p.name, p.valueText, formatParamValue(checked, p.type))
            }
            val skipped = listOfNotNull(
                unknown.takeIf { it > 0 }?.let { "$it not on this vehicle" },
                invalid.takeIf { it > 0 }?.let { "$it with a value the vehicle can't store" },
                file.badLines.takeIf { it.isNotEmpty() }?.let { "unreadable lines ${it.joinToString(", ")}" },
            ).joinToString("; ").ifEmpty { null }
            if (changes.isEmpty()) return@launch say("$name: nothing to change" + (skipped?.let { " ($it)" } ?: ""))
            model.update { it.copy(fileLoad = FileLoad(name, changes, skipped?.let { s -> "Skipped: $s." })) }
        }
    }

    /** Writes the file's changes one by one. A parameter that doesn't take is noted on its row and the rest go on. */
    fun onFileLoadConfirmed() {
        val load = model.value.fileLoad ?: return
        model.update { it.copy(fileLoad = null) }
        runExclusive("Writing…") {
            var applied = 0
            load.changes.forEachIndexed { i, change ->
                model.update { it.copy(progress = "Writing ${i + 1} / ${load.changes.size}") }
                val p = param(change.name) ?: return@forEachIndexed
                val value = parseParamInput(change.to, p.type).getOrNull() ?: return@forEachIndexed
                if (write(p, value) is ParamSetResult.Applied) applied++
            }
            val failed = load.changes.size - applied
            say("${load.fileName}: $applied of ${load.changes.size} written" + if (failed > 0) ", $failed not applied (see the marked rows)" else "")
        }
    }

    fun onFileLoadDismissed() = model.update { it.copy(fileLoad = null) }

    /** One write, with the list updated to what the vehicle reported. */
    private suspend fun write(p: Param, value: Float): ParamSetResult {
        val result = repository.set(p, value)
        val now = when (result) {
            is ParamSetResult.Applied -> result.param
            is ParamSetResult.NotApplied -> result.vehicleValue
        }
        model.update { m ->
            m.copy(
                params = if (now == null) m.params else m.params.map { if (it.name == now.name) now else it },
                notes = if (result is ParamSetResult.NotApplied) m.notes + (p.name to result.reason) else m.notes - p.name,
            )
        }
        return result
    }

    /** Runs [block] unless something else is running: parameter transfers are one at a time on the link anyway. */
    private fun runExclusive(label: String, block: suspend () -> Unit) {
        if (model.value.progress != null) return
        model.update { it.copy(progress = label) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                model.update { it.copy(progress = null) }
            }
        }
    }

    private fun canWrite() = writable(model.value, vehicle.value)

    private fun ready(v: VehicleState) = v.connected && v.login?.allowsTraffic != false

    private fun writable(m: Model, v: VehicleState) = ready(v) && !v.armed && m.params.isNotEmpty() && m.progress == null

    private fun param(name: String) = model.value.params.find { it.name == name }

    private fun say(text: String) {
        _effects.trySend(ParamsEffect.ShowMessage(text))
    }

    private fun buildUiState(m: Model, v: VehicleState): ParamsUiState {
        val modified = m.params.count { m.downloaded[it.name] != it.value }
        val hint = when {
            !v.connected -> "Connect a vehicle to read or change parameters"
            v.login?.allowsTraffic == false -> "${v.login?.label}. Parameters wait for the login."
            m.params.isEmpty() -> null
            v.armed -> "Disarm to change parameters"
            else -> null
        }
        return ParamsUiState(
            status = m.progress ?: if (m.params.isEmpty()) "Not downloaded" else "${m.params.size} parameters · $modified modified",
            canDownload = ready(v) && m.progress == null,
            canWrite = writable(m, v),
            canSave = m.params.isNotEmpty(),
            writeHint = hint,
            query = m.query,
            rows = m.params.asSequence()
                .filter { m.query.isBlank() || it.name.contains(m.query.trim(), ignoreCase = true) }
                .sortedBy { it.name }
                .map { ParamRow(it.name, it.valueText, m.downloaded[it.name] != it.value, m.notes[it.name]) }
                .toList(),
            edit = m.edit,
            fileLoad = m.fileLoad,
        )
    }
}
