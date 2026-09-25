package com.kft.gcs.core.vehicle

import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.common.CommandLong
import com.divpundir.mavlink.definitions.common.HomePosition
import com.divpundir.mavlink.definitions.common.MavCmd
import com.divpundir.mavlink.definitions.common.MavDataStream
import com.divpundir.mavlink.definitions.common.RequestDataStream
import com.divpundir.mavlink.definitions.standard.AutopilotVersion
import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.MavSender
import com.kft.gcs.core.mavlink.VehicleInfo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Turns the raw MAVLink stream into one [VehicleState] for the whole app.
 *
 * When a vehicle appears it asks for what the GCS needs ([requestStartupMessages]). ArduPilot sends only
 * heartbeats on a fresh TCP/UDP link until a GCS asks. It asks again after every reconnect, because a rebooted
 * autopilot forgets.
 *
 * The inputs are plain flows and a [MavSender] rather than the [com.kft.gcs.core.mavlink.ConnectionManager]
 * itself, so tests can drive it with a fake flight controller. In the app the sender is the TX gateway.
 */
class VehicleRepository(
    private val scope: CoroutineScope,
    frames: Flow<MavFrame<out MavMessage<*>>>,
    link: Flow<LinkState>,
    private val sender: MavSender,
) {
    private val commands = CommandProtocol(frames, sender)

    private val _state = MutableStateFlow(VehicleState())
    val state: StateFlow<VehicleState> = _state.asStateFlow()

    init {
        // One collector for link changes and frames, so `vehicle` and the state are never updated concurrently.
        scope.launch {
            var vehicle: VehicleInfo? = null
            var startup: Job? = null
            merge(link.map { Update.Link(it) }, frames.map { Update.Frame(it) }).collect { update ->
                when (update) {
                    is Update.Link -> {
                        val seen = (update.state as? LinkState.Connected)?.vehicle
                        if (update.state == LinkState.Disconnected) _state.value = VehicleState()
                        if (seen != null && vehicle == null) startup = launchStartup(seen)
                        if (seen == null) startup?.cancel()
                        vehicle = seen
                        _state.value = if (seen == null) _state.value.copy(connected = false)
                        else _state.value.copy(connected = true, armed = seen.armed, flightMode = flightModeName(seen.kind, seen.customMode))
                    }
                    is Update.Frame -> {
                        val v = vehicle ?: return@collect
                        // Only the autopilot's own frames: a gimbal or companion computer on the same link has its
                        // own attitude and status messages that must not overwrite the vehicle's.
                        if (update.frame.systemId == v.systemId && update.frame.componentId == v.componentId) {
                            _state.value = _state.value.reduce(update.frame.message)
                        }
                    }
                }
            }
        }
    }

    // Its own coroutine: the requests wait for COMMAND_ACKs, which arrive on the frames this collector must keep reading.
    private fun launchStartup(vehicle: VehicleInfo) = scope.launch { requestStartupMessages(vehicle) }

    /**
     * Best effort, in order of importance. A refusal or missing ACK isn't retried here: the data either arrives
     * another way (ArduPilot broadcasts HOME_POSITION when home changes) or the UI shows a dash.
     */
    private suspend fun requestStartupMessages(v: VehicleInfo) {
        // REQUEST_DATA_STREAM is a plain message, not a command: ArduPilot never ACKs it.
        sender.send(streamRequest(v))
        commands.send(requestMessage(v, AutopilotVersion.id))
        commands.send(requestMessage(v, HomePosition.id))
        commands.send(messageInterval(v, HomePosition.id, HOME_INTERVAL))
    }

    private sealed interface Update {
        data class Link(val state: LinkState) : Update
        data class Frame(val frame: MavFrame<out MavMessage<*>>) : Update
    }

    companion object {
        /** 4 Hz is plenty for a map and HUD, and light on a 57600-baud telemetry radio. */
        const val STREAM_RATE_HZ: UShort = 4u

        /**
         * HOME_POSITION isn't in any ArduPilot stream group. ArduPilot does broadcast it when home changes, but one
         * lost packet (say, the one sent at arming) would leave a stale home marker for the whole flight. A copy
         * every 10 s costs about 6 bytes/s.
         */
        val HOME_INTERVAL = 10.seconds

        /**
         * MAV_CMD_REQUEST_MESSAGE (common.xml): "send this message once". ArduPilot looks the id up in its own
         * table and answers FAILED for ids it can't send; for HOME_POSITION it sends nothing until home is set,
         * but still ACKs ACCEPTED.
         */
        internal fun requestMessage(v: VehicleInfo, messageId: UInt) = CommandLong(
            targetSystem = v.systemId,
            targetComponent = v.componentId,
            command = MavEnumValue.of(MavCmd.REQUEST_MESSAGE),
            param1 = messageId.toFloat(),
        )

        /**
         * MAV_CMD_SET_MESSAGE_INTERVAL (common.xml): param1 message id, param2 interval in µs. ArduPilot applies it
         * to this link only, clamps it to 1 ms..60 s, and DENIES a nonzero param3 (GCS_Common.cpp
         * `handle_command_set_message_interval`). The rate lasts until the autopilot reboots, which is why this
         * is sent again on every reconnect.
         */
        internal fun messageInterval(v: VehicleInfo, messageId: UInt, interval: Duration) = CommandLong(
            targetSystem = v.systemId,
            targetComponent = v.componentId,
            command = MavEnumValue.of(MavCmd.SET_MESSAGE_INTERVAL),
            param1 = messageId.toFloat(),
            param2 = interval.inWholeMicroseconds.toFloat(),
        )

        /**
         * REQUEST_DATA_STREAM(ALL) sets every ArduPilot SRx stream group in one message. It's deprecated in
         * MAVLink in favour of per-message SET_MESSAGE_INTERVAL, but ArduPilot still honours it and it's what
         * Mission Planner sends. Per-message rates can come later if we need them.
         */
        internal fun streamRequest(vehicle: VehicleInfo) = RequestDataStream(
            targetSystem = vehicle.systemId,
            targetComponent = vehicle.componentId,
            reqStreamId = MavEnumValue.of(MavDataStream.ALL),
            reqMessageRate = STREAM_RATE_HZ,
            startStop = 1u,
        )
    }
}
