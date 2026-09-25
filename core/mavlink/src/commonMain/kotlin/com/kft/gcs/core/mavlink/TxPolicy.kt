package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.common.CommandInt
import com.divpundir.mavlink.definitions.common.CommandLong
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
internal enum class TxCategory { ALWAYS, OPERATOR, MISSION_CHANGE, MISSION_CHANGE_DISARMED, PILOT_ONLY, NEVER, UNLISTED }

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

        is ParamSet -> TxClassification(TxCategory.OPERATOR, "PARAM_SET")

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
        is CommandLong -> classifyCommand(message.command.value)
        is CommandInt -> classifyCommand(message.command.value)

        // Never, whatever the state. Guidance belongs to the pod's pod_mavlink module alone (pod invariant 7).
        is SetPositionTargetLocalNed -> never("SET_POSITION_TARGET_LOCAL_NED")
        is SetPositionTargetGlobalInt -> never("SET_POSITION_TARGET_GLOBAL_INT")
        is SetAttitudeTarget -> never("SET_ATTITUDE_TARGET")
        // RC is the pilot's. AI-enable and target-lock are RC channels, so an RC override from the GCS could
        // raise them. That would be engagement over the GCS, which pod invariant 4 forbids.
        is RcChannelsOverride -> never("RC_CHANNELS_OVERRIDE")
        is ManualControl -> never("MANUAL_CONTROL")

        else -> TxClassification(TxCategory.UNLISTED, message.instanceCompanion.id.let { "message id $it" })
    }

    /**
     * Null means allowed. Otherwise the reason the message is refused.
     *
     * @param armed the vehicle's armed flag from its latest heartbeat, or null when no vehicle is heard. Unknown
     *   counts as armed for [TxCategory.MISSION_CHANGE_DISARMED]: fail closed.
     */
    fun check(category: TxCategory, pod: PodStatus, armed: Boolean?): String? = when (category) {
        TxCategory.ALWAYS, TxCategory.OPERATOR -> null
        TxCategory.MISSION_CHANGE -> missionLockout(pod)
        TxCategory.MISSION_CHANGE_DISARMED -> missionLockout(pod)
            ?: if (armed != false) "only allowed while the vehicle is disarmed (spec S9)" else null
        TxCategory.PILOT_ONLY -> "flight actions belong to the pilot on the RC, never the GCS (spec S9)"
        TxCategory.NEVER -> "never sent from the GCS (pod contract §3)"
        TxCategory.UNLISTED -> "not on the TX allowlist"
    }

    private val MISSION_LOCKOUT = setOf(PodLockState.LOCKED, PodLockState.TERMINAL, PodLockState.ENGAGE)

    private fun missionLockout(pod: PodStatus) =
        if (pod.lock in MISSION_LOCKOUT) "mission changes are blocked while the pod reports ${pod.lock}" else null

    private fun classifyCommand(command: UInt): TxClassification = when (command) {
        MavCmd.SET_MESSAGE_INTERVAL.value -> always("MAV_CMD_SET_MESSAGE_INTERVAL")
        MavCmd.REQUEST_MESSAGE.value -> always("MAV_CMD_REQUEST_MESSAGE")
        MavCmd.REQUEST_AUTOPILOT_CAPABILITIES.value -> always("MAV_CMD_REQUEST_AUTOPILOT_CAPABILITIES")
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

        else -> TxClassification(TxCategory.UNLISTED, "MAV_CMD $command")
    }

    private fun always(label: String) = TxClassification(TxCategory.ALWAYS, label)
    private fun missionChange(label: String) = TxClassification(TxCategory.MISSION_CHANGE, label)
    private fun pilotOnly(label: String) = TxClassification(TxCategory.PILOT_ONLY, label)
    private fun never(label: String) = TxClassification(TxCategory.NEVER, label)
}
