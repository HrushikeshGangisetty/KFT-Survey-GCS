package com.kft.gcs.core.vehicle

import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.common.MavDataStream
import com.divpundir.mavlink.definitions.common.RequestDataStream
import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.TxResult
import com.kft.gcs.core.mavlink.VehicleInfo
import kotlinx.coroutines.CoroutineScope
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
 * When a vehicle appears it asks for telemetry. ArduPilot sends only heartbeats on a fresh TCP/UDP link until a
 * GCS requests streams. It asks again after every reconnect, because a rebooted autopilot forgets.
 *
 * The inputs are plain flows and a send function rather than the [com.kft.gcs.core.mavlink.ConnectionManager]
 * itself, so tests can drive it without a link. The send function is typed to the one message this class
 * sends, and in the app it goes through the TX gateway like everything else.
 */
class VehicleRepository(
    scope: CoroutineScope,
    frames: Flow<MavFrame<out MavMessage<*>>>,
    link: Flow<LinkState>,
    private val sendStreamRequest: suspend (RequestDataStream) -> TxResult,
) {
    private val _state = MutableStateFlow(VehicleState())
    val state: StateFlow<VehicleState> = _state.asStateFlow()

    init {
        // One collector for link changes and frames, so `vehicle` and the state are never updated concurrently.
        scope.launch {
            var vehicle: VehicleInfo? = null
            merge(link.map { Update.Link(it) }, frames.map { Update.Frame(it) }).collect { update ->
                when (update) {
                    is Update.Link -> {
                        val seen = (update.state as? LinkState.Connected)?.vehicle
                        if (update.state == LinkState.Disconnected) _state.value = VehicleState()
                        if (seen != null && vehicle == null) sendStreamRequest(streamRequest(seen))
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

    private sealed interface Update {
        data class Link(val state: LinkState) : Update
        data class Frame(val frame: MavFrame<out MavMessage<*>>) : Update
    }

    companion object {
        /** 4 Hz is plenty for a map and HUD, and light on a 57600-baud telemetry radio. */
        const val STREAM_RATE_HZ: UShort = 4u

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
