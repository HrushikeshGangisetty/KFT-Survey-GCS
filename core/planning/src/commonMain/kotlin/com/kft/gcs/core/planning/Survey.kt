package com.kft.gcs.core.planning

import com.kft.gcs.core.geo.LatLon
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

/** Everything the operator chooses for one area survey. Overlaps are fractions (0.7 = 70 %). */
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
)

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
}

/** A survey worked out from [SurveyParams]: the numbers the panel shows and the grid the mission is built from. */
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
)

/**
 * Works out a survey: height ↔ GSD, footprint, spacing, trigger distance, grid, stats, batteries, data and warnings.
 * Pure, like everything in this module. Throws [IllegalArgumentException] for inputs [buildSurveyGrid] or the camera
 * formulas reject (too few corners, overlap of 100 %, zero speed…), so the caller can show the message.
 */
fun planSurvey(params: SurveyParams, limits: SurveyLimits = SurveyLimits()): SurveyPlan {
    require(params.speedMs > 0) { "speed must be positive" }
    val camera = params.camera
    val altitude = when (val h = params.height) {
        is SurveyHeight.Altitude -> h.metres
        is SurveyHeight.Gsd -> camera.altitudeForGsdM(h.metresPerPixel)
    }
    require(altitude > 0) { "altitude must be positive" }
    val gsd = camera.gsdM(altitude)
    val footprint = camera.footprint(altitude, params.orientation)
    val spacing = lineSpacingM(footprint, params.sideOverlap)
    val trigger = triggerDistanceM(footprint, params.frontOverlap)
    // The camera switches off d/2 after each pass's last photo ([cameraOff]), so the run-out must reach that far.
    val turnaround = when (val t = params.turnaround) {
        is Turnaround.Copter -> t.copy(extensionM = max(t.extensionM, trigger / 2))
        is Turnaround.Plane -> t.copy(leadOutM = max(t.leadOutM, trigger / 2))
    }
    val spec = GridSpec(params.polygon, params.gridAngleDeg, spacing, turnaround, params.entry)
    val grid = buildSurveyGrid(spec)
    val stats = surveyStats(spec, grid, trigger, params.speedMs)

    val warnings = buildList {
        val interval = trigger / params.speedMs
        // 1e-9: exactly at the camera's limit is fine. 10 m / 5 m/s comes out as 1.999… s through the camera maths,
        // and warning "2.0 s apart, but the camera needs 2.0 s" is noise (the first SITL run showed it).
        if (interval < camera.minTriggerIntervalS - 1e-9) {
            add(SurveyWarning.PhotoIntervalTooShort(interval, camera.minTriggerIntervalS, trigger / camera.minTriggerIntervalS))
        }
        limits.maxGsdM?.let { if (gsd > it) add(SurveyWarning.GsdAboveLimit(gsd, it)) }
        if (grid.loopTurns > 0) add(SurveyWarning.PlaneLoopTurns(grid.loopTurns))
    }
    return SurveyPlan(
        altitudeM = altitude,
        gsdM = gsd,
        footprint = footprint,
        lineSpacingM = spacing,
        triggerDistanceM = trigger,
        grid = grid,
        stats = stats,
        // 1e-6: a flight of exactly 2 batteries' time needs 2, not 3. The distance comes through the map projection, so
        // it carries float noise of a few micrometres; a millionth of a battery is far above that and far below anything real.
        batteries = limits.usableFlightTimeS?.takeIf { it > 0 }?.let { ceil(stats.flightTimeS / it - 1e-6).toInt() },
        dataMb = stats.photoCount * camera.mbPerPhoto,
        warnings = warnings,
    )
}
