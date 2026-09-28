/*
 * Test reference only: never part of the app.
 *
 * Ported from QGroundControl, https://github.com/mavlink/qgroundcontrol, master at commit a1b54d85 (2026-09):
 *   src/MissionManager/CameraCalc.cc                  _recalcTriggerDistance
 *   src/MissionManager/SurveyComplexItem.cc           _rebuildTransectsPhase1WorkerSinglePolygon and its helpers
 *                                                      (_rotatePoint, _intersectLinesWithPolygon, _adjustLineDirection,
 *                                                      _adjustTransectsToEntryPointLocation, _clampGridAngle90),
 *                                                      _recalcCameraShots
 *   src/MissionManager/TransectStyleComplexItem.cc    the camera trigger items (_buildAndAppendMissionItems)
 *   src/Utilities/Geo/QGCGeo.cc                        convertGeoToNed / convertNedToGeo (GeographicLib LocalCartesian)
 * Copyright the QGroundControl project. QGroundControl is dual-licensed Apache-2.0 / GPL-3.0 (.github/COPYING.md);
 * this port uses it under the Apache License, Version 2.0 (licenses/Apache-2.0.txt, and see NOTICE).
 *
 * Changes from the original: translated from C++/Qt to Kotlin; Qt types (QPointF, QLineF, QGeoCoordinate) replaced by
 * plain doubles and our LatLon; GeographicLib's LocalCartesian replaced by the same WGS84 ECEF tangent-plane maths
 * written out here; QGeoCoordinate's spherical distance/azimuth by core:geo's Geodesy (same spherical formulas);
 * only the single-polygon path without refly, alternate transects or hover-and-capture; logging removed.
 */
package com.kft.gcs.core.planning

import com.kft.gcs.core.geo.Geodesy
import com.kft.gcs.core.geo.LatLon
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** QGC's four entry locations (SurveyComplexItem::EntryLocation). */
internal enum class QgcEntry { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** One QGC transect in flight order: optional turnaround, survey entry, survey exit, optional turnaround. */
internal data class QgcTransect(val turnIn: LatLon?, val entry: LatLon, val exit: LatLon, val turnOut: LatLon?)

/** CameraCalc::_recalcTriggerDistance with "value set is distance" (altitude typed): GSD cm/px and the two spacings. */
internal data class QgcCameraCalc(val gsdCm: Double, val footprintSideM: Double, val footprintFrontalM: Double, val spacingM: Double, val triggerM: Double)

internal fun qgcCameraCalc(c: Camera, altitudeM: Double, landscape: Boolean, sideOverlapPct: Double, frontOverlapPct: Double): QgcCameraCalc {
    val imageDensity = (altitudeM * c.sensorWidthMm * 100.0) / (c.imageWidthPx * c.focalLengthMm)
    val side = (if (landscape) c.imageWidthPx else c.imageHeightPx) * imageDensity / 100.0
    val frontal = (if (landscape) c.imageHeightPx else c.imageWidthPx) * imageDensity / 100.0
    return QgcCameraCalc(imageDensity, side, frontal, side * ((100.0 - sideOverlapPct) / 100.0), frontal * ((100.0 - frontOverlapPct) / 100.0))
}

/** SurveyComplexItem::_rebuildTransectsPhase1WorkerSinglePolygon(refly = false), no alternate transects. */
internal fun qgcTransects(polygon: List<LatLon>, gridAngleDeg: Double, gridSpacingIn: Double, turnaroundM: Double, entry: QgcEntry): List<QgcTransect> {
    val origin = polygon[0]
    val enu = QgcTangentPlane(origin)
    // QPointF(x = east, y = north); vertex 0 is (0, 0) exactly ("avoids a nan calculation").
    val pts = polygon.mapIndexed { i, v -> if (i == 0) P(0.0, 0.0) else enu.forward(v) }
    val closed = pts + pts[0]

    var gridAngle = gridAngleDeg
    if (gridAngle > 90.0) gridAngle -= 180.0 else if (gridAngle < -90.0) gridAngle += 180.0 // _clampGridAngle90
    var gridSpacing = gridSpacingIn

    val minX = closed.minOf { it.x }; val maxX = closed.maxOf { it.x }
    val minY = closed.minOf { it.y }; val maxY = closed.maxOf { it.y }
    val center = P((minX + maxX) / 2, (minY + maxY) / 2)
    val diagonal = sqrt((maxX - minX) * (maxX - minX) + (maxY - minY) * (maxY - minY))
    val maxWidth = diagonal * 1.5
    require(maxWidth > 0) { "degenerate polygon" }

    val lineList = mutableListOf<L>()
    if (gridSpacing < diagonal / 1000) gridSpacing = diagonal / 1000 // maxTransectCount = 1000
    val halfWidth = maxWidth / 2.0
    var transectX = center.x - halfWidth
    val transectXMax = transectX + maxWidth
    while (transectX < transectXMax) {
        val top = center.y - halfWidth
        val bottom = center.y + halfWidth
        lineList += L(rotate(P(transectX, top), center, gridAngle), rotate(P(transectX, bottom), center, gridAngle))
        transectX += gridSpacing
    }

    var intersectLines = intersectLinesWithPolygon(lineList, closed)
    if (intersectLines.size < 2) {
        val first = lineList.first()
        val mid = P((first.p1.x + first.p2.x) / 2, (first.p1.y + first.p2.y) / 2)
        val dx = center.x - mid.x; val dy = center.y - mid.y
        intersectLines = intersectLinesWithPolygon(listOf(L(P(first.p1.x + dx, first.p1.y + dy), P(first.p2.x + dx, first.p2.y + dy))), closed)
    }

    // _adjustLineDirection: every line the same way as the first, compared by QLineF::angle().
    val firstAngle = intersectLines.firstOrNull()?.qtAngle() ?: 0.0
    val resultLines = intersectLines.map { if (abs(it.qtAngle() - firstAngle) > 1.0) L(it.p2, it.p1) else it }

    var transects = resultLines.map { listOf(enu.reverse(it.p1), enu.reverse(it.p2)) }

    // _adjustTransectsToEntryPointLocation
    if (entry == QgcEntry.BOTTOM_LEFT || entry == QgcEntry.BOTTOM_RIGHT) transects = transects.map { it.reversed() }
    if (entry == QgcEntry.TOP_RIGHT || entry == QgcEntry.BOTTOM_RIGHT) transects = transects.reversed()

    // Lawnmower: reverse every other transect.
    transects = transects.mapIndexed { i, t -> if (i % 2 == 1) t.reversed() else t }

    return transects.map { t ->
        if (turnaroundM > 0) {
            val azIn = Geodesy.initialBearingDeg(t[0], t[1])
            val azOut = Geodesy.initialBearingDeg(t[1], t[0])
            // atDistanceAndAzimuth(-d, az) is d metres the other way.
            QgcTransect(Geodesy.destination(t[0], azIn + 180, turnaroundM), t[0], t[1], Geodesy.destination(t[1], azOut + 180, turnaroundM))
        } else {
            QgcTransect(null, t[0], t[1], null)
        }
    }
}

/** SurveyComplexItem::_recalcCameraShots from transects (images not in turnarounds): Σ ⌈entry→exit / d⌉. */
internal fun qgcCameraShotsEstimate(transects: List<QgcTransect>, triggerM: Double): Int =
    transects.sumOf { ceil(Geodesy.distanceMeters(it.entry, it.exit) / triggerM).toInt() }

/**
 * What ArduPilot actually shoots on a QGC survey line (not a QGC function): QGC switches the distance trigger on at
 * the survey entry with param3 = 1 ("trigger one image immediately") and off at the exit with DO_SET_CAM_TRIGG_DIST
 * (0, …, param3 = 1), which on ArduPilot takes one more photo there (AP_Mission / GCS_Common: trigger == 1 →
 * take_picture). So ⌊L/d⌋ + 1 on the way, plus the exit photo.
 */
internal fun qgcArduPilotPhotos(lengthM: Double, triggerM: Double): Int = floor(lengthM / triggerM + 1e-9).toInt() + 2

// ---- Qt geometry, as much as the port needs ----

internal data class P(val x: Double, val y: Double)
internal data class L(val p1: P, val p2: P) {
    /** QLineF::angle(): degrees counter-clockwise from +x in Qt's y-down convention, normalised to [0, 360). */
    fun qtAngle(): Double {
        val theta = atan2(-(p2.y - p1.y), p2.x - p1.x) * 180 / PI
        val n = if (theta < 0) theta + 360 else theta
        return if (abs(n - 360.0) < 1e-12) 0.0 else n
    }
}

/** SurveyComplexItem::_rotatePoint. */
private fun rotate(p: P, o: P, angle: Double): P {
    val r = (PI / 180.0) * -angle
    return P((p.x - o.x) * cos(r) - (p.y - o.y) * sin(r) + o.x, (p.x - o.x) * sin(r) + (p.y - o.y) * cos(r) + o.y)
}

/** QLineF::intersects(…) == BoundedIntersection, with the point. */
private fun boundedIntersection(a: L, b: L): P? {
    val ax = a.p2.x - a.p1.x; val ay = a.p2.y - a.p1.y
    val bx = b.p1.x - b.p2.x; val by = b.p1.y - b.p2.y
    val denom = ay * bx - ax * by
    if (denom == 0.0 || !denom.isFinite()) return null
    val cx = a.p1.x - b.p1.x; val cy = a.p1.y - b.p1.y
    val na = (by * cx - bx * cy) / denom
    val nb = (ax * cy - ay * cx) / denom
    if (na < 0 || na > 1 || nb < 0 || nb > 1) return null
    return P(a.p1.x + ax * na, a.p1.y + ay * na)
}

/** SurveyComplexItem::_intersectLinesWithPolygon: the two crossings furthest apart (i outer, j inner, strict >). */
private fun intersectLinesWithPolygon(lines: List<L>, closed: List<P>): List<L> = lines.mapNotNull { line ->
    val xs = mutableListOf<P>()
    for (j in 0 until closed.size - 1) {
        val p = boundedIntersection(line, L(closed[j], closed[j + 1])) ?: continue
        if (p !in xs) xs += p
    }
    if (xs.size < 2) return@mapNotNull null
    var first = xs[0]; var second = xs[0]; var max = 0.0
    for (i in xs.indices) for (j in xs.indices) {
        val d = sqrt((xs[j].x - xs[i].x).let { it * it } + (xs[j].y - xs[i].y).let { it * it })
        if (d > max) { first = xs[i]; second = xs[j]; max = d }
    }
    L(first, second)
}

/** GeographicLib::LocalCartesian on WGS84 at height 0, as QGCGeo uses it: forward gives (east, north), up dropped. */
internal class QgcTangentPlane(origin: LatLon) {
    private val a = 6378137.0
    private val f = 1 / 298.257223563
    private val e2 = f * (2 - f)
    private val lat0 = origin.latitude * PI / 180
    private val lon0 = origin.longitude * PI / 180
    private val o = ecef(origin.latitude, origin.longitude)

    private fun ecef(latDeg: Double, lonDeg: Double): DoubleArray {
        val lat = latDeg * PI / 180; val lon = lonDeg * PI / 180
        val n = a / sqrt(1 - e2 * sin(lat) * sin(lat))
        return doubleArrayOf(n * cos(lat) * cos(lon), n * cos(lat) * sin(lon), n * (1 - e2) * sin(lat))
    }

    fun forward(p: LatLon): P {
        val q = ecef(p.latitude, p.longitude)
        val dx = q[0] - o[0]; val dy = q[1] - o[1]; val dz = q[2] - o[2]
        val east = -sin(lon0) * dx + cos(lon0) * dy
        val north = -sin(lat0) * cos(lon0) * dx - sin(lat0) * sin(lon0) * dy + cos(lat0) * dz
        return P(east, north)
    }

    /** convertNedToGeo(north, east, 0): the tangent-plane point (up = 0) back to latitude/longitude. */
    fun reverse(p: P): LatLon {
        val x = o[0] - sin(lon0) * p.x - sin(lat0) * cos(lon0) * p.y
        val y = o[1] + cos(lon0) * p.x - sin(lat0) * sin(lon0) * p.y
        val z = o[2] + cos(lat0) * p.y
        val lon = atan2(y, x)
        val r = sqrt(x * x + y * y)
        var lat = atan2(z, r * (1 - e2))
        repeat(6) {
            val n = a / sqrt(1 - e2 * sin(lat) * sin(lat))
            val h = r / cos(lat) - n
            lat = atan2(z, r * (1 - e2 * n / (n + h)))
        }
        return LatLon(lat * 180 / PI, lon * 180 / PI)
    }
}
