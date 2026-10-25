package com.kft.gcs.core.vehicle

import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.ardupilotmega.MagCalProgress
import com.divpundir.mavlink.definitions.ardupilotmega.MavCmd as ArduMavCmd
import com.divpundir.mavlink.definitions.common.CommandLong
import com.divpundir.mavlink.definitions.common.MagCalReport
import com.divpundir.mavlink.definitions.common.MagCalStatus
import com.divpundir.mavlink.definitions.common.MavCmd
import com.divpundir.mavlink.definitions.common.MavResult
import com.divpundir.mavlink.definitions.common.Statustext
import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.MavSender
import com.kft.gcs.core.mavlink.TxResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull

/**
 * The six positions of ArduPilot's accelerometer calibration, with the number the FC uses for each
 * (ardupilotmega.xml ACCELCAL_VEHICLE_POS) and how the FC words them ("Place vehicle on its LEFT side").
 */
enum class AccelPosition(val wire: Int, val label: String) {
    LEVEL(1, "level"), LEFT(2, "on its left side"), RIGHT(3, "on its right side"),
    NOSE_DOWN(4, "nose down"), NOSE_UP(5, "nose up"), BACK(6, "on its back"),
}

/** Something the Setup tab asks the vehicle to do. Each is one COMMAND_LONG; [toCommand] has the numbers. */
sealed interface SetupCommand {
    /** Accelerometer, 6 positions. The FC calibrates the gyros first, then asks for each position in turn. */
    data object AccelStart : SetupCommand

    /** "The vehicle is now [position]": the answer to the FC's request for that position. */
    data class AccelPlaced(val position: AccelPosition) : SetupCommand

    /** Level: the FC's attitude with the vehicle sitting level becomes its zero (AHRS trim). */
    data object Level : SetupCommand

    /** Accelerometer offsets from one level position, for a vehicle too large to turn over. */
    data object SimpleAccel : SetupCommand

    /** Barometer: the current pressure becomes ground level. On a Plane it also zeroes the airspeed sensor. */
    data object Baro : SetupCommand

    /** Every compass at once, saved by the FC when it succeeds. */
    data object CompassStart : SetupCommand
    data object CompassCancel : SetupCommand

    /** Tells the FC an RC calibration is running (on) or over (off). While on, it refuses to arm ("RC calibrating"). */
    data class RcCalibrating(val on: Boolean) : SetupCommand

    /** Restarts the autopilot. The link drops for a few seconds and the GCS logs in again by itself. */
    data object Reboot : SetupCommand

    /**
     * Runs the pre-arm checks now, so the report ([PreArmReport]) is fresh. Changes nothing on the vehicle: the FC only
     * sends a "PreArm:" text per failure, as it does by itself every 30 s.
     */
    data object RunPreArmChecks : SetupCommand
}

/** How a [SetupCommand] ended. [Failed.reason] is written for the operator. */
sealed interface SetupResult {
    data object Done : SetupResult
    data class Failed(val reason: String) : SetupResult
}

/** Where one compass's calibration is (common.xml MAG_CAL_STATUS). */
enum class CompassCalStatus(val label: String) {
    NOT_STARTED("Not started"), WAITING("Waiting to start"), RUNNING_1("Collecting, step 1"), RUNNING_2("Collecting, step 2"),
    SUCCESS("Done"), FAILED("Failed"), BAD_ORIENTATION("Failed: compass orientation is wrong"), BAD_RADIUS("Failed: field strength is wrong"),
}

/** What the vehicle reports during a calibration. Only the autopilot's own frames, never a gimbal's or companion's. */
sealed interface SetupReport {
    /** The FC wants the vehicle in [position] (ACCELCAL_VEHICLE_POS 1..6). It repeats this every second until it gets it. */
    data class AccelRequest(val position: AccelPosition) : SetupReport

    /** The accelerometer calibration ended (ACCELCAL_VEHICLE_POS SUCCESS / FAILED). Repeated every second, like the above. */
    data class AccelDone(val success: Boolean) : SetupReport

    /**
     * MAG_CAL_PROGRESS for [compass] (0-based, as the FC numbers them). [mask] has a bit set for every compass being
     * calibrated (`_get_cal_mask`), so the screen knows how many results to wait for.
     */
    data class CompassProgress(val compass: Int, val percent: Int, val status: CompassCalStatus, val attempt: Int, val mask: Int) : SetupReport

    /** MAG_CAL_REPORT: [compass]'s result. [fitness] is the RMS fit error in mGauss (smaller is better). */
    data class CompassResult(val compass: Int, val status: CompassCalStatus, val fitness: Float, val saved: Boolean) : SetupReport

    /** A STATUSTEXT, e.g. "Place vehicle nose DOWN and press any key." or "Calibration successful". */
    data class Text(val message: StatusMessage) : SetupReport
}

/**
 * Calibrations and the reboot, for the Setup tab. An interface so ViewModel tests can use a fake.
 *
 * Calibrations are a conversation: the GCS starts one with [run], the FC answers through [reports] (positions it wants,
 * progress, results), and for the accelerometer the GCS answers each position with another [run]. [reports] is cold:
 * collect it while a calibration is on screen.
 */
interface VehicleSetupRepository {
    suspend fun run(command: SetupCommand): SetupResult
    val reports: Flow<SetupReport>
}

/**
 * [VehicleSetupRepository] over COMMAND_LONG and the calibration messages, for one vehicle.
 *
 * Every command waits for the KFT login (like parameters), and the TX gateway allows them only while disarmed
 * (TxPolicy SETUP_PATTERNS, which allows exactly the numbers [toCommand] builds). The pre-arm run is the exception: it
 * changes nothing, so the gateway always allows it, and the FC itself refuses it while armed.
 */
class DefaultVehicleSetupRepository(
    private val frames: Flow<MavFrame<out MavMessage<*>>>,
    private val link: StateFlow<LinkState>,
    private val vehicle: StateFlow<VehicleState>,
    sender: MavSender,
) : VehicleSetupRepository {
    private val commands = CommandProtocol(frames, sender)

    override suspend fun run(command: SetupCommand): SetupResult {
        val target = loggedInTarget(link.value, vehicle.value)
            ?: return SetupResult.Failed(notReadyReason(link.value, vehicle.value, if (command == SetupCommand.RunPreArmChecks) "Pre-arm checks" else "Calibrations"))
        val (message, attempts, timeout) = command.toCommand(target)
        val result = commands.send(message, attempts, timeout)
        return result.toSetupResult(command)
    }

    override val reports: Flow<SetupReport> = frames
        .filter { frame -> (link.value as? LinkState.Connected)?.vehicle?.componentId == frame.componentId }
        .mapNotNull { setupReport(it.message) }
}

/** One command on the wire: the message, how many times to send it, and how long to wait for its ACK. */
internal data class WireCommand(val message: CommandLong, val attempts: Int, val ackTimeout: Duration)

/**
 * The numbers for each [SetupCommand], checked against the KFT fork (d91a40ef) and kept identical to TxPolicy's
 * SETUP_PATTERNS (the gateway refuses anything else).
 *
 * The calibrations run inside the FC's main loop and only ACK when done: ArduPilot allows itself 30 s for them
 * (`EXPECT_DELAY_MS(30000)` in `_handle_command_preflight_calibration`). So they are sent **once** with a 30 s wait:
 * a resend after a slow ACK would start the calibration a second time. ACCELCAL_VEHICLE_POS is sent once too: a resend
 * after a lost ACK would be refused (the FC has moved on to sampling), and the FC's next position request is what
 * tells the screen it arrived. The reboot is sent once so it can't reboot twice.
 */
internal fun SetupCommand.toCommand(target: MissionProtocol.Target): WireCommand {
    fun long(cmd: UInt, vararg p: Float, attempts: Int = 1, timeout: Duration = CALIBRATION_TIMEOUT) = WireCommand(
        CommandLong(
            targetSystem = target.system, targetComponent = target.component, command = MavEnumValue.fromValue(cmd),
            param1 = p.getOrElse(0) { 0f }, param2 = p.getOrElse(1) { 0f }, param3 = p.getOrElse(2) { 0f },
            param4 = p.getOrElse(3) { 0f }, param5 = p.getOrElse(4) { 0f }, param6 = p.getOrElse(5) { 0f }, param7 = p.getOrElse(6) { 0f },
        ),
        attempts, timeout,
    )
    val calibration = MavCmd.PREFLIGHT_CALIBRATION.value
    return when (this) {
        SetupCommand.AccelStart -> long(calibration, 0f, 0f, 0f, 0f, 1f)
        is SetupCommand.AccelPlaced -> long(ArduMavCmd.ACCELCAL_VEHICLE_POS.value, position.wire.toFloat(), timeout = CommandProtocol.ACK_TIMEOUT * 2)
        SetupCommand.Level -> long(calibration, 0f, 0f, 0f, 0f, 2f)
        SetupCommand.SimpleAccel -> long(calibration, 0f, 0f, 0f, 0f, 4f)
        SetupCommand.Baro -> long(calibration, 0f, 0f, 1f)
        // Mask 0 = every compass, retry 0, autosave 1, delay 0, autoreboot 0 (handle_mag_cal_command). Starting twice
        // only restarts it, so the usual resends are fine.
        SetupCommand.CompassStart -> long(ArduMavCmd.DO_START_MAG_CAL.value, 0f, 0f, 1f, attempts = CommandProtocol.ATTEMPTS, timeout = CommandProtocol.ACK_TIMEOUT)
        SetupCommand.CompassCancel -> long(ArduMavCmd.DO_CANCEL_MAG_CAL.value, attempts = CommandProtocol.ATTEMPTS, timeout = CommandProtocol.ACK_TIMEOUT)
        is SetupCommand.RcCalibrating -> long(calibration, 0f, 0f, 0f, if (on) 1f else 0f, attempts = CommandProtocol.ATTEMPTS, timeout = CommandProtocol.ACK_TIMEOUT)
        SetupCommand.Reboot -> long(MavCmd.PREFLIGHT_REBOOT_SHUTDOWN.value, 1f, timeout = CommandProtocol.ACK_TIMEOUT * 2)
        // MAV_CMD_RUN_PREARM_CHECKS (common.xml #401, no parameters). ArduPilot runs the checks in the same pass and ACKs
        // at once (GCS_Common.cpp handle_command_run_prearm_checks), so the usual resends are fine: a second run only
        // reports again.
        SetupCommand.RunPreArmChecks -> long(MavCmd.RUN_PREARM_CHECKS.value, attempts = CommandProtocol.ATTEMPTS, timeout = CommandProtocol.ACK_TIMEOUT)
    }
}

/** 30 s, ArduPilot's own allowance for a calibration (see [toCommand]). */
internal val CALIBRATION_TIMEOUT = 30.seconds

/**
 * The command protocol's result in the operator's words.
 *
 * The RC calibration flag is special: `_handle_command_preflight_calibration` sets it (`rc().calibrating(...)`) and then
 * falls through to `return ret`, still MAV_RESULT_UNSUPPORTED, because no other calibration was asked for. So
 * UNSUPPORTED is how a working ArduPilot answers it, and counts as done here.
 */
internal fun CommandResult.toSetupResult(command: SetupCommand): SetupResult = when (this) {
    CommandResult.Accepted -> SetupResult.Done
    is CommandResult.Refused ->
        if (command is SetupCommand.RcCalibrating && result == MavResult.UNSUPPORTED) SetupResult.Done
        else SetupResult.Failed(
            when (result) {
                MavResult.DENIED, MavResult.FAILED -> "The vehicle refused it. Check its messages below and that it is disarmed and still."
                // handle_command_run_prearm_checks answers TEMPORARILY_REJECTED while armed, and only then.
                MavResult.TEMPORARILY_REJECTED ->
                    if (command == SetupCommand.RunPreArmChecks) "The vehicle is armed. It only runs its pre-arm checks while disarmed."
                    else "The vehicle is busy. Try again in a moment."
                MavResult.UNSUPPORTED -> "This firmware doesn't support it."
                else -> "The vehicle answered ${result.name}."
            },
        )
    CommandResult.NoAck -> SetupResult.Failed("No answer from the vehicle.")
    is CommandResult.NotSent -> SetupResult.Failed(
        when (val why = tx) {
            is TxResult.Rejected -> "Not sent: ${why.reason}."
            TxResult.NotConnected -> "Not sent: no link."
            is TxResult.Failed -> "Not sent: link error (${why.cause.message})."
            TxResult.Sent -> "Not sent."
        },
    )
}

/**
 * The calibration messages, as [SetupReport]s. Null for everything else.
 * - COMMAND_LONG ACCELCAL_VEHICLE_POS, **from** the FC (GCS_Common.cpp `send_accelcal_vehicle_position`, target 0/0):
 *   param1 is the position, or 16777215 (SUCCESS) / 16777216 (FAILED), both exact in a float.
 * - MAG_CAL_PROGRESS (ardupilotmega.xml #191) and MAG_CAL_REPORT (common.xml #192): EXTRA3 stream group, so they come
 *   at the rate the connect-time stream request set (AP_Compass_Calibration.cpp `send_mag_cal_progress` / `_report`).
 *   A report repeats for as long as the FC holds that result.
 * - STATUSTEXT: the FC's instructions and verdicts ("Calibration FAILED"). KFT login texts are protocol, not shown.
 */
internal fun setupReport(m: MavMessage<*>): SetupReport? = when (m) {
    is CommandLong -> if (m.command.value != ArduMavCmd.ACCELCAL_VEHICLE_POS.value) null else when (m.param1) {
        16777215f -> SetupReport.AccelDone(true)
        16777216f -> SetupReport.AccelDone(false)
        else -> AccelPosition.entries.find { it.wire.toFloat() == m.param1 }?.let(SetupReport::AccelRequest)
    }
    is MagCalProgress -> SetupReport.CompassProgress(m.compassId.toInt(), m.completionPct.toInt(), compassStatus(m.calStatus), m.attempt.toInt(), m.calMask.toInt())
    is MagCalReport -> SetupReport.CompassResult(m.compassId.toInt(), compassStatus(m.calStatus), m.fitness, m.autosaved.toInt() != 0)
    is Statustext -> m.text.trimEnd('\u0000').takeUnless { it.startsWith("KFTCH") }
        ?.let { SetupReport.Text(StatusMessage(it, severityOf(m.severity.value))) }
    else -> null
}

private fun compassStatus(v: MavEnumValue<MagCalStatus>) = when (v.entry) {
    MagCalStatus.MAG_CAL_NOT_STARTED, null -> CompassCalStatus.NOT_STARTED
    MagCalStatus.MAG_CAL_WAITING_TO_START -> CompassCalStatus.WAITING
    MagCalStatus.MAG_CAL_RUNNING_STEP_ONE -> CompassCalStatus.RUNNING_1
    MagCalStatus.MAG_CAL_RUNNING_STEP_TWO -> CompassCalStatus.RUNNING_2
    MagCalStatus.MAG_CAL_SUCCESS -> CompassCalStatus.SUCCESS
    MagCalStatus.MAG_CAL_FAILED -> CompassCalStatus.FAILED
    MagCalStatus.MAG_CAL_BAD_ORIENTATION -> CompassCalStatus.BAD_ORIENTATION
    MagCalStatus.MAG_CAL_BAD_RADIUS -> CompassCalStatus.BAD_RADIUS
}
