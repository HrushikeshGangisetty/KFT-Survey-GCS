package com.kft.gcs.feature.fly

import androidx.compose.runtime.Immutable
import com.kft.gcs.core.companion.CompanionConfig
import com.kft.gcs.core.companion.CompanionState
import com.kft.gcs.core.companion.Connection
import com.kft.gcs.core.companion.HealthStatus
import com.kft.gcs.core.companion.Source
import com.kft.gcs.core.companion.TrackPoint
import com.kft.gcs.core.companion.VioState
import com.kft.gcs.core.companion.healthAge
import com.kft.gcs.core.companion.mapFixAge
import com.kft.gcs.core.companion.odometryAge
import com.kft.gcs.core.vehicle.FleetVehicle
import com.kft.gcs.ui.design.Status
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.Duration

/**
 * Everything the companion panel draws. Two clearly separate parts: the companion's own data ([vio] … [track]),
 * which is null while [hiddenReason] says why it isn't shown, and what the FC itself reports ([fcEkf], [fcMessages]).
 */
@Immutable
data class CompanionUiState(
    val url: String,
    val systemId: Int,
    /** "Connected", "Connecting…", "Off", "Failed: …" (the GCS's WebSocket, not the vehicle). */
    val connection: Line,
    val on: Boolean,
    /** Set when companion data is not shown, e.g. it is configured for another vehicle. */
    val hiddenReason: String?,
    /** "SYNTHETIC · run synthetic-stale-seed0": warning unless live. */
    val source: Line?,
    /** VIO state; says STALE (critical) when the estimate is older than the timeout, whatever the state. */
    val vio: Line?,
    val rows: List<Line>,
    val health: List<Line>,
    /** Odometry track in metres, odom axes, and which segment (reset_counter) is current. */
    val track: List<TrackPoint>,
    val currentSegment: Int?,
    /** "3 records rejected · last: schema_version 0.1, expected 0.2". */
    val rejected: Line?,
    val fcEkf: Line,
    val fcMessages: List<String>,
)

/** One labelled value with its status colour. */
data class Line(val label: String, val value: String, val status: Status = Status.NEUTRAL)

/**
 * Builds the panel from the companion store, the settings, the selected vehicle and the current time. Pure, so each
 * P4 scenario's look is tested from hand-made records and virtual time.
 * @param now time on the repository's clock (`CompanionRepository.now()`), which the ages are measured against.
 */
fun companionUi(s: CompanionState, config: CompanionConfig, now: Duration, selected: FleetVehicle?): CompanionUiState {
    val hidden = when {
        selected == null -> "No vehicle selected. Companion data is shown with vehicle ${config.systemId} only."
        selected.id.systemId.toInt() != config.systemId ->
            "This companion is set up for vehicle ${config.systemId}; vehicle ${selected.id.systemId} is selected."
        else -> null
    }
    val show = hidden == null
    val odomAge = s.odometryAge(now)
    val odom = s.odometry?.record
    return CompanionUiState(
        url = config.url,
        systemId = config.systemId,
        connection = when (val c = s.connection) {
            Connection.Off -> Line("Link", "Off", Status.NEUTRAL)
            Connection.Connecting -> Line("Link", "Connecting…", Status.WARN)
            Connection.Connected -> Line("Link", "Connected", Status.OK)
            is Connection.Failed -> Line("Link", "Failed: ${c.reason}", Status.WARN)
        },
        on = s.connection != Connection.Off,
        hiddenReason = hidden,
        source = s.source?.takeIf { show }?.let { src ->
            Line("Source", "${src.name} · run ${s.runId}", if (src == Source.LIVE) Status.NEUTRAL else Status.WARN)
        },
        vio = if (!show) null else when {
            odom == null || odomAge == null -> Line("VIO", "No odometry yet", Status.WARN)
            odomAge > config.odometryTimeout -> Line("VIO", "STALE · last said ${odom.state}", Status.CRITICAL)
            else -> Line(
                "VIO", odom.state.name,
                when (odom.state) {
                    VioState.TRACKING -> Status.OK
                    VioState.LOST -> Status.CRITICAL
                    VioState.INITIALIZING, VioState.DEGRADED -> Status.WARN
                },
            )
        },
        rows = if (!show) emptyList() else listOfNotNull(
            Line("Position age", odomAge?.let(::seconds) ?: DASH, if (odomAge == null || odomAge > config.odometryTimeout) Status.CRITICAL else Status.NEUTRAL),
            Line("Resets", odom?.let { "counter ${it.resetCounter} · ${s.resetsSeen} this run" } ?: DASH, if (s.resetsSeen > 0) Status.WARN else Status.NEUTRAL),
            mapFixLine(s, config, now),
        ),
        health = if (!show) emptyList() else s.health.keys.sorted().map { name ->
            val h = s.health.getValue(name).record
            val age = s.healthAge(name, now)
            // No Pi clock yet means the age is unknown, which is not fresh.
            val stale = age == null || age > config.healthTimeout
            Line(
                name,
                listOfNotNull(
                    if (stale) "STALE" else null,
                    h.status.name,
                    h.reason,
                    // Bridge 0.5 only: whether the FC logged us in and whether its position follows ours.
                    h.auth?.let { "auth ${it.name}" },
                    h.fcAcceptance?.let { "FC ${it.name.replace('_', ' ')}" },
                    "cpu ${h.cpuPct.roundToInt()}%",
                    "queue ${h.queueLen}",
                    h.lastInputAgeMs?.let { "input ${it.roundToInt()} ms" },
                    age?.let { "${seconds(it)} old" } ?: "age unknown",
                ).joinToString(" · "),
                when {
                    h.status == HealthStatus.FAILED -> Status.CRITICAL
                    stale || h.status != HealthStatus.OK -> Status.WARN
                    else -> Status.OK
                },
            )
        },
        track = if (show) s.track else emptyList(),
        currentSegment = odom?.resetCounter?.takeIf { show },
        rejected = s.rejected.takeIf { it > 0 && show }?.let { Line("Rejected", "$it records · last: ${s.lastRejection}", Status.WARN) },
        fcEkf = ekfLine(selected?.state?.ekfFlags),
        fcMessages = selected?.state?.estimatorMessages.orEmpty().map { it.text },
    )
}

/**
 * The last map fix, labelled as a map fix (an estimate from map matching, never truth: the `wrong_map` scenario is a
 * confidently wrong one). Rejected fixes show their reason. Age is from the query image's exposure time.
 */
private fun mapFixLine(s: CompanionState, config: CompanionConfig, now: Duration): Line {
    val f = s.mapFix ?: return Line("Map fix", "None yet", Status.NEUTRAL)
    val age = s.mapFixAge(now)
    val stale = age == null || age > config.mapFixTimeout
    val what = if (f.accepted) {
        "Accepted · ${f.latDeg!!.sixDecimals()}, ${f.lonDeg!!.sixDecimals()} · σ ${f.horizontalSigmaM!!.oneDecimal()} m"
    } else {
        "Rejected: ${f.rejectReason}"
    }
    val ageText = age?.let { "${seconds(it)} old" } ?: "age unknown"
    return Line(
        "Map fix",
        "$what · $ageText${if (stale) " · STALE" else ""} · ${s.fixesAccepted} accepted, ${s.fixesRejected} rejected",
        if (stale || !f.accepted) Status.WARN else Status.NEUTRAL,
    )
}

/**
 * The FC's own estimator summary from EKF_STATUS_REPORT.flags (ardupilotmega.xml EKF_STATUS_FLAGS). Warns when the FC
 * has no relative horizontal position, is in constant-position mode, is uninitialised or flags a GPS glitch.
 */
internal fun ekfLine(flags: UInt?): Line {
    if (flags == null) return Line("FC EKF", "Not reported", Status.NEUTRAL)
    fun has(bit: UInt) = flags and bit != 0u
    val good = listOf(1u to "attitude", 2u to "horiz vel", 4u to "vert vel", 8u to "pos rel", 16u to "pos abs", 32u to "alt abs").filter { has(it.first) }.map { it.second }
    val bad = listOf(128u to "CONST POS MODE", 1024u to "UNINITIALISED", 32768u to "GPS GLITCH").filter { has(it.first) }.map { it.second }
    val warn = bad.isNotEmpty() || !has(8u)
    return Line("FC EKF", (bad + good).joinToString(" · ").ifEmpty { "no solution" }, if (warn) Status.WARN else Status.OK)
}

private fun seconds(d: Duration) = "${(d.inWholeMilliseconds / 100.0).roundToInt() / 10.0} s"

private fun Double.oneDecimal(): String = ((this * 10).roundToInt() / 10.0).toString()

/** Six decimals of a degree is about 0.1 m, all a map fix needs. */
private fun Double.sixDecimals(): String = ((this * 1e6).roundToLong() / 1e6).toString()

private const val DASH = "–"
