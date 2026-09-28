package com.kft.gcs.core.planning

import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.geo.LocalProjection
import kotlin.math.ceil
import kotlin.math.max

/**
 * How the survey height is chosen. Either one fixes the other through the camera ([gsdM] / [altitudeForGsdM]):
 * - [Altitude]: "fly at 100 m", and the GSD follows.
 * - [Gsd]: "I need 2 cm per pixel", and the altitude follows. This is how a mapping customer usually asks.
 */
sealed interface SurveyHeight {
    data class Altitude(val metres: Double) : SurveyHeight
    data class Gsd(val metresPerPixel: Double) : SurveyHeight
}

/**
 * Everything the operator chooses for one survey. Overlaps are fractions (0.7 = 70 %).
 * @property polygon the area's corners; for a [corridor], its centre line instead.
 * @property crosshatch fly the area a second time with the lines at 90° to the first (area surveys only).
 * @property corridor a corridor scan along [polygon] instead of an area survey. [sideOverlap], [gridAngleDeg] and
 *   [entry] don't apply to it: its lines come from its shape ([corridorOffsets]).
 */
data class SurveyParams(
    val polygon: List<LatLon>,
    val camera: Camera,
    val height: SurveyHeight,
    val orientation: CameraOrientation = CameraOrientation.LANDSCAPE,
    val sideOverlap: Double = 0.7,
    val frontOverlap: Double = 0.8,
    val gridAngleDeg: Double = 0.0,
    val entry: EntryCorner = EntryCorner.BOTTOM_LEFT,
    val speedMs: Double,
    val turnaround: Turnaround,
    val crosshatch: Crosshatch? = null,
    val corridor: CorridorShape? = null,
)

/**
 * The second, crossing pass of a crosshatch survey, [altitudeOffsetM] above (or, negative, below) the first. Seen
 * from two directions (and optionally two heights), walls, trees and structures reconstruct better in 3D than from
 * one set of parallel lines. Its spacing and trigger distance are worked out for its own altitude, so both passes
 * keep the chosen overlaps.
 */
data class Crosshatch(val altitudeOffsetM: Double = 0.0)

/**
 * Operator settings that only raise warnings or add estimates.
 * @property usableFlightTimeS flight time one battery gives, with the reserve already taken off; null = unknown.
 * @property maxGsdM warn when the survey's GSD is coarser than this; null = no limit.
 */
data class SurveyLimits(val usableFlightTimeS: Double? = null, val maxGsdM: Double? = null)

/** Something the operator should look at before flying. Not a block: the plan is still valid. */
sealed interface SurveyWarning {
    /**
     * Photos would be due every [intervalS] seconds, but the camera needs at least [minIntervalS]. ArduPilot holds each
     * early photo back until the interval has passed, so they end up further apart than the overlap needs. [maxSpeedMs] is the fastest speed that works with these overlaps.
     */
    data class PhotoIntervalTooShort(val intervalS: Double, val minIntervalS: Double, val maxSpeedMs: Double) : SurveyWarning

    /** The GSD is coarser than the operator's limit. */
    data class GsdAboveLimit(val gsdM: Double, val limitM: Double) : SurveyWarning

    /** A plane still has [count] turns between lines closer than twice its turn radius (see [SurveyGrid.loopTurns]). */
    data class PlaneLoopTurns(val count: Int) : SurveyWarning

    /**
     * A corridor's lines are so far apart for this camera and altitude that neighbouring photos overlap sideways
     * by only [overlap] (a fraction; negative is a gap), under [MIN_CORRIDOR_SIDE_OVERLAP]. More lines fix it.
     */
    data class CorridorSideOverlapLow(val overlap: Double) : SurveyWarning
}

/**
 * The least side overlap a corridor should have before it's flagged. 60 %: the low end of what photogrammetry
 * software asks for in easy terrain (Pix4D's guidance is 60 % side overlap as a minimum, more over forest or water).
 */
const val MIN_CORRIDOR_SIDE_OVERLAP = 0.6

/** One set of lines at one altitude. A survey has one; a crosshatch has two ([SurveyPlan.crosshatch]). */
data class GridLeg(
    val altitudeM: Double,
    val gsdM: Double,
    val footprint: Footprint,
    val lineSpacingM: Double,
    val triggerDistanceM: Double,
    val grid: SurveyGrid,
)

/**
 * A survey worked out from [SurveyParams]: the numbers the panel shows and the grid the mission is built from.
 * The top-level altitude, spacing, trigger and grid are the first (or only) set of lines; [crosshatch] is the second.
 * [stats] covers both, including the hop between them. [legs] lists them in flight order.
 */
data class SurveyPlan(
    val altitudeM: Double,
    val gsdM: Double,
    val footprint: Footprint,
    val lineSpacingM: Double,
    val triggerDistanceM: Double,
    val grid: SurveyGrid,
    val stats: SurveyStats,
    /** ⌈flight time / usable time per battery⌉, or null when the battery time isn't set. */
    val batteries: Int?,
    /** Photos × MB per photo; 0 when the camera's photo size isn't known. */
    val dataMb: Double,
    val warnings: List<SurveyWarning>,
    val crosshatch: GridLeg? = null,
) {
    val legs: List<GridLeg> get() = listOfNotNull(GridLeg(altitudeM, gsdM, footprint, lineSpacingM, triggerDistanceM, grid), crosshatch)
}

/**
 * Works out a survey: height ↔ GSD, footprint, spacing, trigger distance, grid (or corridor lines, and a crosshatch's
 * second grid), stats, batteries, data and warnings. Pure, like everything in this module. Throws
 * [IllegalArgumentException] for inputs [buildSurveyGrid], [buildCorridorGrid] or the camera formulas reject (too
 * few corners, overlap of 100 %, zero speed…), so the caller can show the message.
 */
fun planSurvey(params: SurveyParams, limits: SurveyLimits = SurveyLimits()): SurveyPlan {
    require(params.speedMs > 0) { "speed must be positive" }
    val camera = params.camera
    val altitude = when (val h = params.height) {
        is SurveyHeight.Altitude -> h.metres
        is SurveyHeight.Gsd -> camera.altitudeForGsdM(h.metresPerPixel)
    }
    val first = planLeg(params, altitude, params.gridAngleDeg, params.entry)
    // The crossing pass starts at whichever corner is nearest the first pass's end: the shortest hop between them.
    val second = params.crosshatch?.takeIf { params.corridor == null }?.let { c ->
        val end = first.grid.passes.last().exit
        EntryCorner.entries.map { planLeg(params, altitude + c.altitudeOffsetM, params.gridAngleDeg + 90, it) }
            .minBy { distanceM(end, it.grid.passes.first().entry) }
    }
    val legs = listOfNotNull(first, second)

    val area = params.corridor?.let { corridorAreaM2(params.polygon, it) } ?: polygonAreaM2(params.polygon)
    // ponytail: the hop between the two passes is a straight line at survey speed, for Plane too (its first lead-in
    // of 4 turn radii already lets it settle). Model the turn if plane crosshatch times come out short in SITL.
    val hopM = second?.let { distanceM(first.grid.passes.last().exit, it.grid.passes.first().entry) } ?: 0.0
    val distance = legs.sumOf { it.grid.distanceM } + hopM
    val stats = SurveyStats(
        areaM2 = area,
        lineCount = legs.sumOf { it.grid.lineCount },
        photoCount = legs.sumOf { leg -> leg.grid.passes.sumOf { photosOnPass(it.photoLengthM, leg.triggerDistanceM, leg.footprint.alongM) } },
        distanceM = distance,
        // ponytail: constant speed, no slow-down in copter turns or wind (as surveyStats).
        flightTimeS = distance / params.speedMs,
    )

    val warnings = buildList {
        // 1e-9: exactly at the camera's limit is fine. 10 m / 5 m/s comes out as 1.999… s through the camera maths,
        // and warning "2.0 s apart, but the camera needs 2.0 s" is noise (the first SITL run showed it).
        legs.minBy { it.triggerDistanceM }.let { leg ->
            val interval = leg.triggerDistanceM / params.speedMs
            if (interval < camera.minTriggerIntervalS - 1e-9) {
                add(SurveyWarning.PhotoIntervalTooShort(interval, camera.minTriggerIntervalS, leg.triggerDistanceM / camera.minTriggerIntervalS))
            }
        }
        limits.maxGsdM?.let { max -> legs.maxOf { it.gsdM }.let { if (it > max) add(SurveyWarning.GsdAboveLimit(it, max)) } }
        legs.sumOf { it.grid.loopTurns }.let { if (it > 0) add(SurveyWarning.PlaneLoopTurns(it)) }
        if (params.corridor != null) {
            val overlap = 1 - first.lineSpacingM / first.footprint.acrossM
            if (overlap < MIN_CORRIDOR_SIDE_OVERLAP - 1e-9) add(SurveyWarning.CorridorSideOverlapLow(overlap))
        }
    }
    return SurveyPlan(
        altitudeM = first.altitudeM,
        gsdM = first.gsdM,
        footprint = first.footprint,
        lineSpacingM = first.lineSpacingM,
        triggerDistanceM = first.triggerDistanceM,
        grid = first.grid,
        stats = stats,
        // 1e-6: a flight of exactly 2 batteries' time needs 2, not 3. The distance comes through the map projection, so
        // it carries float noise of a few micrometres; a millionth of a battery is far above that and far below anything real.
        batteries = limits.usableFlightTimeS?.takeIf { it > 0 }?.let { ceil(stats.flightTimeS / it - 1e-6).toInt() },
        dataMb = stats.photoCount * camera.mbPerPhoto,
        warnings = warnings,
        crosshatch = second,
    )
}

/** One set of lines at [altitude]: footprint, spacing, trigger, and the area grid or the corridor lines. */
private fun planLeg(params: SurveyParams, altitude: Double, gridAngleDeg: Double, entry: EntryCorner): GridLeg {
    require(altitude > 0) { "altitude must be positive" }
    val camera = params.camera
    val footprint = camera.footprint(altitude, params.orientation)
    val trigger = triggerDistanceM(footprint, params.frontOverlap)
    // The camera switches off d/2 after each pass's last photo ([cameraOff]), so the run-out must reach that far. With
    // front overlap under 50 % the last photo can be up to d − footprint/2 past the far edge ([photosOnPass]); then
    // the run-out must reach 1.5·d − footprint/2. At 50 % and above that is ≤ d/2, so d/2 it stays.
    val minRunOut = max(trigger / 2, 1.5 * trigger - footprint.alongM / 2)
    val turnaround = when (val t = params.turnaround) {
        is Turnaround.Copter -> t.copy(extensionM = max(t.extensionM, minRunOut))
        is Turnaround.Plane -> t.copy(leadOutM = max(t.leadOutM, minRunOut))
    }
    val corridor = params.corridor
    val (spacing, grid) = if (corridor != null) {
        corridorOffsets(corridor).second to buildCorridorGrid(params.polygon, corridor, turnaround)
    } else {
        val spacing = lineSpacingM(footprint, params.sideOverlap)
        spacing to buildSurveyGrid(GridSpec(params.polygon, gridAngleDeg, spacing, turnaround, entry))
    }
    return GridLeg(altitude, camera.gsdM(altitude), footprint, spacing, trigger, grid)
}

/** Straight-line distance, on the flat local map (as everything in this module). */
private fun distanceM(a: LatLon, b: LatLon): Double {
    val p = LocalProjection(a).toLocal(b)
    return kotlin.math.hypot(p.x, p.y)
}
