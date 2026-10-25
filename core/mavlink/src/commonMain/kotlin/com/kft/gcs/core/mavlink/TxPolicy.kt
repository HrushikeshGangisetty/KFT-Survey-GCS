package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.ardupilotmega.MavCmd as ArduMavCmd
import com.divpundir.mavlink.definitions.common.CommandInt
import com.divpundir.mavlink.definitions.common.CommandLong
import com.divpundir.mavlink.definitions.common.LogRequestData
import com.divpundir.mavlink.definitions.common.LogRequestEnd
import com.divpundir.mavlink.definitions.common.LogRequestList
import com.divpundir.mavlink.definitions.common.ManualControl
import com.divpundir.mavlink.definitions.common.MavCmd
import com.divpundir.mavlink.definitions.common.MissionAck
import com.divpundir.mavlink.definitions.common.MissionClearAll
import com.divpundir.mavlink.definitions.common.MissionCount
import com.divpundir.mavlink.definitions.common.MissionItemInt
import com.divpundir.mavlink.definitions.common.MissionRequestInt
import com.divpundir.mavlink.definitions.common.MissionRequestList
import com.divpundir.mavlink.definitions.common.MissionSetCurrent
import com.divpundir.mavlink.definitions.common.MissionWritePartialList
import com.divpundir.mavlink.definitions.common.ParamRequestList
import com.divpundir.mavlink.definitions.common.ParamRequestRead
import com.divpundir.mavlink.definitions.common.ParamSet
import com.divpundir.mavlink.definitions.common.RcChannelsOverride
import com.divpundir.mavlink.definitions.common.RequestDataStream
import com.divpundir.mavlink.definitions.common.SetAttitudeTarget
import com.divpundir.mavlink.definitions.common.SetMode
import com.divpundir.mavlink.definitions.common.SetPositionTargetGlobalInt
import com.divpundir.mavlink.definitions.common.SetPositionTargetLocalNed
import com.divpundir.mavlink.definitions.common.SetupSigning
import com.divpundir.mavlink.definitions.minimal.Heartbeat

/** Which ArduPilot firmware family the vehicle runs. Mode numbers differ between them (GUIDED is 4 on Copter, 15 on Plane). */
enum class VehicleKind { COPTER, PLANE, UNKNOWN }

/** What the pod reports about its lock state. [NONE] means no pod is connected, which is the only state in P0. */
enum class PodLockState { NONE, IDLE, LOCKED, TERMINAL, ENGAGE }

/**
 * The pod facts the TX gateway needs (docs/spec/02_pod_interface_contract.md §3). Kept separate from the
 * pod link itself so the gateway can be tested with any combination, long before a pod exists.
 */
data class PodStatus(val lock: PodLockState = PodLockState.NONE, val aiEnableHigh: Boolean = false) {
    companion object {
        val NoPod = PodStatus()
    }
}

/**
 * The rows of the TX rules table in the pod contract §3, as tightened by spec S9, plus [UNLISTED] for everything
 * the table doesn't name.
 *
 * [PILOT_ONLY] replaces the contract's "operator commands", "safe-direction modes" and "enter GUIDED" rows for
 * anything that makes the aircraft move or change mode. Under S9 the GCS sends none of them, in any pod state, so
 * the GUIDED and safe-direction rules are covered by a stricter one (see open item GS-8 in the spec).
 */
internal enum class TxCategory { ALWAYS, PARAM_CHANGE_DISARMED, SETUP_DISARMED, LOG_READ_DISARMED, MISSION_CHANGE, MISSION_CHANGE_DISARMED, PILOT_ONLY, NEVER, UNLISTED }

/** A message's category plus a human-readable name for logs and rejection reasons. */
internal data class TxClassification(val category: TxCategory, val label: String)

/**
 * The allowlist, as pure functions: [classify] puts a message into a row of the contract table, [check] applies
 * that row's rule to the current pod state. Pure on purpose: every row is unit-tested without a transport.
 *
 * Anything not explicitly listed is [TxCategory.UNLISTED] and rejected. Default-deny means a new message type
 * can't slip through just because nobody thought about it; adding it here (with a test) is a deliberate act.
 *
 * Where each allowed message is defined (spec S10), checked against mavlink-kotlin's generated packages and
 * ArduPilot master `libraries/GCS_MAVLink/GCS_Common.cpp` (2026-09):
 * - HEARTBEAT: `minimal.xml`.
 * - PARAM_*, MISSION_*, COMMAND_LONG/COMMAND_INT, SET_MODE, REQUEST_DATA_STREAM: `common.xml`.
 *   REQUEST_DATA_STREAM is deprecated there, but ArduPilot still maps it onto its SRx stream groups.
 * - ArduPilot converts every COMMAND_LONG to COMMAND_INT before handling it, and builds without COMMAND_LONG
 *   answer `MAV_RESULT_COMMAND_INT_ONLY`, so both forms are classified by the same command table.
 */
internal object TxPolicy {

    fun classify(message: MavMessage<*>): TxClassification = when (message) {
        // Always allowed: link keep-alive and read-only requests.
        is Heartbeat -> always("HEARTBEAT")
        is ParamRequestRead -> always("PARAM_REQUEST_READ")
        is ParamRequestList -> always("PARAM_REQUEST_LIST")
        is RequestDataStream -> always("REQUEST_DATA_STREAM")
        is MissionRequestList -> always("MISSION_REQUEST_LIST")   // mission download
        is MissionRequestInt -> always("MISSION_REQUEST_INT")     // mission download
        is MissionAck -> always("MISSION_ACK")                     // ends a mission download

        // PARAM_SET (common.xml #23) changes how the vehicle flies: gains, failsafes, the camera. The pod contract lists
        // it under "operator commands"; this GCS is stricter and only sends it on the ground, because a wrong value
        // in the air can't be taken back before it matters. ArduPilot applies it at once and saves it to storage
        // (GCS_Param.cpp handle_param_set). Reading (PARAM_REQUEST_*) stays ALWAYS: it changes nothing.
        is ParamSet -> TxClassification(TxCategory.PARAM_CHANGE_DISARMED, "PARAM_SET")

        // Flight-log download (common.xml #117, #119, #122). Read-only: they copy a log off the SD card and change nothing.
        // ArduPilot ignores all three while armed ("Disarm for log download", AP_Logger_MAVLinkLogTransfer.cpp
        // handle_log_message, KFT fork d91a40ef); refusing them here too says so at once and keeps a download from
        // ever competing with telemetry in the air. LOG_ERASE (#121) wipes every log and stays unlisted on purpose.
        is LogRequestList -> TxClassification(TxCategory.LOG_READ_DISARMED, "LOG_REQUEST_LIST")
        is LogRequestData -> TxClassification(TxCategory.LOG_READ_DISARMED, "LOG_REQUEST_DATA")
        is LogRequestEnd -> TxClassification(TxCategory.LOG_READ_DISARMED, "LOG_REQUEST_END")

        // Anything that changes the mission on the vehicle.
        is MissionCount -> missionChange("MISSION_COUNT")
        is MissionItemInt -> missionChange("MISSION_ITEM_INT")
        is MissionClearAll -> missionChange("MISSION_CLEAR_ALL")
        is MissionWritePartialList -> missionChange("MISSION_WRITE_PARTIAL_LIST")
        // MISSION_SET_CURRENT (common.xml #41, deprecated there for MAV_CMD_DO_SET_MISSION_CURRENT, which stays
        // unlisted) is still handled by ArduPilot (GCS_Common.cpp handle_mission_set_current -> AP_Mission::
        // set_current_cmd). In AUTO that makes a flying aircraft jump to another item at once, which is a flight
        // action (S9). On the ground it only picks where AUTO will start, which resume-from-point needs.
        is MissionSetCurrent -> TxClassification(TxCategory.MISSION_CHANGE_DISARMED, "MISSION_SET_CURRENT")

        // Mission items can hold TAKEOFF, LAND or RTL: those are a plan the pilot starts by switching to AUTO on
        // the RC, not an immediate action, so they are MISSION_CHANGE above whatever command they carry (S9).
        is SetMode -> pilotOnly("SET_MODE")
        is CommandLong -> with(message) { classifyCommand(command.value, listOf(param1, param2, param3, param4, param5, param6, param7)) }
        // ArduPilot reads COMMAND_INT's x, y, z where COMMAND_LONG has param5..7 (GCS_Common.cpp
        // try_command_long_as_command_int), so the setup patterns below are checked on the same seven numbers.
        is CommandInt -> with(message) { classifyCommand(command.value, listOf(param1, param2, param3, param4, x.toFloat(), y.toFloat(), z)) }

        // Never, whatever the state. Guidance belongs to the pod's pod_mavlink module alone (pod invariant 7).
        is SetPositionTargetLocalNed -> never("SET_POSITION_TARGET_LOCAL_NED")
        is SetPositionTargetGlobalInt -> never("SET_POSITION_TARGET_GLOBAL_INT")
        is SetAttitudeTarget -> never("SET_ATTITUDE_TARGET")
        // RC is the pilot's. AI-enable and target-lock are RC channels, so an RC override from the GCS could
        // raise them. That would be engagement over the GCS, which pod invariant 4 forbids.
        is RcChannelsOverride -> never("RC_CHANNELS_OVERRIDE")
        is ManualControl -> never("MANUAL_CONTROL")
        // SETUP_SIGNING (common.xml #256) carries a signing key in clear: anyone listening learns it. ArduPilot stores
        // the key it carries (GCS_Signing.cpp handle_setup_signing); the PRD makes the flight controller take it over
        // USB only (FR-3, firmware). Keys are written by the provisioning tool over USB, never by this GCS over a link
        // (PRD "Key management", FR-14), so no key ever leaves this device.
        is SetupSigning -> never("SETUP_SIGNING")

        else -> TxClassification(TxCategory.UNLISTED, message.instanceCompanion.id.let { "message id $it" })
    }

    /**
     * Null means allowed. Otherwise the reason the message is refused.
     *
     * @param armed the vehicle's armed flag from its latest heartbeat, or null when no vehicle is heard. Unknown
     *   counts as armed for [TxCategory.MISSION_CHANGE_DISARMED]: fail closed.
     */
    fun check(category: TxCategory, pod: PodStatus, armed: Boolean?): String? = when (category) {
        TxCategory.ALWAYS -> null
        TxCategory.PARAM_CHANGE_DISARMED -> if (armed != false) "parameters can only be changed while the vehicle is disarmed" else null
        TxCategory.SETUP_DISARMED -> if (armed != false) "calibrations and reboots only while the vehicle is disarmed" else null
        TxCategory.LOG_READ_DISARMED -> if (armed != false) "logs can only be downloaded while the vehicle is disarmed" else null
        TxCategory.MISSION_CHANGE -> missionLockout(pod)
        TxCategory.MISSION_CHANGE_DISARMED -> missionLockout(pod)
            ?: if (armed != false) "only allowed while the vehicle is disarmed (spec S9)" else null
        TxCategory.PILOT_ONLY -> "flight actions belong to the pilot on the RC, never the GCS (spec S9)"
        TxCategory.NEVER -> "never sent from the GCS (pod contract §3)"
        TxCategory.UNLISTED -> "not on the TX allowlist"
    }

    /**
     * The `target_system` field of [message], or null for a message that has none. A vehicle's sender refuses anything
     * whose target isn't its own vehicle (ADR-005 §3), so an allowlisted message missing here can't be sent at all:
     * default-deny, like [classify]. Every one of these has `target_system` in `common.xml`.
     */
    fun targetSystem(message: MavMessage<*>): UByte? = when (message) {
        is ParamRequestRead -> message.targetSystem
        is ParamRequestList -> message.targetSystem
        is ParamSet -> message.targetSystem
        is RequestDataStream -> message.targetSystem
        is MissionRequestList -> message.targetSystem
        is MissionRequestInt -> message.targetSystem
        is MissionAck -> message.targetSystem
        is MissionCount -> message.targetSystem
        is MissionItemInt -> message.targetSystem
        is MissionClearAll -> message.targetSystem
        is MissionWritePartialList -> message.targetSystem
        is MissionSetCurrent -> message.targetSystem
        is CommandLong -> message.targetSystem
        is CommandInt -> message.targetSystem
        is LogRequestList -> message.targetSystem
        is LogRequestData -> message.targetSystem
        is LogRequestEnd -> message.targetSystem
        else -> null
    }

    private val MISSION_LOCKOUT = setOf(PodLockState.LOCKED, PodLockState.TERMINAL, PodLockState.ENGAGE)

    private fun missionLockout(pod: PodStatus) =
        if (pod.lock in MISSION_LOCKOUT) "mission changes are blocked while the pod reports ${pod.lock}" else null

    /**
     * [params] are the command's seven numbers (param1..param7, or param1..4, x, y, z for COMMAND_INT). Most rows only
     * look at the command id; the vehicle-setup rows also check the numbers, because one command id covers both a
     * harmless calibration and one that isn't (see [SETUP_PATTERNS]).
     */
    private fun classifyCommand(command: UInt, params: List<Float>): TxClassification = when (command) {
        MavCmd.SET_MESSAGE_INTERVAL.value -> always("MAV_CMD_SET_MESSAGE_INTERVAL")
        MavCmd.REQUEST_MESSAGE.value -> always("MAV_CMD_REQUEST_MESSAGE")
        MavCmd.REQUEST_AUTOPILOT_CAPABILITIES.value -> always("MAV_CMD_REQUEST_AUTOPILOT_CAPABILITIES")
        // RUN_PREARM_CHECKS (common.xml #401): read-only. ArduPilot runs AP_Arming::pre_arm_checks(true), which only
        // reports ("PreArm: …" texts), and answers TEMPORARILY_REJECTED while armed (GCS_Common.cpp
        // handle_command_run_prearm_checks, KFT fork d91a40ef). It doesn't arm: arming is still the pilot's (S9).
        MavCmd.RUN_PREARM_CHECKS.value -> always("MAV_CMD_RUN_PREARM_CHECKS")
        // KFT login (spec S12). common.xml defines USER_1/USER_2 (31010/31011) as free for private use; ardupilotKFT
        // uses them as the HMAC challenge request and response, and before login drops every message except
        // HEARTBEAT and these two (GCS_Common.cpp handle_message). ALWAYS: they move nothing, and the GCS must be
        // able to log in whatever the pod reports. Stock ArduPilot answers UNSUPPORTED.
        MavCmd.USER_1.value -> always("MAV_CMD_USER_1 (KFT login challenge request)")
        MavCmd.USER_2.value -> always("MAV_CMD_USER_2 (KFT login response)")

        // S9: everything that arms, moves the aircraft or changes its mode is the pilot's. DO_SET_MODE is here
        // whatever mode it names (GUIDED, RTL, LAND…). MISSION_START switches ArduPilot to AUTO, and
        // DO_CHANGE_SPEED / DO_PAUSE_CONTINUE change what a flying aircraft does, so they are flight actions too.
        MavCmd.COMPONENT_ARM_DISARM.value -> pilotOnly("MAV_CMD_COMPONENT_ARM_DISARM")
        MavCmd.NAV_TAKEOFF.value -> pilotOnly("MAV_CMD_NAV_TAKEOFF")
        MavCmd.DO_SET_MODE.value -> pilotOnly("MAV_CMD_DO_SET_MODE")
        MavCmd.NAV_RETURN_TO_LAUNCH.value -> pilotOnly("MAV_CMD_NAV_RETURN_TO_LAUNCH")
        MavCmd.NAV_LAND.value -> pilotOnly("MAV_CMD_NAV_LAND")
        MavCmd.MISSION_START.value -> pilotOnly("MAV_CMD_MISSION_START")
        MavCmd.DO_CHANGE_SPEED.value -> pilotOnly("MAV_CMD_DO_CHANGE_SPEED")
        MavCmd.DO_PAUSE_CONTINUE.value -> pilotOnly("MAV_CMD_DO_PAUSE_CONTINUE")

        // DO_REPOSITION is a guided setpoint in command form, so it's the same rule as SET_POSITION_TARGET_*.
        MavCmd.DO_REPOSITION.value -> never("MAV_CMD_DO_REPOSITION")
        // Direct servo/relay commands can fire a payload. Survey camera triggering goes inside the mission instead.
        MavCmd.DO_SET_SERVO.value -> never("MAV_CMD_DO_SET_SERVO")
        MavCmd.DO_SET_RELAY.value -> never("MAV_CMD_DO_SET_RELAY")

        // Large Vehicle MagCal (common.xml #42006): compass offsets from a heading the operator states, for a vehicle too
        // big to rotate. GCS_Common.cpp `handle_command_fixed_mag_cal_yaw` → AP_Compass_Calibration.cpp
        // `mag_cal_fixed_yaw` (KFT fork 8e89f41a): it computes the expected field from the World Magnetic Model at the
        // vehicle's position, rotates it by the given yaw, and saves offsets = expected − measured. It moves nothing.
        // The yaw is a free number, so it can't be one of SETUP_PATTERNS' exact lists: allowed are a yaw in [0, 360)
        // and every other number 0 (mask 0 = every compass; lat/lon 0 = the FC's own position). A typed latitude or a
        // compass mask is UNLISTED: the GCS has no reason to send either.
        MavCmd.FIXED_MAG_CAL_YAW.value -> {
            val yaw = params[0]
            if (yaw.isFinite() && yaw >= 0f && yaw < 360f && params.drop(1).all { it == 0f }) TxClassification(TxCategory.SETUP_DISARMED, "MAV_CMD_FIXED_MAG_CAL_YAW")
            else TxClassification(TxCategory.UNLISTED, "MAV_CMD_FIXED_MAG_CAL_YAW with parameters ${params.joinToString(",")}")
        }

        // Vehicle setup: calibrations and a reboot, only on the ground, and only with the exact numbers this GCS sends.
        in SETUP_PATTERNS -> {
            val (label, allowed) = SETUP_PATTERNS.getValue(command)
            if (params in allowed) TxClassification(TxCategory.SETUP_DISARMED, label)
            else TxClassification(TxCategory.UNLISTED, "$label with parameters ${params.joinToString(",")}")
        }

        else -> TxClassification(TxCategory.UNLISTED, "MAV_CMD $command")
    }

    /**
     * The vehicle-setup commands, by command id: a label and every parameter list (param1..7) that may be sent. Anything
     * else with the same id is UNLISTED. ArduPilot refuses all of these while armed itself; SETUP_DISARMED refuses them
     * here first, and fails closed when the armed state is unknown. Handling checked in the KFT fork (d91a40ef):
     *
     * - PREFLIGHT_CALIBRATION (common.xml #241), GCS_Common.cpp `_handle_command_preflight_calibration`. Allowed: baro
     *   (param3 = 1, also zeroes a Plane's airspeed sensor), RC calibration flag on/off (param4 = 1 / all zero: "RC
     *   calibrating" blocks arming while the sticks are swept), accelerometer 6-position (param5 = 1), level (param5 = 2),
     *   simple accelerometer (param5 = 4). **Not** allowed: param6 = 1, Copter's compassmot, which spins the motors
     *   (GCS_Mavlink.cpp `GCS_MAVLINK_Copter::_handle_command_preflight_calibration`); param2/param5 = 76, which
     *   marks an old calibration valid without doing one; and gyro-only (param1 = 1), which ArduPilot does at every boot.
     * - ACCELCAL_VEHICLE_POS (ardupilotmega.xml #42429), `handle_command_accelcal_vehicle_pos`: "the vehicle is now in
     *   position N" (1..6) during the accelerometer calibration. Only starts a sample when N is the step the FC asked for.
     * - DO_START_MAG_CAL (#42424), AP_Compass_Calibration.cpp `handle_mag_cal_command`: every compass (mask 0), no retry,
     *   autosave on, no delay, and **no** autoreboot (param5): a reboot is its own, deliberate command below.
     * - DO_CANCEL_MAG_CAL (#42426), same function: mask 0 cancels every compass.
     * - PREFLIGHT_REBOOT_SHUTDOWN (common.xml #246), `handle_preflight_reboot`: param1 = 1 reboots the autopilot. The
     *   other numbers are 0 on purpose: 42/24/71 in param1..3 are ArduPilot's fault-injection magic, and param6
     *   (y) = 20190226 forces a reboot while armed.
     */
    private val SETUP_PATTERNS: Map<UInt, Pair<String, Set<List<Float>>>> = mapOf(
        MavCmd.PREFLIGHT_CALIBRATION.value to ("MAV_CMD_PREFLIGHT_CALIBRATION" to setOf(
            sevenOf(3 to 1f), sevenOf(4 to 1f), sevenOf(), sevenOf(5 to 1f), sevenOf(5 to 2f), sevenOf(5 to 4f),
        )),
        ArduMavCmd.ACCELCAL_VEHICLE_POS.value to ("MAV_CMD_ACCELCAL_VEHICLE_POS" to (1..6).map { sevenOf(1 to it.toFloat()) }.toSet()),
        ArduMavCmd.DO_START_MAG_CAL.value to ("MAV_CMD_DO_START_MAG_CAL" to setOf(sevenOf(3 to 1f))),
        ArduMavCmd.DO_CANCEL_MAG_CAL.value to ("MAV_CMD_DO_CANCEL_MAG_CAL" to setOf(sevenOf())),
        MavCmd.PREFLIGHT_REBOOT_SHUTDOWN.value to ("MAV_CMD_PREFLIGHT_REBOOT_SHUTDOWN" to setOf(sevenOf(1 to 1f))),
    )

    /** Seven zeros with the given (1-based param number, value) pairs set. */
    private fun sevenOf(vararg set: Pair<Int, Float>) = MutableList(7) { 0f }.also { list -> set.forEach { (n, v) -> list[n - 1] = v } }.toList()

    private fun always(label: String) = TxClassification(TxCategory.ALWAYS, label)
    private fun missionChange(label: String) = TxClassification(TxCategory.MISSION_CHANGE, label)
    private fun pilotOnly(label: String) = TxClassification(TxCategory.PILOT_ONLY, label)
    private fun never(label: String) = TxClassification(TxCategory.NEVER, label)
}
