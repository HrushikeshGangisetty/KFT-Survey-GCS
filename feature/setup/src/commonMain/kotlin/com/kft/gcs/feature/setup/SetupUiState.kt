package com.kft.gcs.feature.setup

import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.vehicle.AccelPosition
import com.kft.gcs.core.vehicle.StatusMessage

/** The Setup tab's pages, in list order, grouped under [group]. */
enum class SetupPage(val title: String, val group: String) {
    ACCEL("Accelerometer", "Calibration"),
    COMPASS("Compass", "Calibration"),
    BARO("Barometer", "Calibration"),
    RADIO("Radio", "Calibration"),
    FLIGHT_MODES("Flight modes", "Configuration"),
    FAILSAFES("Failsafes", "Configuration"),
    PREARM("Pre-arm checks", "Information"),
    HARDWARE("Hardware ID", "Information"),
}

/**
 * Everything the Setup tab draws apart from the calibrations (CLAUDE.md §3); those are [CalibrationUiState].
 *
 * @property vehicle one line about the selected vehicle ("Copter · 4.6.3 · disarmed"), or why there's none.
 * @property notice why changes are off right now (not connected, logging in, armed), or null.
 * @property params "1 320 parameters read" or "Parameters not read yet", for the header.
 * @property sections the parameter page on screen (flight modes or failsafes), empty on the other pages.
 * @property problems for that page: combinations the FC would refuse to arm with ([armingProblems]).
 * @property activeSlot the mode-switch position (0..5) the RC selects right now, for the flight-mode page.
 */
data class SetupUiState(
    val page: SetupPage = SetupPage.ACCEL,
    val vehicle: String = "No vehicle connected",
    val notice: String? = "Connect a vehicle to set it up.",
    val params: String = "Parameters not read yet",
    val canDownload: Boolean = false,
    val canWrite: Boolean = false,
    val busy: Boolean = false,
    val sections: List<FieldSection> = emptyList(),
    val problems: List<String> = emptyList(),
    val modeSwitch: String? = null,
    val activeSlot: Int? = null,
    val edit: FieldEdit? = null,
    val board: List<Pair<String, String>> = emptyList(),
    val sensors: List<SensorRow> = emptyList(),
)

data class FieldSection(val title: String, val note: String?, val rows: List<FieldRow>)

/** One field: [value] as read ("RTL", "10.5 V"), or "—" when the vehicle doesn't have this parameter (or none is read). */
data class FieldRow(val param: String, val label: String, val value: String, val help: String?, val available: Boolean)

/**
 * The edit dialog for one field. [text] is the value being chosen (a code for a choice, the number for bits). While
 * [confirm] is null it asks for the value; then [confirm] holds the question and nothing is sent until it's answered.
 */
data class FieldEdit(
    val param: String,
    val label: String,
    val help: String?,
    val current: String,
    val kind: FieldKind,
    val text: String,
    val error: String? = null,
    val confirm: String? = null,
)

/** Which calibration is running. One at a time: they share the FC's attention and the operator's. */
enum class Calibration(val title: String) {
    ACCEL("Accelerometer calibration"), LEVEL("Level"), SIMPLE_ACCEL("Simple accelerometer calibration"),
    COMPASS("Compass calibration"), BARO("Barometer calibration"), RADIO("Radio calibration"),
}

/** How something ended, in the operator's words. */
data class Outcome(val ok: Boolean, val text: String)

/**
 * Everything the calibration pages draw.
 * @property canStart a vehicle is connected, logged in, disarmed, and nothing is running.
 * @property hint why [canStart] is false, or null.
 * @property log the FC's messages since the running (or last) calibration started, oldest first.
 * @property canReboot connected, logged in, disarmed and nothing running.
 * @property rebootAdvice why a reboot is due ("the compass calibration is saved…"), or null.
 */
data class CalibrationUiState(
    val running: Calibration? = null,
    val canStart: Boolean = false,
    val hint: String? = "Connect a vehicle to calibrate it.",
    val vehicleKind: VehicleKind? = null,
    val accel: AccelUi = AccelUi(),
    val imuResult: Outcome? = null,
    val compass: CompassUi = CompassUi(),
    val baroResult: Outcome? = null,
    val radio: RadioUi = RadioUi(),
    val log: List<StatusMessage> = emptyList(),
    val canReboot: Boolean = false,
    val confirmReboot: Boolean = false,
    val rebootAdvice: String? = null,
)

/**
 * The 6-position accelerometer calibration. [waitingFor] is the position the FC asked for (null before the first
 * request); [sampling] the one it is measuring now, after the operator said the vehicle is in place.
 */
data class AccelUi(val waitingFor: AccelPosition? = null, val sampling: AccelPosition? = null)

/** One row per compass the FC is calibrating, numbered from 1 as the operator counts them. */
data class CompassUi(val rows: List<CompassRow> = emptyList(), val result: Outcome? = null)

data class CompassRow(val number: Int, val percent: Int, val status: String, val fitness: String?, val finished: Boolean, val ok: Boolean)

enum class RadioStep { IDLE, SWEEP, CENTRE, REVIEW, SAVING }

/**
 * The radio page: the live channels, and the calibration's step. [channels] always shows what the receiver sends;
 * during and after the sweep it also has each channel's range. [review] is the result waiting to be saved.
 */
data class RadioUi(
    val step: RadioStep = RadioStep.IDLE,
    val channels: List<RadioChannel> = emptyList(),
    val review: RcCalibrationResult? = null,
    val result: Outcome? = null,
)

data class RadioChannel(val number: Int, val role: String?, val pwm: Int?, val min: Int?, val max: Int?)
