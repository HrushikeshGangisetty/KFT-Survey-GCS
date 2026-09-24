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

/** The rows of the TX rules table in the pod contract §3, plus [UNLISTED] for everything the table doesn't name. */
internal enum class TxCategory { ALWAYS, OPERATOR, MISSION_CHANGE, SAFE_DIRECTION, ENTER_GUIDED, NEVER, UNLISTED }

/** A message's category plus a human-readable name for logs and rejection reasons. */
internal data class TxClassification(val category: TxCategory, val label: String)

/**
 * The allowlist, as pure functions: [classify] puts a message into a row of the contract table, [check] applies
 * that row's rule to the current pod state. Pure on purpose: every row is unit-tested without a transport.
 *
 * Anything not explicitly listed is [TxCategory.UNLISTED] and rejected. Default-deny means a new message type
 * can't slip through just because nobody thought about it; adding it here (with a test) is a deliberate act.
 */
internal object TxPolicy {

    fun classify(message: MavMessage<*>, vehicle: VehicleKind): TxClassification = when (message) {
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
        is MissionSetCurrent -> missionChange("MISSION_SET_CURRENT")
        is MissionWritePartialList -> missionChange("MISSION_WRITE_PARTIAL_LIST")

        is SetMode -> classifyMode(message.customMode, vehicle, "SET_MODE")
        is CommandLong -> classifyCommand(message.command.value, message.param2, vehicle)
        is CommandInt -> classifyCommand(message.command.value, message.param2, vehicle)

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

    /** Null means allowed. Otherwise the reason the message is refused. */
    fun check(category: TxCategory, pod: PodStatus): String? = when (category) {
        TxCategory.ALWAYS, TxCategory.OPERATOR, TxCategory.SAFE_DIRECTION -> null
        TxCategory.MISSION_CHANGE ->
            if (pod.lock in MISSION_LOCKOUT) "mission changes are blocked while the pod reports ${pod.lock}" else null
        TxCategory.ENTER_GUIDED ->
            if (pod.aiEnableHigh) "entering GUIDED is blocked while pod AI-enable is high" else null
        TxCategory.NEVER -> "never sent from the GCS (pod contract §3)"
        TxCategory.UNLISTED -> "not on the TX allowlist"
    }

    private val MISSION_LOCKOUT = setOf(PodLockState.LOCKED, PodLockState.TERMINAL, PodLockState.ENGAGE)

    private fun classifyCommand(command: UInt, param2: Float, vehicle: VehicleKind): TxClassification = when (command) {
        MavCmd.SET_MESSAGE_INTERVAL.value -> always("MAV_CMD_SET_MESSAGE_INTERVAL")
        MavCmd.REQUEST_MESSAGE.value -> always("MAV_CMD_REQUEST_MESSAGE")
        MavCmd.REQUEST_AUTOPILOT_CAPABILITIES.value -> always("MAV_CMD_REQUEST_AUTOPILOT_CAPABILITIES")

        MavCmd.COMPONENT_ARM_DISARM.value -> operator("MAV_CMD_COMPONENT_ARM_DISARM")
        MavCmd.NAV_TAKEOFF.value -> operator("MAV_CMD_NAV_TAKEOFF")
        MavCmd.MISSION_START.value -> operator("MAV_CMD_MISSION_START")
        MavCmd.DO_CHANGE_SPEED.value -> operator("MAV_CMD_DO_CHANGE_SPEED")
        MavCmd.DO_PAUSE_CONTINUE.value -> operator("MAV_CMD_DO_PAUSE_CONTINUE")

        MavCmd.NAV_RETURN_TO_LAUNCH.value -> safe("MAV_CMD_NAV_RETURN_TO_LAUNCH")
        MavCmd.NAV_LAND.value -> safe("MAV_CMD_NAV_LAND")

        // DO_SET_MODE carries ArduPilot's custom mode number in param2.
        MavCmd.DO_SET_MODE.value -> classifyMode(param2.toUInt(), vehicle, "MAV_CMD_DO_SET_MODE")

        // DO_REPOSITION is a guided setpoint in command form, so it's the same rule as SET_POSITION_TARGET_*.
        MavCmd.DO_REPOSITION.value -> never("MAV_CMD_DO_REPOSITION")
        // Direct servo/relay commands can fire a payload. Survey camera triggering goes inside the mission instead.
        MavCmd.DO_SET_SERVO.value -> never("MAV_CMD_DO_SET_SERVO")
        MavCmd.DO_SET_RELAY.value -> never("MAV_CMD_DO_SET_RELAY")

        else -> TxClassification(TxCategory.UNLISTED, "MAV_CMD $command")
    }

    /**
     * Sorts an ArduPilot custom mode into ENTER_GUIDED / SAFE_DIRECTION / OPERATOR. Numbers come from ArduPilot's
     * mode enums (mavlink-kotlin `CopterMode` / `PlaneMode`). Plane has no LAND mode: QLAND and QRTL are its
     * VTOL landing/return modes, so they count as safe-direction too.
     */
    private fun classifyMode(mode: UInt, vehicle: VehicleKind, via: String): TxClassification {
        val label = "$via mode $mode"
        return when (vehicle) {
            VehicleKind.COPTER -> when (mode) {
                COPTER_GUIDED, COPTER_GUIDED_NOGPS -> TxClassification(TxCategory.ENTER_GUIDED, label)
                in COPTER_SAFE -> TxClassification(TxCategory.SAFE_DIRECTION, label)
                else -> TxClassification(TxCategory.OPERATOR, label)
            }
            VehicleKind.PLANE -> when (mode) {
                PLANE_GUIDED -> TxClassification(TxCategory.ENTER_GUIDED, label)
                in PLANE_SAFE -> TxClassification(TxCategory.SAFE_DIRECTION, label)
                else -> TxClassification(TxCategory.OPERATOR, label)
            }
            // Without a vehicle type we can't tell whether this number means GUIDED, so refuse rather than guess.
            VehicleKind.UNKNOWN -> TxClassification(TxCategory.UNLISTED, "$label on an unknown vehicle type")
        }
    }

    private const val COPTER_GUIDED = 4u
    private const val COPTER_GUIDED_NOGPS = 20u
    private val COPTER_SAFE = setOf(6u, 5u, 9u, 17u)   // RTL, LOITER, LAND, BRAKE
    private const val PLANE_GUIDED = 15u
    private val PLANE_SAFE = setOf(11u, 12u, 20u, 21u) // RTL, LOITER, QLAND, QRTL

    private fun always(label: String) = TxClassification(TxCategory.ALWAYS, label)
    private fun operator(label: String) = TxClassification(TxCategory.OPERATOR, label)
    private fun missionChange(label: String) = TxClassification(TxCategory.MISSION_CHANGE, label)
    private fun safe(label: String) = TxClassification(TxCategory.SAFE_DIRECTION, label)
    private fun never(label: String) = TxClassification(TxCategory.NEVER, label)
}
