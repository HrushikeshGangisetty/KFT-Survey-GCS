package com.kft.gcs.feature.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kft.gcs.core.mavlink.VehicleId
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.vehicle.Param
import com.kft.gcs.core.vehicle.ParamRepository
import com.kft.gcs.core.vehicle.ParamSetResult
import com.kft.gcs.core.vehicle.VehicleState
import com.kft.gcs.core.vehicle.parseParamInput
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

/** One-shot messages from the Setup tab (both ViewModels), shown as a snackbar. */
sealed interface SetupEffect {
    data class ShowMessage(val text: String) : SetupEffect
}

/**
 * The Setup tab's pages, its parameter pages (flight modes, failsafes) and the Hardware ID page.
 *
 * Parameter values come from [ParamRepository.known], the list the Params tab downloads at connect, so opening Setup
 * costs no radio traffic. Every change is checked ([checkValue]), confirmed by the operator, then written through
 * [ParamRepository.set], which the TX gateway allows only while disarmed. What the vehicle echoes back is what the
 * page then shows.
 *
 * Several vehicles: [params] and [vehicle] are the selected vehicle's. An edit remembers the vehicle it was opened
 * for and is refused if the selection changed before it was confirmed.
 */
class SetupViewModel(
    private val vehicle: StateFlow<VehicleState>,
    private val params: ParamRepository,
    private val selectedId: StateFlow<VehicleId?> = MutableStateFlow(null),
) : ViewModel() {

    private data class Model(val page: SetupPage = SetupPage.ACCEL, val busy: String? = null, val edit: FieldEdit? = null, val editFor: VehicleId? = null)

    private val model = MutableStateFlow(Model())

    val state: StateFlow<SetupUiState> = combine(model, vehicle, params.known, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), build(model.value, vehicle.value, params.known.value))

    private val _effects = Channel<SetupEffect>(Channel.BUFFERED)
    val effects: Flow<SetupEffect> = _effects.receiveAsFlow()

    fun onPageSelected(page: SetupPage) = model.update { it.copy(page = page, edit = null) }

    /** Reads every parameter again (the Params tab does this at connect; this is for a fresh copy). */
    fun onDownloadClicked() {
        if (!ready(vehicle.value) || model.value.busy != null) return
        model.update { it.copy(busy = "Reading parameters…") }
        viewModelScope.launch {
            params.downloadAll { done, total -> model.update { it.copy(busy = "Reading parameters $done / $total") } }
                .onSuccess { say("Read ${it.size} parameters") }
                .onFailure { say("Reading failed: ${it.message}") }
            model.update { it.copy(busy = null) }
        }
    }

    fun onFieldClicked(param: String) {
        if (!writable()) return
        val spec = specs().firstOrNull { it.param == param } ?: return
        val p = params.known.value[param] ?: return
        val text = if (spec.kind is FieldKind.Number) number(p.value.toDouble()) else p.value.toLong().toString()
        model.update { it.copy(edit = FieldEdit(param, spec.label, spec.help, displayValue(spec.kind, p.value), spec.kind, text), editFor = selectedId.value) }
    }

    fun onEditTextChanged(text: String) = model.update { m -> m.copy(edit = m.edit?.copy(text = text, error = null, confirm = null)) }

    /** First step: check the value. Nothing is sent until [onEditConfirmClicked]. */
    fun onEditSetClicked() {
        val edit = model.value.edit ?: return
        val p = params.known.value[edit.param] ?: return
        val value = parseParamInput(edit.text, p.type).getOrElse { return setError(it.message) }
        checkValue(edit.kind, value)?.let { return setError(it) }
        if (value == p.value) return setError("That's the current value")
        val shown = displayValue(edit.kind, value)
        val confirm = buildString {
            append("Change ${edit.label} (${edit.param}) from ${edit.current} to $shown?")
            if (edit.kind is FieldKind.Choice && value.toInt() in edit.kind.danger) append(" Warning: $shown stops the motors in flight; the aircraft falls.")
            // What this write would leave the FC refusing to arm with, that it isn't refusing already.
            val before = armingProblems(vehicle.value.vehicleKind, values())
            armingProblems(vehicle.value.vehicleKind, values() + (edit.param to value)).filter { it !in before }.forEach { append(" Note: $it") }
        }
        model.update { it.copy(edit = edit.copy(error = null, confirm = confirm)) }
    }

    fun onEditConfirmClicked() {
        val m = model.value
        val edit = m.edit?.takeIf { it.confirm != null } ?: return
        val p = params.known.value[edit.param] ?: return
        val value = parseParamInput(edit.text, p.type).getOrNull() ?: return
        model.update { it.copy(edit = null) }
        if (selectedId.value != m.editFor) return say("The selected vehicle changed. Nothing was sent.")
        model.update { it.copy(busy = "Writing ${edit.param}…") }
        viewModelScope.launch {
            when (val r = params.set(p, value)) {
                is ParamSetResult.Applied -> say("${edit.label} set to ${displayValue(edit.kind, r.param.value)}")
                is ParamSetResult.NotApplied -> say("${edit.label}: ${r.reason}")
            }
            model.update { it.copy(busy = null) }
        }
    }

    fun onEditDismissed() = model.update { it.copy(edit = null) }

    private fun setError(text: String?) = model.update { m -> m.copy(edit = m.edit?.copy(error = text ?: "Not a valid value", confirm = null)) }

    private fun values(): Map<String, Float> = params.known.value.mapValues { it.value.value }

    private fun specs(): List<FieldSpec> = sectionsFor(model.value.page, vehicle.value.vehicleKind).flatMap { it.fields }

    private fun ready(v: VehicleState) = v.connected && v.login?.allowsTraffic != false

    private fun writable() = writable(model.value, vehicle.value, params.known.value)

    private fun writable(m: Model, v: VehicleState, known: Map<String, Param>) = ready(v) && !v.armed && known.isNotEmpty() && m.busy == null

    private fun say(text: String) {
        _effects.trySend(SetupEffect.ShowMessage(text))
    }

    private fun build(m: Model, v: VehicleState, known: Map<String, Param>): SetupUiState {
        val values = known.mapValues { it.value.value }
        val sections = sectionsFor(m.page, v.vehicleKind).map { s ->
            FieldSection(s.title, s.note, s.fields.map { f ->
                val p = known[f.param]
                FieldRow(f.param, f.label, p?.let { displayValue(f.kind, it.value) } ?: "—", f.help, p != null)
            })
        }
        val switch = modeSwitch(v, values)
        return SetupUiState(
            page = m.page,
            vehicle = vehicleLine(v),
            notice = when {
                !v.connected -> "Connect a vehicle to set it up."
                v.login?.allowsTraffic == false -> "${v.login?.label}. Setup waits for the login."
                v.armed -> "The vehicle is armed. Calibrations and changes are only possible on the ground, disarmed."
                v.vehicleKind == VehicleKind.UNKNOWN || v.vehicleKind == null -> "Vehicle type unknown: the Copter lists are shown."
                else -> null
            },
            params = m.busy ?: if (known.isEmpty()) "Parameters not read yet" else "${known.size} parameters read",
            canDownload = ready(v) && m.busy == null,
            canWrite = writable(m, v, known),
            busy = m.busy != null,
            sections = sections,
            problems = if (m.page == SetupPage.FAILSAFES) armingProblems(v.vehicleKind, values) else emptyList(),
            modeSwitch = if (m.page == SetupPage.FLIGHT_MODES) switch?.first else null,
            activeSlot = if (m.page == SetupPage.FLIGHT_MODES) switch?.second else null,
            edit = m.edit,
            board = boardRows(v),
            sensors = sensorRows(values),
        )
    }
}

internal fun sectionsFor(page: SetupPage, kind: VehicleKind?): List<SectionSpec> = when (page) {
    SetupPage.FAILSAFES -> failsafeSections(kind)
    SetupPage.FLIGHT_MODES -> flightModeSections(kind)
    else -> emptyList()
}

private fun vehicleLine(v: VehicleState): String {
    if (!v.connected) return "No vehicle connected"
    val kind = when (v.vehicleKind) {
        VehicleKind.COPTER -> "Copter"
        VehicleKind.PLANE -> "Plane"
        else -> "Vehicle"
    }
    return listOfNotNull(kind, v.firmwareVersion, if (v.armed) "armed" else "disarmed", v.flightMode).joinToString(" · ")
}

/** "Channel 5 at 1495 µs: position 4", and that position (0-based), from the live RC and FLTMODE_CH. */
private fun modeSwitch(v: VehicleState, values: Map<String, Float>): Pair<String, Int?>? {
    val channel = values["FLTMODE_CH"]?.toInt() ?: return null
    if (channel == 0) return "No mode switch: FLTMODE_CH is disabled." to null
    val pwm = v.rcChannels.getOrNull(channel - 1) ?: return "Channel $channel: no RC signal." to null
    val slot = modeSlot(pwm)
    return "Channel $channel at $pwm µs: " + (slot?.let { "position ${it + 1}" } ?: "out of range, the FC ignores it") to slot
}

private fun boardRows(v: VehicleState): List<Pair<String, String>> {
    val a = v.autopilot ?: return emptyList()
    return listOfNotNull(
        "Firmware" to (a.firmware + (a.gitHash?.let { " ($it)" } ?: "")),
        a.boardId?.let { "Board type" to "$it" },
        a.usbId?.let { "USB id" to it },
        a.uid?.let { "Unique id" to it },
    )
}
