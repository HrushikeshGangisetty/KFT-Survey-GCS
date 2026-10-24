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
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.IOException

/**
 * Which vehicle, app-wide (ADR-005): the link it is heard on plus its MAVLink system id. The link is part of the
 * identity because two drones on two radios may both still have the factory system id 1.
 * @property link the number [Links] gave the link when it opened.
 */
data class VehicleId(val link: Int, val systemId: UByte)

/** An ArduPilot vehicle heard on the link, from its HEARTBEAT. */
data class VehicleInfo(
    val systemId: UByte,
    val componentId: UByte,
    val kind: VehicleKind,
    val armed: Boolean,
    /** ArduPilot custom mode number; its meaning depends on [kind]. */
    val customMode: UInt,
    /**
     * Two autopilots on this link are using this system id (ADR-005 §4). MAVLink can't tell them apart, so nothing is
     * sent to this id (the gateway refuses) and its telemetry isn't trusted until one of them is given another id.
     */
    val duplicateId: Boolean = false,
)

/** Where the link is. The UI draws straight from this. */
sealed interface LinkState {
    data object Disconnected : LinkState

    /** Opening the link. [attempt] > 1 means auto-reconnect is retrying; [lastError] says why the previous try ended. */
    data class Connecting(val config: LinkConfig, val attempt: Int, val lastError: String?) : LinkState

    /**
     * The link is open. [vehicles] is every vehicle heard on it, by system id. One is dropped when its heartbeats stop
     * for [ConnectionManager.HEARTBEAT_TIMEOUT] (radio out of range, vehicle powered off).
     *
     * [vehicle] is the one this state is about: on the link's own state the first of [vehicles] (null until a
     * heartbeat arrives), on a vehicle's view of the link ([ConnectionManager.viewOf]) that vehicle. The per-vehicle
     * classes in `core:vehicle` only ever read [vehicle], which is why they didn't change when links learned to carry
     * several vehicles (ADR-005 §2).
     */
    data class Connected(
        val config: LinkConfig,
        val vehicle: VehicleInfo?,
        val stats: LinkStats,
        val vehicles: List<VehicleInfo> = listOfNotNull(vehicle),
    ) : LinkState
}

/**
 * This state as the vehicle with [systemId] sees it: the same, with [LinkState.Connected.vehicle] being that vehicle
 * (null while its heartbeat isn't heard).
 */
fun LinkState.forVehicle(systemId: UByte): LinkState =
    if (this is LinkState.Connected) copy(vehicle = vehicles.find { it.systemId == systemId }) else this

/**
 * One open link, as `core:vehicle` uses it. [ConnectionManager] is the real one; an interface so the fleet's tests
 * can script a link with fake flight controllers instead of sockets.
 */
interface MavLink {
    /** This link's number, the `link` of every [VehicleId] on it. */
    val id: Int
    val state: StateFlow<LinkState>

    /** Every frame received on the link, from every vehicle. */
    val frames: Flow<MavFrame<out MavMessage<*>>>

    /** A sender that can only reach the vehicle with [systemId] on this link ([MavTxGateway.forVehicle]). */
    fun senderFor(systemId: UByte): MavSender
}

/**
 * Each link in the list together with its current state. Emits again whenever a link opens, closes or changes state,
 * so one collector can watch every link.
 */
@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
fun <L : MavLink> Flow<List<L>>.withStates(): Flow<List<Pair<L, LinkState>>> = flatMapLatest { links ->
    // combine() of an empty list never emits, so "no links" is spelled out.
    if (links.isEmpty()) flowOf(emptyList()) else combine(links.map { link -> link.state.map { link to it } }) { it.toList() }
}

/**
 * Owns one MAVLink link: opening it, keeping it alive, and closing it. The app has one of these per open link
 * ([Links]); a link may carry several vehicles (ADR-005).
 *
 * - [connect] keeps trying until [disconnect]: if the link fails it reconnects with a growing delay (1, 2, 4, 5, 5… s).
 * - While open it sends the GCS heartbeat at 1 Hz (through the gateway, like everything else), watches for the
 *   heartbeat of every vehicle on the link, and updates [LinkStats] once a second.
 * - [frames] carries every received frame to `core:vehicle`, which turns them into telemetry.
 *
 * Runs in the app-wide [scope], not a screen's scope, so leaving the Connections screen doesn't drop the link.
 *
 */
class ConnectionManager internal constructor(
    private val scope: CoroutineScope,
    private val openConnection: (LinkConfig) -> OpenedLink,
    private val timeSource: TimeSource.WithComparableMarks,
    podStatus: StateFlow<PodStatus>,
    override val id: Int = 1,
) : MavLink {
    /** Production constructor: real UDP/TCP/serial/T12 transports doing their blocking I/O on [ioDispatcher]. */
    constructor(scope: CoroutineScope, ioDispatcher: CoroutineDispatcher, podStatus: StateFlow<PodStatus>, serialPorts: SerialPorts, t12: T12Link, id: Int = 1) :
        this(scope, { config -> openTransportConnection(config, ioDispatcher, serialPorts, t12) }, TimeSource.Monotonic, podStatus, id)

    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    // Telemetry consumers must never slow the reader down, so a slow collector loses the oldest frames instead.
    private val _frames = MutableSharedFlow<MavFrame<out MavMessage<*>>>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val frames: SharedFlow<MavFrame<out MavMessage<*>>> = _frames.asSharedFlow()

    // The gateway asks about the vehicle it is sending to at send time, from the same heartbeat the UI shows.
    val gateway = MavTxGateway(podStatus) { systemId -> (_state.value as? LinkState.Connected)?.vehicles?.find { it.systemId == systemId } }

    /** What this link is set to open, or null once it is disconnected. Set by [connect] itself, before [state] follows. */
    var config: LinkConfig? = null
        private set

    override fun senderFor(systemId: UByte): MavSender = gateway.forVehicle(systemId)

    private var linkJob: Job? = null

    /** Opens [config], replacing any current link. Returns immediately; watch [state] for progress. */
    fun connect(config: LinkConfig) {
        this.config = config
        linkJob?.cancel()
        linkJob = scope.launch { runLink(config) }
    }

    /** Closes the link and stops reconnecting. */
    fun disconnect() {
        config = null
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
            val link = openConnection(config)
            val connection = link.connection
            try {
                coroutineScope {
                    connection.connect(readerScope = this)
                    failures = 0
                    gateway.attach(link.frameSink())
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
        val heard = HashMap<UByte, Heard>() // by system id
        fun publish() {
            val vehicles = heard.values.map { it.info }.sortedBy { it.systemId }
            _state.value = LinkState.Connected(config, vehicles.firstOrNull(), stats.snapshot(), vehicles)
        }
        publish()

        // Frames and the 1 s tick go through ONE collector, so `stats` and `heard` are only ever touched by one
        // coroutine at a time. Two separate coroutines would race on a multi-threaded dispatcher.
        val ticks = flow { while (true) { delay(1.seconds); emit(null) } }
        launch {
            merge(connection.mavFrame, ticks).collect { frame ->
                if (frame == null) {
                    stats.tick()
                    heard.values.removeAll { it.lastHeartbeat.elapsedNow() > HEARTBEAT_TIMEOUT }
                    heard.values.forEach { it.info = it.info.copy(duplicateId = it.isDuplicate()) }
                    publish()
                    return@collect
                }
                stats.onFrame(frame.systemId, frame.componentId, frame.sequence)
                val message = frame.message
                if (message is Heartbeat && message.autopilot.value == MavAutopilot.ARDUPILOTMEGA.value) {
                    val now = timeSource.markNow()
                    val h = heard.getOrPut(frame.systemId) { Heard(now) }
                    h.lastHeartbeat = now
                    h.beats += now
                    val info = vehicleFrom(frame.systemId, frame.componentId, message).copy(duplicateId = h.isDuplicate())
                    if (info != h.info) {
                        h.info = info
                        publish()
                    }
                }
                _frames.emit(frame)
            }
        }
        launch {
            while (true) {
                gateway.sendHeartbeat(GCS_HEARTBEAT)
                delay(1.seconds)
            }
        }

        val failed = connection.streamState.first { it is StreamState.Inactive }
        throw IOException((failed as? StreamState.Inactive.Failed)?.cause?.message ?: "link closed")
    }

    /** One system id's bookkeeping in [runSession]: what its last heartbeat said, and when the recent ones arrived. */
    private class Heard(var lastHeartbeat: ComparableTimeMark) {
        // A placeholder until the first heartbeat is stored, a few lines after this is created; never published.
        var info = VehicleInfo(0u, 0u, VehicleKind.UNKNOWN, armed = false, customMode = 0u)
        val beats = ArrayDeque<ComparableTimeMark>()

        /**
         * ADR-005 §4: ArduPilot sends one heartbeat a second, so [DUPLICATE_BEATS] or more inside [DUPLICATE_WINDOW]
         * is two autopilots answering as one system id. Once set it stays until the rate is back to one sender's
         * ([DUPLICATE_CLEAR_BEATS]), so it doesn't flicker at the threshold.
         */
        fun isDuplicate(): Boolean {
            while (beats.isNotEmpty() && beats.first().elapsedNow() > DUPLICATE_WINDOW) beats.removeFirst()
            return if (info.duplicateId) beats.size > DUPLICATE_CLEAR_BEATS else beats.size >= DUPLICATE_BEATS
        }
    }

    companion object {
        /** See [Heard.isDuplicate]. One sender gives 10 or 11 heartbeats in 10 s, two give about 20. */
        val DUPLICATE_WINDOW = 10.seconds
        const val DUPLICATE_BEATS = 16
        const val DUPLICATE_CLEAR_BEATS = 12

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
