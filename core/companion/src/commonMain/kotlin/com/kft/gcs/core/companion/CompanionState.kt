package com.kft.gcs.core.companion

import kotlin.math.hypot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.Serializable

/**
 * What the operator configures for the companion link. Saved like the other profiles.
 * @property systemId the MAVLink system id of the vehicle this companion rides on. Its data is shown only while that
 *   vehicle is the selected one (P7 "vehicle association"), so one drone's Pi is never shown under another drone.
 * @property odometryTimeout, mapFixTimeout, healthTimeout how old each may get before the panel says STALE. Odometry
 *   comes at 20 Hz, health at 2 Hz and map fixes at 0.5 Hz (visionnav P4 rates), so each is a few periods.
 */
@Serializable
data class CompanionConfig(
    val url: String = "ws://127.0.0.1:8765",
    val systemId: Int = 1,
    val odometryTimeout: Duration = 500.milliseconds,
    val mapFixTimeout: Duration = 5.seconds,
    val healthTimeout: Duration = 1500.milliseconds,
)

/** The WebSocket client's own state. Nothing here is the vehicle's. */
sealed interface Connection {
    data object Off : Connection
    data object Connecting : Connection
    data object Connected : Connection
    /** Dropped or refused; the client retries by itself while the operator leaves it on. */
    data class Failed(val reason: String) : Connection
}

/** A record and when the GCS received it, as time since the store's start (see [CompanionState.reduce]). */
data class Received<T>(val record: T, val at: Duration)

/** One odometry position for the local plot, in metres in odom axes. [segment] is the reset_counter it belongs to. */
data class TrackPoint(val x: Double, val y: Double, val segment: Int)

/**
 * The newest Pi clock reading the GCS has: the largest `publish_time_pi_ns` seen and when it was received. The Pi's
 * "now" is estimated as that plus the GCS time since, so ages keep growing when the Pi stops sending.
 */
data class PiClock(val publishNs: Long, val at: Duration)

/**
 * Everything the GCS knows about the companion, separate from [com.kft.gcs.core.vehicle.VehicleState] on purpose.
 * One run at a time: the first record fixes [runId] and [source]; a record from another run or source starts over.
 */
data class CompanionState(
    val connection: Connection = Connection.Off,
    val runId: String? = null,
    val source: Source? = null,
    val odometry: Received<LocalOdometry>? = null,
    val piClock: PiClock? = null,
    /** Latest health per component, by name. */
    val health: Map<String, Received<Health>> = emptyMap(),
    val mapFix: MapFix? = null,
    val fixesAccepted: Int = 0,
    val fixesRejected: Int = 0,
    /** reset_counter changes seen in this run (the counter itself is in [odometry]). */
    val resetsSeen: Int = 0,
    val track: List<TrackPoint> = emptyList(),
    /** Records refused (bad JSON, wrong version, broken rule, out of order); none of them changed anything else. */
    val rejected: Int = 0,
    val lastRejection: String? = null,
    /** How many times a new run_id or source replaced the old one. */
    val runChanges: Int = 0,
)

/** Applies one parsed WebSocket message received at [now]. Pure: tests pass hand-made records and times. */
fun CompanionState.reduce(parsed: Parsed, now: Duration): CompanionState = when (parsed) {
    is Parsed.Rejected -> reject(parsed.reason)
    is Parsed.Ok -> parsed.record.let { r ->
        // A different run (or a different source in the same run_id) is another session: nothing carries over,
        // because its clock, odom origin and reset counter belong to that run.
        val s = if (runId == null || (r.runId == runId && r.source == source)) this
            else CompanionState(connection, runChanges = runChanges + 1)
        s.copy(runId = r.runId, source = r.source).apply(r, now)
    }
}

private fun CompanionState.apply(r: CompanionRecord, now: Duration): CompanionState = when (r) {
    is LocalOdometry -> odometry(r, now)
    is Health -> health(r, now)
    is MapFix -> copy(
        mapFix = r,
        fixesAccepted = fixesAccepted + if (r.accepted) 1 else 0,
        fixesRejected = fixesRejected + if (r.accepted) 0 else 1,
    )
}

private fun CompanionState.odometry(r: LocalOdometry, now: Duration): CompanionState {
    val last = odometry?.record
    // A repeated or older publish time is a duplicate, reordering or a Pi clock restart. Refusing it keeps the
    // Pi clock estimate moving forward only; after a Pi reboot ages grow until the panel says STALE, which is right.
    if (piClock != null && r.publishTimePiNs <= piClock.publishNs) return reject("odometry publish time did not advance")
    // Older measurement in the same odom segment: arrived out of order. A frozen (equal) one is accepted: that is the
    // stale case, and its age shows it.
    if (last != null && r.resetCounter == last.resetCounter && r.measurementTimePiNs < last.measurementTimePiNs) {
        return reject("odometry measurement time went backwards")
    }
    val reset = last != null && r.resetCounter != last.resetCounter
    val tracking = r.state == VioState.TRACKING || r.state == VioState.DEGRADED
    return copy(
        odometry = Received(r, now),
        piClock = PiClock(r.publishTimePiNs, now),
        resetsSeen = resetsSeen + if (reset) 1 else 0,
        track = if (tracking) track.appendPoint(TrackPoint(r.positionM[0]!!, r.positionM[1]!!, r.resetCounter)) else track,
    )
}

private fun CompanionState.health(r: Health, now: Duration): CompanionState {
    // Older evaluation time for the same component: reordered or replayed. A frozen (equal) one is accepted so its age
    // shows; refusing only the strictly older keeps the newest status from being overwritten by a late one.
    val last = health[r.component]?.record
    if (last != null && r.measurementTimePiNs < last.measurementTimePiNs) return reject("health measurement time went backwards")
    return copy(health = health + (r.component to Received(r, now)))
}

private fun CompanionState.reject(reason: String) = copy(rejected = rejected + 1, lastRejection = reason)

/**
 * Adds [p] when it is at least [minSpacingM] from the last point of its segment, keeping at most [maxPoints].
 * Spacing keeps a hover from piling up 20 points a second; a new segment always starts with its first point.
 */
internal fun List<TrackPoint>.appendPoint(p: TrackPoint, minSpacingM: Double = 0.02, maxPoints: Int = 2_000): List<TrackPoint> {
    val last = lastOrNull()
    if (last != null && last.segment == p.segment && hypot(p.x - last.x, p.y - last.y) < minSpacingM) return this
    return (this + p).takeLast(maxPoints)
}

/**
 * The Pi's clock now, estimated from the newest publish time plus the GCS time since it arrived. Null before the
 * first odometry. It ignores the one-way network delay, so ages read low by that much (milliseconds on a LAN).
 */
fun CompanionState.piNowNs(now: Duration): Long? = piClock?.let { it.publishNs + (now - it.at).inWholeNanoseconds }

/**
 * How old the odometry estimate is: Pi now minus its measurement_time (P7: from measurement time, not arrival).
 * Keeps growing when measurement_time freezes while messages keep coming (the `stale` scenario) and when nothing comes.
 */
fun CompanionState.odometryAge(now: Duration): Duration? =
    odometry?.let { o -> piNowNs(now)?.let { (it - o.record.measurementTimePiNs).nanoseconds } }

/** How old the last map fix's image is, on the Pi clock. Null if there is no fix or no Pi clock yet (unknown, not fresh). */
fun CompanionState.mapFixAge(now: Duration): Duration? =
    mapFix?.let { f -> piNowNs(now)?.let { (it - f.queryExposureTimePiNs).nanoseconds } }

/**
 * How old [component]'s health status is: Pi now minus its measurement_time (WE-7: not arrival). Null if there is no
 * such health yet or no Pi clock yet (no odometry has arrived); unknown, not fresh. Keeps growing when the Pi freezes
 * the time while messages keep coming.
 */
fun CompanionState.healthAge(component: String, now: Duration): Duration? =
    health[component]?.let { h -> piNowNs(now)?.let { (it - h.record.measurementTimePiNs).nanoseconds } }
