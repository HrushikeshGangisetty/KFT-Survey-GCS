package com.kft.gcs.feature.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kft.gcs.core.mavlink.VehicleId
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.vehicle.CompassCalStatus
import com.kft.gcs.core.vehicle.Param
import com.kft.gcs.core.vehicle.ParamRepository
import com.kft.gcs.core.vehicle.ParamSetResult
import com.kft.gcs.core.vehicle.SetupCommand
import com.kft.gcs.core.vehicle.SetupReport
import com.kft.gcs.core.vehicle.SetupResult
import com.kft.gcs.core.vehicle.StatusMessage
import com.kft.gcs.core.vehicle.VehicleSetupRepository
import com.kft.gcs.core.vehicle.VehicleState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The calibration pages and the reboot. One calibration at a time ([CalibrationUiState.running]).
 *
 * ```
 * onXStartClicked ─▶ launchCalibration: listen to setup.reports, then send the start command
 *                       ├─ accelerometer: FC asks for a position ─▶ operator places it ─▶ onAccelPlacedClicked ─▶ … ─▶ AccelDone
 *                       ├─ compass: progress per compass ─▶ a result for every compass in the FC's mask
 *                       ├─ radio: "RC calibrating" on ─▶ sweep (ranges from RC_CHANNELS) ─▶ centre ─▶ review ─▶ save params ─▶ flag off
 *                       └─ level, simple accelerometer, barometer: one command, its ACK is the result
 * ```
 * The vehicle is fixed when a calibration starts. Selecting another vehicle, or losing the link, stops it here and
 * says what may still be running on the FC. [VehicleSetupRepository] and [ParamRepository] always act on the selected
 * vehicle, which is why a change of selection must stop everything.
 */
class CalibrationViewModel(
    private val vehicle: StateFlow<VehicleState>,
    private val setup: VehicleSetupRepository,
    private val params: ParamRepository,
    private val selectedId: StateFlow<VehicleId?> = MutableStateFlow(null),
) : ViewModel() {

    private data class Model(
        val running: Calibration? = null,
        val accel: AccelUi = AccelUi(),
        val imuResult: Outcome? = null,
        val compassAcked: Boolean = false,
        val compassMask: Int = 0,
        val compassRows: Map<Int, CompassRow> = emptyMap(),
        val compassResult: Outcome? = null,
        val heading: String = "",
        val baroResult: Outcome? = null,
        val radioStep: RadioStep = RadioStep.IDLE,
        val ranges: List<RcRange?> = emptyList(),
        val review: RcCalibrationResult? = null,
        val radioResult: Outcome? = null,
        val log: List<StatusMessage> = emptyList(),
        val confirmReboot: Boolean = false,
        val rebootAdvice: String? = null,
    )

    private val model = MutableStateFlow(Model())

    /** The calibration's coroutine, and the vehicle it was started on. Touched on the main thread only. */
    private var job: Job? = null
    private var startedOn: VehicleId? = null

    val state: StateFlow<CalibrationUiState> = combine(model, vehicle, params.known, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), build(model.value, vehicle.value, params.known.value))

    private val _effects = Channel<SetupEffect>(Channel.BUFFERED)
    val effects: Flow<SetupEffect> = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            selectedId.collect { id -> if (model.value.running != null && id != startedOn) abort("Another vehicle was selected") }
        }
        viewModelScope.launch {
            vehicle.map { it.connected }.distinctUntilChanged().collect { connected -> if (!connected && model.value.running != null) abort("The vehicle's link was lost") }
        }
    }

    // ---- accelerometer and level ----

    fun onAccelStartClicked() = launchCalibration(Calibration.ACCEL, { it.copy(accel = AccelUi(), imuResult = null) }) {
        val r = setup.run(SetupCommand.AccelStart)
        if (r is SetupResult.Failed) return@launchCalibration model.update { it.copy(imuResult = Outcome(false, r.reason)) }
        // The FC now asks for each position (reports → onReport) and ends with AccelDone, which sets imuResult.
        model.first { it.imuResult != null }
    }

    /** The operator says the vehicle is in the position the FC asked for. The FC then samples and asks for the next. */
    fun onAccelPlacedClicked() {
        val position = model.value.accel.waitingFor?.takeIf { model.value.running == Calibration.ACCEL } ?: return
        model.update { it.copy(accel = AccelUi(sampling = position)) }
        viewModelScope.launch {
            val r = setup.run(SetupCommand.AccelPlaced(position))
            if (r is SetupResult.Failed && model.value.running == Calibration.ACCEL) {
                model.update { it.copy(accel = AccelUi(waitingFor = position)) }
                say("Not taken: ${r.reason}")
            }
        }
    }

    fun onLevelClicked() = oneShot(Calibration.LEVEL, SetupCommand.Level, "Level saved: the vehicle's attitude now reads level as it sits.") { m, o -> m.copy(imuResult = o) }

    fun onSimpleAccelClicked() =
        oneShot(Calibration.SIMPLE_ACCEL, SetupCommand.SimpleAccel, "Accelerometer offsets saved from this level position.") { m, o -> m.copy(imuResult = o) }

    // ---- compass from a known heading (Large Vehicle MagCal) ----

    fun onHeadingChanged(text: String) = model.update { it.copy(heading = text) }

    /**
     * Sets every compass's offsets so it reads the typed true heading. Checked here first (0 ≤ heading < 360, what the
     * gateway allows); the FC then needs a position to look up the Earth's field, and says so if it has none.
     */
    fun onFixedYawClicked() {
        val yaw = model.value.heading.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f && it < 360f }
            ?: return say("Type the heading the vehicle points at: 0 to 359.9 degrees from true north")
        oneShot(
            Calibration.FIXED_YAW_COMPASS, SetupCommand.FixedYawCompass(yaw),
            "Compass offsets set for a heading of ${number(yaw.toDouble())}° and saved. Check the heading on the map now matches.",
        ) { m, o -> m.copy(compassResult = o) }
    }

    // ---- barometer ----

    fun onBaroClicked() {
        val plane = vehicle.value.vehicleKind == VehicleKind.PLANE
        oneShot(Calibration.BARO, SetupCommand.Baro, "Ground pressure set: altitude reads 0 here." + if (plane) " The airspeed sensor was zeroed too." else "") { m, o -> m.copy(baroResult = o) }
    }

    // ---- compass ----

    fun onCompassStartClicked() =
        launchCalibration(Calibration.COMPASS, { it.copy(compassAcked = false, compassMask = 0, compassRows = emptyMap(), compassResult = null) }) {
            val r = setup.run(SetupCommand.CompassStart)
            if (r is SetupResult.Failed) return@launchCalibration model.update { it.copy(compassResult = Outcome(false, r.reason)) }
            // Only reports after the ACK count: before it, the FC may still be repeating the result of an earlier run.
            model.update { it.copy(compassAcked = true) }
            val end = model.first { m -> m.compassMask != 0 && bits(m.compassMask).all { m.compassRows[it]?.finished == true } }
            val failed = bits(end.compassMask).mapNotNull { end.compassRows[it] }.filter { !it.ok }
            model.update {
                if (failed.isEmpty()) {
                    it.copy(
                        compassResult = Outcome(true, "Every compass is calibrated and saved on the vehicle. Reboot the flight controller to use the new calibration."),
                        rebootAdvice = "The compass calibration takes effect after a reboot.",
                    )
                } else {
                    it.copy(compassResult = Outcome(false, failed.joinToString("; ") { r -> "Compass ${r.number}: ${r.status}" } +
                        ". Nothing was saved for it. Turn the vehicle slowly through every orientation, away from metal and cars, and try again."))
                }
            }
        }

    // ---- radio ----

    fun onRadioStartClicked() = launchCalibration(Calibration.RADIO, { it.copy(radioStep = RadioStep.IDLE, ranges = emptyList(), review = null, radioResult = null) }) {
        val r = setup.run(SetupCommand.RcCalibrating(true))
        if (r is SetupResult.Failed) return@launchCalibration model.update { it.copy(radioResult = Outcome(false, r.reason)) }
        model.update { it.copy(radioStep = RadioStep.SWEEP) }
        // Runs until the calibration is saved or cancelled, which cancels this coroutine.
        vehicle.collect { v -> model.update { m -> if (m.radioStep == RadioStep.SWEEP) m.copy(ranges = widen(m.ranges, v.rcChannels)) else m } }
    }

    fun onRadioSweepDone() = model.update { if (it.radioStep == RadioStep.SWEEP && it.ranges.isNotEmpty()) it.copy(radioStep = RadioStep.CENTRE) else it }

    /** The sticks are centred and the throttle is down: that reading becomes each channel's trim. */
    fun onRadioCentred() {
        if (model.value.radioStep != RadioStep.CENTRE) return
        val review = rcCalibrationResult(model.value.ranges, vehicle.value.rcChannels, values(), vehicle.value.vehicleKind)
        model.update { it.copy(radioStep = RadioStep.REVIEW, review = review) }
    }

    /** Writes the reviewed values one by one, then clears "RC calibrating". A value that doesn't take is reported. */
    fun onRadioSaveClicked() {
        val review = model.value.review?.takeIf { model.value.radioStep == RadioStep.REVIEW && it.problems.isEmpty() } ?: return
        model.update { it.copy(radioStep = RadioStep.SAVING) }
        val capture = job
        viewModelScope.launch {
            val failed = mutableListOf<String>()
            for ((name, value) in review.writes) {
                // The repository writes to the selected vehicle: never let one drone's calibration land on another.
                if (selectedId.value != startedOn) { failed += "$name (vehicle changed)"; continue }
                val p: Param = params.known.value[name] ?: run { failed += name; null } ?: continue
                if (params.set(p, value) !is ParamSetResult.Applied) failed += name
            }
            stopRadio()
            capture?.cancel()
            val written = review.writes.size - failed.size
            model.update {
                it.copy(
                    radioStep = RadioStep.IDLE,
                    radioResult = if (failed.isEmpty()) Outcome(true, "Radio calibration saved ($written values).")
                    else Outcome(false, "$written of ${review.writes.size} values saved. Not applied: ${failed.joinToString(", ")}. Check them on the Params tab."),
                )
            }
        }
    }

    // ---- cancel, reboot ----

    /** Stops the running calibration (accelerometer, compass or radio) and says what that leaves on the FC. */
    fun onCancelClicked() {
        val running = model.value.running ?: return
        if (model.value.radioStep == RadioStep.SAVING) return
        job?.cancel()
        when (running) {
            Calibration.COMPASS -> viewModelScope.launch { setup.run(SetupCommand.CompassCancel) }
            Calibration.RADIO -> viewModelScope.launch { stopRadio() }
            else -> {}
        }
        model.update { it.afterStop(running, "Cancelled") }
    }

    fun onRebootClicked() = model.update { if (canReboot(it, vehicle.value)) it.copy(confirmReboot = true) else it }

    fun onRebootDismissed() = model.update { it.copy(confirmReboot = false) }

    fun onRebootConfirmed() {
        model.update { it.copy(confirmReboot = false) }
        if (!canReboot(model.value, vehicle.value)) return
        viewModelScope.launch {
            when (val r = setup.run(SetupCommand.Reboot)) {
                SetupResult.Done -> {
                    model.update { it.copy(rebootAdvice = null) }
                    say("Rebooting. The link comes back in a few seconds and the GCS logs in again by itself.")
                }
                is SetupResult.Failed -> say("Reboot: ${r.reason}")
            }
        }
    }

    // ---- internals ----

    /**
     * Starts [block] as [calibration], after [reset] clears the page, with the FC's reports feeding [onReport] for as long
     * as it runs. LAZY start so [job] is set before the block runs; `job === mine` so a stopped job never clears the
     * `running` of one started after it.
     */
    private fun launchCalibration(calibration: Calibration, reset: (Model) -> Model, block: suspend () -> Unit) {
        if (!canStart(model.value, vehicle.value)) return
        startedOn = selectedId.value
        model.update { reset(it).copy(running = calibration, log = emptyList()) }
        lateinit var mine: Job
        mine = viewModelScope.launch(start = CoroutineStart.LAZY) {
            // UNDISPATCHED: listening before the first command goes out, so no early report is missed.
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { setup.reports.collect(::onReport) }
            try {
                block()
            } finally {
                listener.cancel()
                if (job === mine) model.update { it.copy(running = null) }
            }
        }
        job = mine
        mine.start()
    }

    private fun oneShot(calibration: Calibration, command: SetupCommand, done: String, put: (Model, Outcome?) -> Model) =
        launchCalibration(calibration, { put(it, null) }) {
            val outcome = when (val r = setup.run(command)) {
                SetupResult.Done -> Outcome(true, done)
                is SetupResult.Failed -> Outcome(false, r.reason)
            }
            model.update { put(it, outcome) }
        }

    private fun onReport(report: SetupReport) = model.update { m ->
        when (report) {
            is SetupReport.Text -> m.copy(log = (m.log + report.message).takeLast(MAX_LOG))
            // A late repeat of the position just answered is ignored: the FC asks every second until it starts sampling.
            is SetupReport.AccelRequest ->
                if (m.running != Calibration.ACCEL || report.position == m.accel.sampling) m else m.copy(accel = AccelUi(waitingFor = report.position))
            is SetupReport.AccelDone -> if (m.running != Calibration.ACCEL) m else m.copy(
                accel = AccelUi(),
                imuResult = if (report.success) Outcome(true, "Accelerometer calibration successful and saved.")
                else Outcome(false, "The accelerometer calibration failed. Hold the vehicle still in each position, and turn it a full 90° each time."),
            )
            is SetupReport.CompassProgress -> if (!m.compassAcked) m else m.copy(
                compassMask = report.mask,
                compassRows = m.compassRows + (report.compass to CompassRow(report.compass + 1, report.percent, report.status.label, null, finished = false, ok = false)),
            )
            is SetupReport.CompassResult -> if (!m.compassAcked) m else {
                val ok = report.status == CompassCalStatus.SUCCESS
                m.copy(compassRows = m.compassRows + (report.compass to CompassRow(report.compass + 1, if (ok) 100 else 0, report.status.label, "${number(report.fitness.toDouble())} mGauss fit", finished = true, ok = ok)))
            }
        }
    }

    /** The calibration was stopped here by [why]: what that leaves on the flight controller, per calibration. */
    private fun Model.afterStop(running: Calibration, why: String): Model = when (running) {
        Calibration.ACCEL -> copy(
            accel = AccelUi(),
            imuResult = Outcome(false, "$why. ArduPilot has no command to stop an accelerometer calibration: it keeps waiting for the next position until the flight controller reboots."),
            rebootAdvice = "An accelerometer calibration may still be waiting on the flight controller.",
        )
        Calibration.COMPASS -> copy(compassResult = Outcome(false, "$why. Nothing was saved."))
        Calibration.FIXED_YAW_COMPASS -> copy(compassResult = Outcome(false, "$why before the vehicle answered. It may still have saved the offsets: check its messages."))
        Calibration.RADIO -> copy(radioStep = RadioStep.IDLE, review = null, radioResult = Outcome(false, "$why. Nothing was written."))
        Calibration.LEVEL, Calibration.SIMPLE_ACCEL -> copy(imuResult = Outcome(false, "$why before the vehicle answered. It may still have finished: check its messages."))
        Calibration.BARO -> copy(baroResult = Outcome(false, "$why before the vehicle answered."))
    }

    /** Selection changed or link lost: nothing can be sent to clean up, so the result says what may be left running. */
    private fun abort(why: String) {
        val running = model.value.running ?: return
        job?.cancel()
        val left = when (running) {
            Calibration.COMPASS -> " The compass calibration may still be running on that vehicle; it saves nothing until it finishes."
            Calibration.RADIO -> " That vehicle still reports \"RC calibrating\" and won't arm until it is rebooted or a radio calibration is finished."
            else -> ""
        }
        model.update { it.afterStop(running, why) }
        say("$why: ${running.title} stopped.$left")
    }

    private suspend fun stopRadio() = setup.run(SetupCommand.RcCalibrating(false))

    private fun values() = params.known.value.mapValues { it.value.value }

    private fun ready(v: VehicleState) = v.connected && v.login?.allowsTraffic != false && !v.armed

    private fun canStart(m: Model, v: VehicleState) = ready(v) && m.running == null

    private fun canReboot(m: Model, v: VehicleState) = ready(v) && m.running == null

    private fun say(text: String) {
        _effects.trySend(SetupEffect.ShowMessage(text))
    }

    private fun build(m: Model, v: VehicleState, known: Map<String, Param>): CalibrationUiState {
        val values = known.mapValues { it.value.value }
        val roles = rcRoles(values)
        val count = maxOf(v.rcChannels.size, m.ranges.size)
        return CalibrationUiState(
            running = m.running,
            canStart = canStart(m, v),
            hint = when {
                !v.connected -> "Connect a vehicle to calibrate it."
                v.login?.allowsTraffic == false -> "${v.login?.label}. Calibrations wait for the login."
                v.armed -> "Disarm first: calibrations only run on the ground."
                m.running != null -> "${m.running.title} is running."
                else -> null
            },
            vehicleKind = v.vehicleKind,
            accel = m.accel,
            imuResult = m.imuResult,
            compass = CompassUi(
                m.compassRows.values.sortedBy { it.number }, m.compassResult, m.heading,
                v.headingDeg?.let { "${number(kotlin.math.round(it * 10) / 10)}°" },
            ),
            baroResult = m.baroResult,
            radio = RadioUi(
                step = m.radioStep,
                channels = (0 until count).map { i ->
                    val r = m.ranges.getOrNull(i)
                    RadioChannel(i + 1, roles[i + 1], v.rcChannels.getOrNull(i), r?.min, r?.max)
                },
                review = m.review,
                result = m.radioResult,
            ),
            log = m.log,
            canReboot = canReboot(m, v),
            confirmReboot = m.confirmReboot,
            rebootAdvice = m.rebootAdvice,
        )
    }

    private companion object {
        const val MAX_LOG = 8
    }
}

/** The bit numbers set in [mask], lowest first. */
private fun bits(mask: Int) = (0 until 8).filter { (mask shr it) and 1 == 1 }
