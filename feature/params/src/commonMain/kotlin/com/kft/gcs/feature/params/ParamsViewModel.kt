package com.kft.gcs.feature.params

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kft.gcs.core.geoio.ParamMeta
import com.kft.gcs.core.geoio.PdefVehicle
import com.kft.gcs.core.geoio.encodeParamFile
import com.kft.gcs.core.geoio.parsePdef
import com.kft.gcs.core.geoio.pdefCacheKey
import com.kft.gcs.core.geoio.pdefUrls
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.geoio.parseParamFile
import com.kft.gcs.core.vehicle.Param
import com.kft.gcs.core.vehicle.ParamRepository
import com.kft.gcs.core.vehicle.ParamSetResult
import com.kft.gcs.core.vehicle.VehicleState
import com.kft.gcs.core.vehicle.formatParamValue
import com.kft.gcs.core.vehicle.parseParamInput
import com.kft.gcs.core.vehicle.valueText
import kotlinx.coroutines.CancellationException
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

/**
 * Where ArduPilot's parameter descriptions come from: autotest.ardupilot.org, and a copy per vehicle and firmware
 * version kept on this device so the field (no internet) still has them. The app backs it with plain HTTP and files.
 * Nothing is bundled with the app (see [com.kft.gcs.core.geoio.ParamMeta] for why).
 */
interface MetadataSource {
    /** The saved text for [key] (see [pdefCacheKey]), or null if there's none. */
    suspend fun cached(key: String): String?

    suspend fun save(key: String, text: String)

    /** The file at [url], or null when the server says it isn't there. Throws when there's no connection. */
    suspend fun download(url: String): String?
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
 * changed.
 *
 * Descriptions, units, ranges, value names and bits come from ArduPilot's parameter metadata for the connected
 * vehicle and firmware version ([loadMetadata], after each download), or from a file the operator imports. The list
 * works without them; they only add help and checks.
 */
class ParamsViewModel(
    private val vehicle: StateFlow<VehicleState>,
    private val repository: ParamRepository,
    private val files: ParamFiles,
    private val metadata: MetadataSource,
) : ViewModel() {

    private data class Model(
        val params: List<Param> = emptyList(),
        val downloaded: Map<String, Float> = emptyMap(),
        val notes: Map<String, String> = emptyMap(),
        val query: String = "",
        val progress: String? = null, // non-null while a download or a batch of writes runs
        val edit: ParamEdit? = null,
        val fileLoad: FileLoad? = null,
        val meta: Map<String, ParamMeta> = emptyMap(),
        val metaKey: String? = null,
        val metaStatus: String? = null,
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
                loadMetadata(vehicle.value)
            }
            .onFailure { say("Download failed: ${it.message}") }
    }

    /**
     * Imports an `apm.pdef.json` / `.xml` by hand: for KFT firmware (generated from the KFT fork, so KFT_ parameters
     * have descriptions too), or when there's no internet. It's saved for the connected vehicle's version, so it's
     * used from then on instead of a download.
     */
    fun onImportMetadataClicked() {
        viewModelScope.launch {
            val (name, text) = files.open() ?: return@launch
            val meta = parsePdef(text)
            if (meta.isEmpty()) return@launch say("$name is not a parameter metadata file (apm.pdef.json or .xml)")
            val key = pdefVehicle(vehicle.value)?.let { pdefCacheKey(it, vehicle.value.firmwareVersion) }
            if (key != null) metadata.save(key, text)
            model.update { it.copy(meta = meta, metaKey = key, metaStatus = "Descriptions from $name (${meta.size})") }
            say("Imported ${meta.size} parameter descriptions")
        }
    }

    /**
     * Metadata for [v]'s vehicle and firmware: the saved copy if there is one (it may be an import), otherwise the
     * downloads from [pdefUrls] in order, the first that parses winning and being saved. Failures only set the
     * status line; the parameter list works without metadata.
     */
    private suspend fun loadMetadata(v: VehicleState) {
        val pv = pdefVehicle(v) ?: return model.update { it.copy(metaStatus = "No descriptions: unknown vehicle type") }
        val version = v.firmwareVersion
        val key = pdefCacheKey(pv, version)
        if (model.value.metaKey == key && model.value.meta.isNotEmpty()) return
        val label = "${pv.latest} ${version ?: "(version unknown)"}"
        fun loaded(meta: Map<String, ParamMeta>, how: String) =
            model.update { it.copy(meta = meta, metaKey = key, metaStatus = "Descriptions: $label, $how") }

        metadata.cached(key)?.let(::parsePdef)?.takeIf { it.isNotEmpty() }?.let { return loaded(it, "saved on this device") }
        model.update { it.copy(metaStatus = "Downloading descriptions for $label…") }
        for (url in pdefUrls(pv, version)) {
            val text = try {
                metadata.download(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return model.update { it.copy(metaStatus = "No descriptions: couldn't reach autotest.ardupilot.org (${e.message}). Import a file instead.") }
            } ?: continue
            val meta = parsePdef(text)
            if (meta.isEmpty()) continue
            metadata.save(key, text)
            return loaded(meta, "downloaded")
        }
        model.update { it.copy(metaStatus = "No descriptions for $label on autotest.ardupilot.org. Import a file instead.") }
    }

    fun onQueryChanged(query: String) = model.update { it.copy(query = query) }

    fun onParamClicked(name: String) {
        if (!canWrite()) return
        val p = param(name) ?: return
        model.update { it.copy(edit = editFor(p, it.meta[p.name], p.valueText)) }
    }

    fun onEditTextChanged(text: String) = model.update { m ->
        val edit = m.edit ?: return@update m
        m.copy(edit = editFor(param(edit.name) ?: return@update m, m.meta[edit.name], text))
    }


    /** First step: check the value. Nothing is sent until [onEditConfirmClicked]. */
    fun onEditSetClicked() {
        val edit = model.value.edit ?: return
        val p = param(edit.name) ?: return
        val meta = model.value.meta[p.name]
        if (meta?.readOnly == true) return model.update { it.copy(edit = edit.copy(error = "Read-only: ArduPilot doesn't let this be changed")) }
        parseParamInput(edit.text, p.type)
            .onSuccess { value ->
                val range = meta?.range
                val outside = range != null && !range.holds(value)
                // The vehicle already holding an out-of-range value means the documented range doesn't fit this
                // aircraft or firmware (a KFT build, an old metadata file): then the operator may override, with a
                // warning. Otherwise out of range is refused.
                val overridable = range != null && !range.holds(p.value)
                val error = when {
                    value == p.value -> "That's the current value"
                    outside && !overridable -> "Outside the documented range ${rangeText(range, meta.units)}. Not sent."
                    else -> null
                }
                val confirm = if (error != null) null else buildString {
                    if (outside) append("Warning: ${formatParamValue(value, p.type)} is outside the documented range ${rangeText(range, meta.units)}; the vehicle already holds ${p.valueText}, also outside it. ")
                    append("Change ${p.name} from ${p.valueText} to ${formatParamValue(value, p.type)} on the vehicle?")
                    if (meta?.rebootRequired == true) append(" It takes effect after the flight controller restarts.")
                }
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
        val q = m.query.trim()
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
            metadata = m.metaStatus,
            rows = m.params.asSequence()
                .filter { p ->
                    val meta = m.meta[p.name]
                    q.isEmpty() || p.name.contains(q, ignoreCase = true) ||
                        meta?.displayName?.contains(q, ignoreCase = true) == true || meta?.description?.contains(q, ignoreCase = true) == true
                }
                // By group, then name: a plain name sort can split a group ("AB", "ABC_Y", "AB_X").
                .sortedWith(compareBy({ groupOf(it.name) }, { it.name }))
                .map { p ->
                    val meta = m.meta[p.name]
                    ParamRow(
                        name = p.name,
                        value = p.valueText,
                        modified = m.downloaded[p.name] != p.value,
                        note = m.notes[p.name],
                        group = groupOf(p.name),
                        displayName = meta?.displayName?.takeIf { it.isNotBlank() },
                        valueLabel = meta?.values?.firstOrNull { (code, _) -> code == p.value.toDouble() }?.second,
                        units = meta?.units,
                        rebootRequired = meta?.rebootRequired == true,
                        readOnly = meta?.readOnly == true,
                    )
                }
                .toList(),
            edit = m.edit,
            fileLoad = m.fileLoad,
        )
    }
}

/** Our vehicle kind as autotest.ardupilot.org names it; null when the vehicle type isn't known yet. */
private fun pdefVehicle(v: VehicleState) = when (v.vehicleKind) {
    VehicleKind.COPTER -> PdefVehicle.COPTER
    VehicleKind.PLANE -> PdefVehicle.PLANE
    else -> null
}

/** The group a parameter is listed under: its name up to the first "_" ("CAM1_TYPE" → "CAM1"). */
internal fun groupOf(name: String) = name.substringBefore('_')

/**
 * In range, allowing for float storage: 0.1 sent as a float is 0.10000000149…, which must not count as above a
 * documented maximum of 0.1.
 */
internal fun ClosedFloatingPointRange<Double>.holds(value: Float): Boolean {
    val slack = 1e-5 * maxOf(1.0, kotlin.math.abs(start), kotlin.math.abs(endInclusive))
    return value.toDouble() in (start - slack)..(endInclusive + slack)
}

private fun rangeText(r: ClosedFloatingPointRange<Double>, units: String?) =
    "${number(r.start)} to ${number(r.endInclusive)}${units?.let { " $it" } ?: ""}"

private fun number(d: Double) = if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()

/** The edit dialog for [p] with [text] typed, carrying the metadata's help and choices. */
private fun editFor(p: Param, meta: ParamMeta?, text: String): ParamEdit {
    return ParamEdit(
        name = p.name,
        type = p.type.name,
        current = p.valueText,
        text = text,
        error = null,
        confirm = null,
        displayName = meta?.displayName?.takeIf { it.isNotBlank() },
        description = meta?.description?.takeIf { it.isNotBlank() },
        facts = listOfNotNull(
            meta?.units?.let { "Units $it" },
            meta?.range?.let { "Range ${rangeText(it, null)}" },
            meta?.increment?.let { "Step ${number(it)}" },
            "Needs a restart".takeIf { meta?.rebootRequired == true },
        ).joinToString(" · ").ifEmpty { null },
        readOnly = meta?.readOnly == true,
        choices = meta?.values?.map { (code, label) -> number(code) to label } ?: emptyList(),
        bits = meta?.bitmask?.map { (bit, label) -> BitChoice(bit, label) } ?: emptyList(),
    )
}

/**
 * [text] with [bit] flipped: what a bitmask checkbox does. An unreadable value counts as 0, so ticking a box in an
 * empty field gives just that bit. The dialog applies it to its own text (see `EditDialog`), not via the ViewModel.
 */
internal fun toggleBit(text: String, bit: Int): String = ((text.trim().toDoubleOrNull()?.toLong() ?: 0L) xor (1L shl bit)).toString()

/** Whether [text], read as a whole number, has [bit] set: a checkbox's tick. */
internal fun hasBit(text: String, bit: Int): Boolean = text.trim().toDoubleOrNull()?.toLong()?.let { (it shr bit) and 1L == 1L } ?: false
