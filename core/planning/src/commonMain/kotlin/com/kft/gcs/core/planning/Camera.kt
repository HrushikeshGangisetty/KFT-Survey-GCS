package com.kft.gcs.core.planning

/**
 * A survey camera, as the planning maths needs it. Sensor size in millimetres, image size in pixels, focal length
 * in millimetres (the real focal length, not the "35 mm equivalent").
 *
 * The maths is the pinhole camera: a sensor of width w at focal length f sees a ground strip of width w·h/f from
 * height h (similar triangles). Every formula here follows from that; see the pass summary for a worked example.
 * Checked against QGC's `CameraCalc.cc` (master, 2026-09), which uses the same relations.
 */
data class Camera(
    val sensorWidthMm: Double,
    val sensorHeightMm: Double,
    val imageWidthPx: Int,
    val imageHeightPx: Int,
    val focalLengthMm: Double,
) {
    init {
        require(sensorWidthMm > 0 && sensorHeightMm > 0 && focalLengthMm > 0) { "sensor size and focal length must be positive" }
        require(imageWidthPx > 0 && imageHeightPx > 0) { "image size must be positive" }
    }
}

/** How the camera sits. LANDSCAPE: the image's long (width) side lies across the flight line. */
enum class CameraOrientation { LANDSCAPE, PORTRAIT }

/** Ground covered by one photo at a given height, across and along the flight line, in metres. */
data class Footprint(val acrossM: Double, val alongM: Double)

/**
 * Ground sample distance: metres of ground per pixel, across the image width, at [altitudeM] above the ground.
 * GSD = h·w / (f·N), with w and f in the same unit (mm cancels) and N the image width in pixels.
 */
fun Camera.gsdM(altitudeM: Double): Double = altitudeM * sensorWidthMm / (focalLengthMm * imageWidthPx)

/** The inverse: the height above ground that gives [gsdM]. h = GSD·f·N / w. */
fun Camera.altitudeForGsdM(gsdM: Double): Double = gsdM * focalLengthMm * imageWidthPx / sensorWidthMm

/**
 * One photo's ground footprint. The side of the sensor that lies across the flight line gives [Footprint.acrossM]:
 * sensor side × h / f. Using the sensor sizes (not GSD × pixels) keeps it right for non-square pixels too.
 */
fun Camera.footprint(altitudeM: Double, orientation: CameraOrientation = CameraOrientation.LANDSCAPE): Footprint {
    val wide = sensorWidthMm * altitudeM / focalLengthMm
    val tall = sensorHeightMm * altitudeM / focalLengthMm
    return if (orientation == CameraOrientation.LANDSCAPE) Footprint(acrossM = wide, alongM = tall) else Footprint(acrossM = tall, alongM = wide)
}

/**
 * Distance between neighbouring flight lines for a side overlap (0.7 = 70 %): each new line moves over by the part
 * of the footprint that must be *new*, footprint × (1 − overlap).
 */
fun lineSpacingM(footprint: Footprint, sideOverlap: Double): Double {
    requireOverlap(sideOverlap)
    return footprint.acrossM * (1 - sideOverlap)
}

/** Distance flown between photos for a front overlap (0.8 = 80 %): footprint along the line × (1 − overlap). */
fun triggerDistanceM(footprint: Footprint, frontOverlap: Double): Double {
    requireOverlap(frontOverlap)
    return footprint.alongM * (1 - frontOverlap)
}

private fun requireOverlap(overlap: Double) =
    require(overlap in 0.0..<1.0) { "overlap must be at least 0 and below 1 (100 % would mean never moving): $overlap" }
