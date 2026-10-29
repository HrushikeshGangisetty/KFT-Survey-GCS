package com.kft.gcs.feature.replay

import com.kft.gcs.core.companion.LocalOdometry
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.geo.LocalPoint
import com.kft.gcs.core.geo.LocalProjection
import com.kft.gcs.core.geoio.DataFlashRecord
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Post-flight analysis of a GNSS-denied flight: how far the vision position drifted from the truth.
 * Pure: values in, values out, so every rule here is tested without a file, a map or a clock. All times are FC seconds
 * since boot (DataFlash TimeUS / 1e6); Pi times are turned into FC times once, in [analyse].
 */

/** Where the truth came from: the GPS, or (in SITL, where the GPS is often disabled) the simulator's own position. */
enum class TruthSource(val label: String) { GPS("GPS"), SITL("SITL truth") }

/** One truth position. [altM] is above home (POS.RelHomeAlt); [speedMS] ground speed; [sats] only from a GPS. */
data class TruthSample(val t: Double, val position: LatLon, val altM: Double?, val speedMS: Double?, val sats: Int?)

/**
 * One vision position as the FC logged it (VISP): [x], [y] in the odom frame (x forward at VIO start, y right, z down;
 * GNSS-Denied contracts/frames.md). [piTimeS] is the Pi time the bridge stamped it with (RTimeUS = measurement_time_pi_ns
 * / 1000, odometry_convert.py), which is what ties the Pi recording to the FC clock. [ignored]: the FC logged it but
 * didn't use it (VISP.Ign).
 */
data class VispSample(val t: Double, val piTimeS: Double, val x: Double, val y: Double, val reset: Int, val ignored: Boolean)

/** One VIO pose from the Pi recording (odometry.jsonl), in the same odom frame, at Pi time [piTimeS]. */
data class OdomSample(val piTimeS: Double, val x: Double?, val y: Double?, val reset: Int, val state: String)

data class ReplayEvent(val t: Double, val text: String)

/** What the FC log holds, as plain values. Built by [fcLogFrom]; tests build it directly. */
data class FcLog(val truth: List<TruthSample>, val truthSource: TruthSource?, val visp: List<VispSample>, val events: List<ReplayEvent>)

/** A vision position placed on the map, with its distance from the truth at the same moment (null: no truth then). */
data class AlignedPoint(val t: Double, val position: LatLon, val errorM: Double?)

data class FlightStats(
    val distanceM: Double,
    /** Distance flown while the FC was receiving vision it used (a non-ignored VISP within 1 s). */
    val visionDistanceM: Double,
    val meanErrorM: Double?,
    val peakErrorM: Double?,
    /** How well the VISP alignment fitted: the RMS distance over its window. Near zero unless VISP is noisy. */
    val anchorErrorM: Double?,
    /** Last VISP error as a percentage of the distance flown since its alignment: drift per distance. */
    val driftPct: Double?,
    /** Anchors committed: one for VISP, one per VIO segment between resets. */
    val alignments: Int,
    /** Changes of VISP's reset counter: the resets the FC was told about. */
    val visionResets: Int,
)

data class Flight(
    val truth: List<TruthSample>,
    val truthSource: TruthSource?,
    /** VISP: what the bridge sent and the FC logged. */
    val published: List<AlignedPoint>,
    /** The Pi's own VIO output (odometry.jsonl), when a recording was added and its clock could be matched. */
    val vio: List<AlignedPoint>,
    val odometry: List<Pair<Double, OdomSample>>,
    val events: List<ReplayEvent>,
    val stats: FlightStats,
    val startS: Double,
    val endS: Double,
    /** Things the operator should know: no truth, a clock that couldn't be matched, segments too short to align. */
    val notes: List<String>,
)

/** Every message [fcLogFrom] reads; the reader skips the rest of the log. */
val FC_LOG_MESSAGES = setOf("GPS", "SIM", "POS", "VISP", "MSG", "EV", "MODE")

/**
 * The values the replay needs from a DataFlash log. GPS rows count only with a 3D fix (Status ≥ 3, ArduPilot
 * GPS_FIX_TYPE); without any, the SITL truth (SIM) is used. ArduPilot logs both instances of a dual GPS as GPS with
 * an instance field I; only the first is used.
 */
fun fcLogFrom(log: Map<String, List<DataFlashRecord>>): FcLog {
    val pos = log["POS"].orEmpty().mapNotNull { r -> r.timeUs?.let { it / 1e6 to r.num("RelHomeAlt") } }
    fun altAt(t: Double) = nearestBefore(pos, t) { it.first }?.second

    val gps = log["GPS"].orEmpty().filter { (it.num("I") ?: 0.0) == 0.0 && (it.num("Status") ?: 0.0) >= 3 }
        .mapNotNull { r ->
            val t = r.timeUs?.div(1e6) ?: return@mapNotNull null
            TruthSample(t, LatLon(r.num("Lat") ?: return@mapNotNull null, r.num("Lng") ?: return@mapNotNull null), altAt(t), r.num("Spd"), r.num("NSats")?.toInt())
        }
    val (truth, source) = if (gps.isNotEmpty()) gps to TruthSource.GPS else {
        val sim = log["SIM"].orEmpty().mapNotNull { r ->
            val t = r.timeUs?.div(1e6) ?: return@mapNotNull null
            TruthSample(t, LatLon(r.num("Lat") ?: return@mapNotNull null, r.num("Lng") ?: return@mapNotNull null), altAt(t), null, null)
        }
        // SIM has no speed: take it from consecutive positions.
        val withSpeed = sim.mapIndexed { i, s ->
            if (i == 0) s else {
                val dt = s.t - sim[i - 1].t
                s.copy(speedMS = if (dt > 0) metres(sim[i - 1].position, s.position) / dt else null)
            }
        }
        withSpeed to TruthSource.SITL.takeIf { sim.isNotEmpty() }
    }

    val visp = log["VISP"].orEmpty().mapNotNull { r ->
        VispSample(
            t = (r.timeUs ?: return@mapNotNull null) / 1e6,
            piTimeS = (r.num("RTimeUS") ?: return@mapNotNull null) / 1e6,
            x = r.num("PX") ?: return@mapNotNull null,
            y = r.num("PY") ?: return@mapNotNull null,
            reset = r.num("Rst")?.toInt() ?: 0,
            ignored = (r.num("Ign") ?: 0.0) != 0.0,
        )
    }

    val events = log["MSG"].orEmpty().mapNotNull { r -> r.timeUs?.let { ReplayEvent(it / 1e6, r.text("Message").orEmpty()) } } +
        log["EV"].orEmpty().mapNotNull { r -> r.timeUs?.let { ReplayEvent(it / 1e6, eventName(r.num("Id")?.toInt())) } } +
        log["MODE"].orEmpty().mapNotNull { r -> r.timeUs?.let { ReplayEvent(it / 1e6, "Mode ${copterMode(r.num("ModeNum")?.toInt())}") } }
    return FcLog(truth, source, visp, events.sortedBy { it.t })
}

/** The Pi recording's odometry.jsonl records, as the samples [analyse] lines up with the FC log. */
fun odomSamples(records: List<LocalOdometry>): List<OdomSample> = records.map {
    OdomSample(it.measurementTimePiNs / 1e9, it.positionM[0], it.positionM[1], it.resetCounter, it.state.name)
}

/**
 * Lines everything up and measures the error.
 *
 * 1. **Pi clock → FC clock.** Each VISP row has both clocks: the FC's TimeUS and the Pi's RTimeUS. The offset is the
 *    median of their differences (median, so a few late packets don't move it). Without VISP there is nothing to tie
 *    the two clocks, and the Pi track is left out with a note rather than guessed.
 * 2. **odom → map.** The odom frame starts at the VIO's start position, with x along the heading it had then, so it
 *    has no latitude or north. Each track is fitted onto the truth with one rotation and one shift (no scale) over its
 *    first [ANCHOR_PATH_M] of flight: the "anchor". Everything after the anchor is placed with that same fit, so the
 *    error that grows afterwards is real drift, not something the fit absorbed. This is the same idea as the GNSS-
 *    Denied handover: commit an origin and heading while the GPS is still good, then fly on vision.
 * 3. **Resets.** A VIO reset starts a new odom frame (reset_counter, frames.md: never difference across a change), so
 *    each reset segment of the Pi odometry gets its own anchor. A segment that never flies [ANCHOR_PATH_M] can't be
 *    anchored (heading is unknown while hovering) and isn't drawn. VISP is not split: ArduPilot re-anchors the vision
 *    frame itself when the counter changes, before logging, so VISP stays continuous. Seen in the P6 `noise30` SITL
 *    log: VISP.Rst changes 2103 times while PX/PY move smoothly.
 */
fun analyse(fc: FcLog, odometry: List<OdomSample>?): Flight {
    val notes = mutableListOf<String>()
    if (fc.truth.isEmpty()) notes += "No GPS fix or SITL truth in this log: tracks can't be placed or measured."
    val origin = fc.truth.firstOrNull()?.position
    val projection = origin?.let(::LocalProjection)
    val truthLocal = fc.truth.map { it.t to projection!!.toLocal(it.position) }
    val truthAt = { t: Double -> interpolate(truthLocal, t) }

    // Reset 0 for every row: VISP is one continuous frame (step 3).
    val publishedFit = align(fc.visp.filter { !it.ignored }.map { Raw(it.t, it.x, it.y, 0) }, truthAt)
    val published = publishedFit.points.map { it.toAligned(projection, truthAt) }
    if (publishedFit.unanchored > 0 && fc.truth.isNotEmpty()) notes += "VISP never covers $ANCHOR_PATH_M m of flight with truth, so it can't be aligned: not drawn."

    var odomTimed = emptyList<Pair<Double, OdomSample>>()
    var vio = emptyList<AlignedPoint>()
    var vioAnchors = emptyList<Double>()
    if (odometry != null) {
        val offsets = fc.visp.map { it.t - it.piTimeS }.sorted()
        if (offsets.isEmpty()) {
            notes += "The FC log has no VISP rows, so the Pi recording's clock can't be matched to it."
        } else {
            val offset = offsets[offsets.size / 2]
            val timed = odometry.map { it.piTimeS + offset to it }
            if (timed.none { (t, _) -> t in (fc.visp.first().t - 5)..(fc.visp.last().t + 5) }) {
                notes += "The Pi recording doesn't overlap this flight (a different run?), so it is left out."
            } else {
                odomTimed = timed
                val vioFit = align(timed.mapNotNull { (t, o) -> if (o.x != null && o.y != null) Raw(t, o.x, o.y, o.reset) else null }, truthAt)
                vio = vioFit.points.map { it.toAligned(projection, truthAt) }
                vioAnchors = vioFit.anchorTimes
                if (vioFit.unanchored > 0) notes += "${vioFit.unanchored} VIO segment(s) between resets too short to align (under $ANCHOR_PATH_M m): not drawn."
            }
        }
    }

    val errors = published.mapNotNull { it.errorM }
    val anchorStart = publishedFit.firstAnchorT
    val lastWithError = published.lastOrNull { it.errorM != null }
    val sinceAnchor = if (anchorStart != null && lastWithError != null) pathLength(fc.truth.filter { it.t in anchorStart..lastWithError.t }) else 0.0
    val usedVisp = fc.visp.filter { !it.ignored }.map { it.t }
    val stats = FlightStats(
        distanceM = pathLength(fc.truth),
        visionDistanceM = pathLength(fc.truth) { _, b -> nearestBefore(usedVisp, b.t) { it }?.let { b.t - it <= 1.0 } == true },
        meanErrorM = errors.takeIf { it.isNotEmpty() }?.average(),
        peakErrorM = errors.maxOrNull(),
        anchorErrorM = publishedFit.firstAnchorRms,
        driftPct = lastWithError?.errorM?.takeIf { sinceAnchor >= 1.0 }?.let { it / sinceAnchor * 100 },
        alignments = publishedFit.anchorTimes.size + vioAnchors.size,
        visionResets = fc.visp.zipWithNext().count { (a, b) -> a.reset != b.reset },
    )

    val vioEvents = odomTimed.zipWithNext().mapNotNull { (a, b) ->
        when {
            b.second.reset != a.second.reset -> ReplayEvent(b.first, "VIO reset (counter ${b.second.reset})")
            b.second.state != a.second.state -> ReplayEvent(b.first, "VIO ${a.second.state} → ${b.second.state}")
            else -> null
        }
    }
    val truthName = fc.truthSource?.label ?: "truth"
    val anchorEvents = publishedFit.anchorTimes.map { ReplayEvent(it, "VISP aligned to $truthName") } +
        vioAnchors.map { ReplayEvent(it, "VIO aligned to $truthName") }
    // Events count too: the FC's boot messages come before the first position, and a flight starts at its first line.
    val times = fc.truth.map { it.t } + fc.visp.map { it.t } + odomTimed.map { it.first } + fc.events.map { it.t }
    return Flight(
        truth = fc.truth, truthSource = fc.truthSource, published = published, vio = vio, odometry = odomTimed,
        events = (fc.events + vioEvents + anchorEvents).sortedBy { it.t },
        stats = stats, startS = times.minOrNull() ?: 0.0, endS = times.maxOrNull() ?: 0.0, notes = notes,
    )
}

/** Truth path flown before an anchor is accepted: enough to fix the heading to about 1° with 0.3 m of noise. */
const val ANCHOR_PATH_M = 30.0

/** How long a truth gap may be and still be bridged by a straight line: GPS runs at 5–10 Hz, SITL truth faster. */
private const val TRUTH_GAP_S = 2.0

private class Raw(val t: Double, val x: Double, val y: Double, val reset: Int)

/** A vision position in map metres (east, north from the projection origin). */
private class Placed(val t: Double, val p: LocalPoint)

private class Fit(val points: List<Placed>, val unanchored: Int, val anchorTimes: List<Double>, val firstAnchorRms: Double?) {
    val firstAnchorT get() = anchorTimes.firstOrNull()
}

private fun Placed.toAligned(projection: LocalProjection?, truthAt: (Double) -> LocalPoint?): AlignedPoint {
    val truth = truthAt(t)
    val error = truth?.let { sqrt((it.x - p.x) * (it.x - p.x) + (it.y - p.y) * (it.y - p.y)) }
    return AlignedPoint(t, projection!!.toLatLon(p), error)
}

/** Splits [raw] at every reset and anchors each segment on its own (see [analyse], steps 2–3). */
private fun align(raw: List<Raw>, truthAt: (Double) -> LocalPoint?): Fit {
    val placed = mutableListOf<Placed>()
    val anchorTimes = mutableListOf<Double>()
    var unanchored = 0
    var firstRms: Double? = null
    segments(raw).forEach { segment ->
        // odom is x forward, y right (z down), like north/east; the map's local frame is east/north. Swapping x and y
        // turns one into the other's handedness, so what is left between them is a pure rotation.
        val pairs = segment.mapNotNull { r -> truthAt(r.t)?.let { Triple(r, LocalPoint(r.y, r.x), it) } }
        var path = 0.0
        val window = pairs.indices.firstOrNull { i ->
            if (i > 0) path += dist(pairs[i - 1].third, pairs[i].third)
            path >= ANCHOR_PATH_M
        }
        if (window == null) { unanchored++; return@forEach }
        val used = pairs.subList(0, window + 1)
        val transform = rigidFit(used.map { it.second }, used.map { it.third })
        anchorTimes += used.first().first.t
        if (firstRms == null) firstRms = sqrt(used.sumOf { (_, src, dst) -> dist(transform(src), dst).let { it * it } } / used.size)
        segment.forEach { placed += Placed(it.t, transform(LocalPoint(it.y, it.x))) }
    }
    return Fit(placed, unanchored, anchorTimes, firstRms)
}

private fun segments(raw: List<Raw>): List<List<Raw>> {
    val out = mutableListOf<MutableList<Raw>>()
    raw.forEach { r -> if (out.isEmpty() || out.last().last().reset != r.reset) out += mutableListOf(r) else out.last() += r }
    return out
}

/**
 * The rotation and shift that best map [src] onto [dst] (least squares, no scale): the 2D case of the Kabsch /
 * Umeyama method (S. Umeyama, "Least-squares estimation of transformation parameters between two point patterns",
 * IEEE PAMI 13(4), 1991). In 2D the best angle has a closed form: θ = atan2(Σ a×b, Σ a·b) over the centred pairs.
 */
internal fun rigidFit(src: List<LocalPoint>, dst: List<LocalPoint>): (LocalPoint) -> LocalPoint {
    val cs = LocalPoint(src.sumOf { it.x } / src.size, src.sumOf { it.y } / src.size)
    val cd = LocalPoint(dst.sumOf { it.x } / dst.size, dst.sumOf { it.y } / dst.size)
    var dot = 0.0
    var cross = 0.0
    src.indices.forEach { i ->
        val ax = src[i].x - cs.x; val ay = src[i].y - cs.y
        val bx = dst[i].x - cd.x; val by = dst[i].y - cd.y
        dot += ax * bx + ay * by
        cross += ax * by - ay * bx
    }
    val theta = atan2(cross, dot)
    val c = cos(theta); val s = sin(theta)
    return { p ->
        val x = p.x - cs.x; val y = p.y - cs.y
        LocalPoint(cd.x + c * x - s * y, cd.y + s * x + c * y)
    }
}

/** Truth at [t], straight-line between the samples either side, or null outside the log or across a gap. */
private fun interpolate(samples: List<Pair<Double, LocalPoint>>, t: Double): LocalPoint? {
    val i = nearestBeforeIndex(samples, t) { it.first } ?: return null
    val (t0, p0) = samples[i]
    if (t0 == t) return p0
    val (t1, p1) = samples.getOrNull(i + 1) ?: return null
    if (t1 - t0 > TRUTH_GAP_S) return null
    val f = (t - t0) / (t1 - t0)
    return LocalPoint(p0.x + f * (p1.x - p0.x), p0.y + f * (p1.y - p0.y))
}

private fun pathLength(truth: List<TruthSample>, counts: (TruthSample, TruthSample) -> Boolean = { _, _ -> true }) =
    truth.zipWithNext().sumOf { (a, b) -> if (b.t - a.t <= TRUTH_GAP_S && counts(a, b)) metres(a.position, b.position) else 0.0 }

/**
 * Distance on the WGS84 ellipsoid's local plane at [a] ([LocalProjection]), not the sphere of `Geodesy`: the errors
 * are measured on that plane, so distances and errors must share it or drift % is off by up to 0.3 %.
 */
private fun metres(a: LatLon, b: LatLon) = LocalProjection(a).toLocal(b).let { sqrt(it.x * it.x + it.y * it.y) }

private fun dist(a: LocalPoint, b: LocalPoint) = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

/** Index of the last element with time ≤ [t] in a list sorted by time (binary search), or null if all are later. */
internal fun <T> nearestBeforeIndex(list: List<T>, t: Double, time: (T) -> Double): Int? {
    var lo = 0
    var hi = list.size - 1
    var found: Int? = null
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (time(list[mid]) <= t) { found = mid; lo = mid + 1 } else hi = mid - 1
    }
    return found
}

internal fun <T> nearestBefore(list: List<T>, t: Double, time: (T) -> Double): T? = nearestBeforeIndex(list, t, time)?.let { list[it] }

/** Names for the EV ids a replay cares about (ArduCopter `LogEvent`, AP_Logger.h); the rest show their number. */
private fun eventName(id: Int?) = when (id) {
    10 -> "Armed"
    11 -> "Disarmed"
    15 -> "Auto-armed"
    17 -> "Land complete (maybe)"
    18 -> "Land complete"
    28 -> "Not landed"
    else -> "Event $id"
}

/** ArduCopter flight-mode numbers (`Mode::Number`, mode.h) for the modes a survey flight uses. */
private fun copterMode(n: Int?) = when (n) {
    0 -> "Stabilize"; 2 -> "AltHold"; 3 -> "Auto"; 4 -> "Guided"; 5 -> "Loiter"; 6 -> "RTL"; 9 -> "Land"; 16 -> "PosHold"
    else -> "$n"
}
