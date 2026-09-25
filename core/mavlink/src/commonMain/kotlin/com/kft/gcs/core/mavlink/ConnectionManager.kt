package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.adapters.coroutines.CoroutinesMavConnection
import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.connection.StreamState
import com.divpundir.mavlink.definitions.minimal.Heartbeat
import com.divpundir.mavlink.definitions.minimal.MavAutopilot
import com.divpundir.mavlink.definitions.minimal.MavModeFlag
import com.divpundir.mavlink.definitions.minimal.MavState
import com.divpundir.mavlink.definitions.minimal.MavType
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.IOException

/** The ArduPilot vehicle heard on the link, from its HEARTBEAT. */
data class VehicleInfo(
    val systemId: UByte,
    val componentId: UByte,
    val kind: VehicleKind,
    val armed: Boolean,
    /** ArduPilot custom mode number; its meaning depends on [kind]. */
    val customMode: UInt,
)

/** Where the link is. The UI draws straight from this. */
sealed interface LinkState {
    data object Disconnected : LinkState

    /** Opening the link. [attempt] > 1 means auto-reconnect is retrying; [lastError] says why the previous try ended. */
    data class Connecting(val config: LinkConfig, val attempt: Int, val lastError: String?) : LinkState

    /**
     * The link is open. [vehicle] is null until a heartbeat arrives, and again if heartbeats stop for
     * [ConnectionManager.HEARTBEAT_TIMEOUT] (radio out of range, vehicle powered off).
     */
    data class Connected(val config: LinkConfig, val vehicle: VehicleInfo?, val stats: LinkStats) : LinkState
}

/**
 * Owns the one MAVLink link (P0 is single-vehicle): opening it, keeping it alive, and closing it.
 *
 * - [connect] keeps trying until [disconnect]: if the link fails it reconnects with a growing delay (1, 2, 4, 5, 5… s).
 * - While open it sends the GCS heartbeat at 1 Hz (through the gateway, like everything else), watches for the
 *   vehicle's heartbeat, and updates [LinkStats] once a second.
 * - [frames] carries every received frame to `core:vehicle`, which turns them into telemetry.
 *
 * Runs in the app-wide [scope], not a screen's scope, so leaving the Connections screen doesn't drop the link.
 */
class ConnectionManager internal constructor(
    private val scope: CoroutineScope,
    private val openConnection: (LinkConfig) -> CoroutinesMavConnection,
    private val timeSource: TimeSource.WithComparableMarks,
    podStatus: StateFlow<PodStatus>,
) {
    /** Production constructor: real UDP/TCP/serial transports doing their blocking I/O on [ioDispatcher]. */
    constructor(scope: CoroutineScope, ioDispatcher: CoroutineDispatcher, podStatus: StateFlow<PodStatus>, serialPorts: SerialPorts) :
        this(scope, { config -> openTransportConnection(config, ioDispatcher, serialPorts) }, TimeSource.Monotonic, podStatus)

    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    // Telemetry consumers must never slow the reader down, so a slow collector loses the oldest frames instead.
    private val _frames = MutableSharedFlow<MavFrame<out MavMessage<*>>>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val frames: SharedFlow<MavFrame<out MavMessage<*>>> = _frames.asSharedFlow()

    val gateway = MavTxGateway(podStatus)

    private var linkJob: Job? = null

    /** Opens [config], replacing any current link. Returns immediately; watch [state] for progress. */
    fun connect(config: LinkConfig) {
        linkJob?.cancel()
        linkJob = scope.launch { runLink(config) }
    }

    /** Closes the link and stops reconnecting. */
    fun disconnect() {
        linkJob?.cancel()
        linkJob = null
        _state.value = LinkState.Disconnected
    }

    /** The reconnect loop. Each pass opens one connection and runs it until it fails; cancellation ends the loop. */
    private suspend fun runLink(config: LinkConfig) {
        var failures = 0 // in a row; reset once a connection opens
        var lastError: String? = null
        while (true) {
            _state.value = LinkState.Connecting(config, attempt = failures + 1, lastError)
            val connection = openConnection(config)
            try {
                coroutineScope {
                    connection.connect(readerScope = this)
                    failures = 0
                    try {
                        runSession(config, connection)
                    } finally {
                        // Close before leaving: the reader is blocked in a socket read that only closing interrupts,
                        // and coroutineScope waits for it. NonCancellable because we may be here due to cancellation.
                        gateway.detach()
                        withContext(NonCancellable) { runCatching { connection.close() } }
                    }
                }
            } catch (e: IOException) {
                lastError = e.message ?: e::class.simpleName
            }
            failures++
            // Show "reconnecting" straight away rather than a stale "connected" for the whole backoff.
            _state.value = LinkState.Connecting(config, attempt = failures + 1, lastError)
            delay(backoff(failures))
        }
    }

    /** Runs one open connection until it fails, then throws [IOException]. Never returns normally. */
    private suspend fun runSession(config: LinkConfig, connection: CoroutinesMavConnection): Nothing = coroutineScope {
        val stats = LinkStatsCounter()
        var vehicle: VehicleInfo? = null
        var lastHeartbeat = timeSource.markNow()
        gateway.attach(connection)
        _state.value = LinkState.Connected(config, null, stats.snapshot())

        // Frames and the 1 s tick go through ONE collector, so `stats`, `vehicle` and `lastHeartbeat` are only
        // ever touched by one coroutine at a time. Two separate coroutines would race on a multi-threaded dispatcher.
        val ticks = flow { while (true) { delay(1.seconds); emit(null) } }
        launch {
            merge(connection.mavFrame, ticks).collect { frame ->
                if (frame == null) {
                    stats.tick()
                    if (vehicle != null && lastHeartbeat.elapsedNow() > HEARTBEAT_TIMEOUT) vehicle = null
                    _state.value = LinkState.Connected(config, vehicle, stats.snapshot())
                    return@collect
                }
                stats.onFrame(frame.systemId, frame.componentId, frame.sequence)
                val message = frame.message
                if (message is Heartbeat && message.autopilot.value == MavAutopilot.ARDUPILOTMEGA.value) {
                    lastHeartbeat = timeSource.markNow()
                    val newVehicle = vehicleFrom(frame.systemId, frame.componentId, message)
                    if (newVehicle != vehicle) {
                        vehicle = newVehicle
                        _state.value = LinkState.Connected(config, vehicle, stats.snapshot())
                    }
                }
                _frames.emit(frame)
            }
        }
        launch {
            while (true) {
                gateway.send(GCS_HEARTBEAT)
                delay(1.seconds)
            }
        }

        val failed = connection.streamState.first { it is StreamState.Inactive }
        throw IOException((failed as? StreamState.Inactive.Failed)?.cause?.message ?: "link closed")
    }

    companion object {
        /** No vehicle heartbeat for this long = link lost. ArduPilot sends one per second, so 3 s tolerates 2 drops. */
        val HEARTBEAT_TIMEOUT = 3.seconds

        private val GCS_HEARTBEAT = Heartbeat(
            type = MavEnumValue.of(MavType.GCS),
            autopilot = MavEnumValue.of(MavAutopilot.INVALID),
            systemStatus = MavEnumValue.of(MavState.ACTIVE),
            mavlinkVersion = 3u,
        )

        /** Wait before retry number [failures]: 1, 2, 4, then 5 s from then on. */
        internal fun backoff(failures: Int) = (1.seconds * (1 shl (failures - 1).coerceIn(0, 3))).coerceAtMost(5.seconds)

        internal fun vehicleFrom(systemId: UByte, componentId: UByte, heartbeat: Heartbeat) = VehicleInfo(
            systemId = systemId,
            componentId = componentId,
            kind = vehicleKindOf(heartbeat.type.value),
            armed = heartbeat.baseMode.value and MavModeFlag.SAFETY_ARMED.value != 0u,
            customMode = heartbeat.customMode,
        )

        /**
         * MAV_TYPE → firmware family. QuadPlanes report VTOL types but run ArduPlane, so they use Plane's mode
         * numbers. Values from mavlink-kotlin's `MavType`.
         */
        internal fun vehicleKindOf(mavType: UInt): VehicleKind = when (mavType) {
            MavType.FIXED_WING.value,
            in MavType.VTOL_TAILSITTER_DUOROTOR.value..MavType.VTOL_RESERVED5.value -> VehicleKind.PLANE
            MavType.QUADROTOR.value, MavType.COAXIAL.value, MavType.HELICOPTER.value, MavType.HEXAROTOR.value,
            MavType.OCTOROTOR.value, MavType.TRICOPTER.value, MavType.DODECAROTOR.value -> VehicleKind.COPTER
            else -> VehicleKind.UNKNOWN
        }
    }
}
