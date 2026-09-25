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
import com.divpundir.mavlink.definitions.minimal.Heartbeat
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Turns the raw MAVLink stream into one [VehicleState] for the whole app, and runs the connect-time sequence.
 *
 * Connect-time sequence, per link session (spec S12):
 * ```
 * first vehicle heartbeat ─▶ KFT login ─▶ AUTHENTICATED / LEGACY_FIRMWARE / NO_KEY ─▶ requestStartupMessages()
 *                                     └─▶ DENIED, or FAILED after the bounded retries ─▶ nothing more is sent
 * heartbeat gap > RELOGIN_GAP (link recovered) ─▶ the whole sequence again (at most one start per LOGIN_DEBOUNCE)
 * ```
 * The login comes first because a KFT flight controller drops everything else until it succeeds, including the
 * stream requests. ArduPilot sends only heartbeats on a fresh TCP/UDP link until a GCS asks for streams, and a
 * rebooted autopilot forgets what it was asked, which is why the sequence runs again after a gap.
 *
 * The inputs are plain flows and a [MavSender] rather than the [com.kft.gcs.core.mavlink.ConnectionManager]
 * itself, so tests can drive it with a fake flight controller. In the app the sender is the TX gateway.
 *
 * @param loginKey the 32-byte KFT fleet key, or null when none is configured ([KftLoginStatus.NO_KEY]).
 * @param timeSource for heartbeat gaps; tests pass virtual time.
 */
class VehicleRepository(
    private val scope: CoroutineScope,
    frames: Flow<MavFrame<out MavMessage<*>>>,
    link: Flow<LinkState>,
    private val sender: MavSender,
    loginKey: ByteArray?,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    private val commands = CommandProtocol(frames, sender)
    private val login = loginKey?.let { KftLogin(frames, commands, it) }

    private val _state = MutableStateFlow(VehicleState())
    val state: StateFlow<VehicleState> = _state.asStateFlow()

    // Touched only by the collector coroutine below, so no locking.
    private var session: Job? = null
    private var lastSessionStart: ComparableTimeMark? = null

    init {
        // One collector for link changes and frames, so `vehicle` and the session bookkeeping never race.
        // Two writers touch _state (this collector and the session's login status), so every write is an atomic update.
        scope.launch {
            var vehicle: VehicleInfo? = null
            var lastHeartbeat: ComparableTimeMark? = null // this link session's latest vehicle heartbeat
            merge(link.map { Update.Link(it) }, frames.map { Update.Frame(it) }).collect { update ->
                when (update) {
                    is Update.Link -> {
                        val seen = (update.state as? LinkState.Connected)?.vehicle
                        if (update.state !is LinkState.Connected) {
                            // Link closed or reconnecting. A new link is a new session: log in again from scratch.
                            session?.cancel()
                            lastHeartbeat = null
                            lastSessionStart = null
                        }
                        if (update.state == LinkState.Disconnected) _state.value = VehicleState()
                        // The heartbeat that makes the vehicle (re)appear may reach the link state before its frame
                        // reaches us, so the gap is checked here too; whichever sees it first starts the session.
                        if (seen != null && vehicle == null) lastHeartbeat = onVehicleHeartbeat(seen, lastHeartbeat)
                        vehicle = seen
                        _state.update { s ->
                            if (seen == null) s.copy(connected = false)
                            else s.copy(
                                connected = true,
                                vehicleKind = seen.kind,
                                armed = seen.armed,
                                flightMode = flightModeName(seen.kind, seen.customMode),
                            )
                        }
                    }
                    is Update.Frame -> {
                        val v = vehicle ?: return@collect
                        // Only the autopilot's own frames: a gimbal or companion computer on the same link has its
                        // own attitude and status messages that must not overwrite the vehicle's.
                        if (update.frame.systemId == v.systemId && update.frame.componentId == v.componentId) {
                            val message = update.frame.message
                            if (message is Heartbeat) lastHeartbeat = onVehicleHeartbeat(v, lastHeartbeat)
                            _state.update { it.reduce(message) }
                        }
                    }
                }
            }
        }
    }

    /** Starts the connect-time sequence on the session's first heartbeat, or after a gap longer than [RELOGIN_GAP]. */
    private fun onVehicleHeartbeat(vehicle: VehicleInfo, last: ComparableTimeMark?): ComparableTimeMark {
        if (last == null || last.elapsedNow() > RELOGIN_GAP) startSession(vehicle)
        return timeSource.markNow()
    }

    private fun startSession(vehicle: VehicleInfo) {
        if (lastSessionStart?.let { it.elapsedNow() < LOGIN_DEBOUNCE } == true) return
        lastSessionStart = timeSource.markNow()
        session?.cancel()
        _state.update { it.copy(login = if (login == null) KftLoginStatus.NO_KEY else KftLoginStatus.LOGGING_IN) }
        // Its own coroutine: the login and the requests wait for replies that arrive on the frames the collector
        // must keep reading.
        session = scope.launch { runSession(vehicle) }
    }

    private suspend fun runSession(vehicle: VehicleInfo) {
        val status = login?.let { loginWithRetries(it, vehicle) } ?: KftLoginStatus.NO_KEY
        setLogin(status)
        if (status.allowsTraffic) requestStartupMessages(vehicle)
    }

    /**
     * FAILED (no challenge, no answer) is retried after [LOGIN_RETRY_DELAYS], then left alone: the next heartbeat gap
     * starts a fresh session. Bounded on purpose (the Android GCS retried every 2 s forever). DENIED is never retried:
     * the same key gives the same answer.
     */
    private suspend fun loginWithRetries(login: KftLogin, vehicle: VehicleInfo): KftLoginStatus {
        for (wait in LOGIN_RETRY_DELAYS) {
            setLogin(KftLoginStatus.LOGGING_IN)
            val status = login.attempt(vehicle)
            if (status != KftLoginStatus.FAILED) return status
            setLogin(KftLoginStatus.FAILED)
            delay(wait)
        }
        setLogin(KftLoginStatus.LOGGING_IN)
        return login.attempt(vehicle)
    }

    private suspend fun setLogin(status: KftLoginStatus) {
        // A cancelled session (link closed, newer session) must not write over the state that replaced it.
        currentCoroutineContext().ensureActive()
        _state.update { it.copy(login = status) }
    }

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
        /**
         * Log in again after this long without a vehicle heartbeat (the Android GCS's rule). ardupilotKFT drops the
         * login after 10 s without *any* heartbeat (`KFT_HEARTBEAT_TIMEOUT_MS`), so 6 s re-logs in early rather than late.
         */
        val RELOGIN_GAP = 6.seconds

        /** At most one session start per 2 s, however many triggers arrive (the Android GCS's debounce). */
        val LOGIN_DEBOUNCE = 2.seconds

        /** Waits between FAILED login attempts: 4 attempts over about 14 s plus the attempts themselves, then stop. */
        val LOGIN_RETRY_DELAYS = listOf(2.seconds, 4.seconds, 8.seconds)

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
