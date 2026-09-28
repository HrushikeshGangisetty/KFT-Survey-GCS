package com.kft.gcs.core.planning

import com.kft.gcs.core.geo.Geodesy
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.geo.LocalPoint
import com.kft.gcs.core.geo.LocalProjection
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Our survey planner next to QGroundControl's algorithm on the same fields (Pass 21). QGC runs as a test-only port,
 * [qgcTransects], so both see identical input. Each assertion states where the two agree, or pins a difference and
 * says whose it is (see the Pass 21 log for the full list):
 * - AGREE: camera maths for square pixels, clipping of convex areas, line spacing, turnaround extension.
 * - OURS, deliberate: lines centred in the area (equal margins); a concave line split into passes; non-square pixels
 *   handled; photos counted as ArduPilot takes them (plus the far-edge photo, [photosOnPass]).
 * - QGC quirks: line margins depend on the bounding box (anything from 0 to one spacing); the entry corner depends on
 *   the order the corners were clicked in; its photo estimate ⌈L/d⌉ is below what its own mission shoots.
 *
 * Everything is measured in one exact frame, the WGS84 tangent plane at the first corner (QGC's own frame), so the
 * comparison isn't blurred by our flat map, whose single earth radius is ≤ 0.5 % off the ellipsoid (measured below).
 */
class QgcParityTest {
    private val home = LatLon(-35.363261, 149.165230) // CMAC, the SITL field: well away from the equator
    private val site = LocalProjection(home)
    private fun at(east: Double, north: Double) = site.toLatLon(LocalPoint(east, north))

    private data class Field(val name: String, val polygon: List<LatLon>, val angle: Double, val concave: Boolean = false)

    private val rectangle = listOf(at(0.0, 0.0), at(300.0, 0.0), at(300.0, 200.0), at(0.0, 200.0))
    private val fields = listOf(
        Field("rectangle 300×200, 0°", rectangle, 0.0),
        Field("rectangle 300×200, 30°", rectangle, 30.0),
        Field("rectangle 300×200, 90°", rectangle, 90.0),
        Field("rotated quad, 0°", listOf(at(-450.0, -230.0), at(-170.0, -250.0), at(-150.0, 170.0), at(-460.0, 180.0)), 0.0),
        Field("triangle, 45°", listOf(at(0.0, 0.0), at(400.0, 50.0), at(150.0, 350.0)), 45.0),
        Field("strip 30 m wide, 0°", listOf(at(0.0, 0.0), at(30.0, 0.0), at(30.0, 400.0), at(0.0, 400.0)), 0.0),
        Field("5×3 km, 17°", listOf(at(0.0, 0.0), at(5000.0, 0.0), at(5000.0, 3000.0), at(0.0, 3000.0)), 17.0),
        Field("concave L, 0°", listOf(at(0.0, 0.0), at(300.0, 0.0), at(300.0, 100.0), at(120.0, 100.0), at(120.0, 300.0), at(0.0, 300.0)), 0.0, concave = true),
    )

    private val spacing = 45.0
    private val trigger = 20.0
    private val turnaround = 15.0
    private val footprintAlong = 100.0 // 80 % front overlap

    /** Both planners' lines, in the exact frame of one field. */
    private inner class Compared(f: Field) {
        val ours = buildSurveyGrid(GridSpec(f.polygon, f.angle, spacing, Turnaround.Copter(turnaround), EntryCorner.BOTTOM_LEFT))
        val qgc = qgcTransects(f.polygon, f.angle, spacing, turnaround, QgcEntry.BOTTOM_LEFT)
        // QGC's own plane (tangent at the first corner), so its straight lines stay exactly straight when measured.
        private val plane = QgcTangentPlane(f.polygon[0])
        private val frame = GridFrame(f.angle)
        fun g(p: LatLon) = plane.forward(p).let { frame.toGrid(LocalPoint(it.x, it.y)) }
        val corners = f.polygon.map(::g)
        val width = corners.maxOf { it.across } - corners.minOf { it.across }
        val qgcAcross = qgc.map { (g(it.entry).across + g(it.exit).across) / 2 }.sorted()
        val ourAcross = ours.passes.map { (g(it.photoStart).across + g(it.photoEnd).across) / 2 }.distinct().sorted()
        fun margins(xs: List<Double>) = (xs.first() - corners.minOf { it.across }) to (corners.maxOf { it.across } - xs.last())
    }

    /** The same lines, cut the same way, extended the same way, wherever the planners' designs don't differ. */
    @Test
    fun convexAreasAgreeOnSpacingClippingAndTurnarounds() {
        for (f in fields.filterNot { it.concave }) {
            val c = Compared(f)
            c.qgcAcross.zipWithNext { a, b -> assertEquals(spacing, b - a, 0.05, "${f.name}: QGC spacing") }
            // Ours is laid out on our flat map; on the ground its spacing is off by that map's scale, ≤ 0.5 %.
            c.ourAcross.zipWithNext { a, b -> assertEquals(spacing, b - a, spacing * 0.005, "${f.name}: our spacing on the ground") }
            // Clipping: at each QGC line, our scan-line clip of the same polygon gives the same single segment.
            c.qgc.forEach { t ->
                val a = (c.g(t.entry).across + c.g(t.exit).across) / 2
                val segs = insideSegments(c.corners, a)
                val q = listOf(c.g(t.entry).along, c.g(t.exit).along).sorted()
                assertEquals(1, segs.size, "${f.name}: convex → one segment")
                assertEquals(q[0], segs[0].first, 0.05, "${f.name}: segment start")
                assertEquals(q[1], segs[0].second, 0.05, "${f.name}: segment end")
            }
            assertTrue(abs(c.ourAcross.size - c.qgcAcross.size) <= 1, "${f.name}: line counts ${c.ourAcross.size} vs ${c.qgcAcross.size}")
            c.qgc.forEach { assertEquals(turnaround, Geodesy.distanceMeters(it.turnIn!!, it.entry), 0.01, "${f.name}: QGC run-in") }
            c.ours.passes.forEach { assertEquals(turnaround, it.runInM, 1e-9, "${f.name}: our run-in") }
        }
    }

    /**
     * DIFFERENCE, ours deliberate: our lines are centred, so both margins are equal and at most half a spacing. QGC
     * starts its sweep at the bounding box's centre − 0.75 × diagonal, so its margins land anywhere in 0…spacing:
     * 44.5 m / 0.7 m on the 30° rectangle. With 70 % side overlap either still covers the edge (half a footprint is
     * 75 m); below 50 % side overlap QGC's layout can leave an edge strip unphotographed, ours can't.
     */
    @Test
    fun oursCentresTheLinesQgcsMarginsDependOnTheBoundingBox() {
        val c = Compared(fields[1])
        val (ol, or) = c.margins(c.ourAcross)
        assertEquals(ol, or, 0.3, "ours: equal margins")
        assertTrue(ol <= spacing / 2 + 0.3)
        val (ql, qr) = c.margins(c.qgcAcross)
        assertTrue(abs(ql - qr) > 30, "QGC: lopsided on this field ($ql / $qr)")
        // Across all fields, our margins never exceed half a spacing; QGC's reach almost a full one.
        val worstQgc = fields.filterNot { it.concave }.maxOf { f -> Compared(f).let { it.margins(it.qgcAcross).let { m -> maxOf(m.first, m.second) } } }
        assertTrue(worstQgc > spacing * 0.9, "worst QGC margin $worstQgc")
    }

    /**
     * DIFFERENCE, ours deliberate: a line that crosses a notch. A U open to the north (arms 100 m wide, a 100 m gap)
     * with east–west lines: over the arms, QGC keeps the two outermost crossings and flies one 300 m line straight
     * over the gap, photos included; ours makes two passes, one per arm, and flies straight on between them without
     * photos.
     */
    @Test
    fun concaveNotchIsSplitByUsSpannedByQgc() {
        val u = listOf(at(0.0, 0.0), at(300.0, 0.0), at(300.0, 300.0), at(200.0, 300.0), at(200.0, 100.0), at(100.0, 100.0), at(100.0, 300.0), at(0.0, 300.0))
        val cu = Compared(Field("U, 90°", u, 90.0, concave = true))
        val crossing = cu.qgc.filter { t -> insideSegments(cu.corners, (cu.g(t.entry).across + cu.g(t.exit).across) / 2).size == 2 }
        assertTrue(crossing.isNotEmpty(), "some E–W lines cross the U's gap")
        crossing.forEach { t ->
            val q = listOf(cu.g(t.entry).along, cu.g(t.exit).along).sorted()
            assertEquals(300.0, q[1] - q[0], 1.0, "QGC flies straight over the 100 m gap (photos included)")
        }
        val ourSplit = cu.ours.passes.groupBy { it.line }.values.count { it.size == 2 }
        assertEquals(crossing.size, ourSplit, "ours: the same lines, each as two passes over the arms")
    }

    /** QGC QUIRK: "bottom left" means bottom or top depending on the order the corners were clicked in. Ours doesn't. */
    @Test
    fun qgcEntryCornerDependsOnVertexOrderOursDoesNot() {
        val fromNorthEast = listOf(rectangle[2], rectangle[3], rectangle[0], rectangle[1])
        val qA = site.toLocal(qgcTransects(rectangle, 0.0, spacing, 0.0, QgcEntry.BOTTOM_LEFT).first().entry)
        val qB = site.toLocal(qgcTransects(fromNorthEast, 0.0, spacing, 0.0, QgcEntry.BOTTOM_LEFT).first().entry)
        assertEquals(200.0, qA.y, 0.1, "clicked from the south-west: QGC's 'bottom left' starts at the top")
        assertEquals(0.0, qB.y, 0.1, "clicked from the north-east: at the bottom")
        val oA = buildSurveyGrid(GridSpec(rectangle, 0.0, spacing, Turnaround.Copter(), EntryCorner.BOTTOM_LEFT)).passes.first().photoStart
        val oB = buildSurveyGrid(GridSpec(fromNorthEast, 0.0, spacing, Turnaround.Copter(), EntryCorner.BOTTOM_LEFT)).passes.first().photoStart
        assertEquals(oA, oB, "ours: the south-west corner either way")
        assertEquals(0.0, site.toLocal(oA).y, 1e-6)
        assertNotEquals(qA, qB)
    }

    /**
     * Photos per line, three ways, on every line of every field: QGC's estimate ⌈L/d⌉ ≤ ours (⌊L/d⌋ + 1, what ArduPilot
     * takes with our items) ≤ what ArduPilot takes with QGC's items (⌊L/d⌋ + 2: its stop item also shoots, param3 = 1).
     * Worked example: L = 200 m, d = 20 m gives QGC 10, ours 11, QGC's mission 12.
     */
    @Test
    fun photoCountsQgcEstimateOursAndQgcsActualMission() {
        assertEquals(10, ceil(200.0 / 20).toInt())
        assertEquals(11, photosOnPass(200.0, 20.0, footprintAlong))
        assertEquals(12, qgcArduPilotPhotos(200.0, 20.0))
        for (f in fields) Compared(f).ours.passes.forEach { p ->
            val n = photosOnPass(p.photoLengthM, trigger, footprintAlong)
            assertTrue(ceil(p.photoLengthM / trigger - 1e-9).toInt() <= n && n <= floor(p.photoLengthM / trigger + 1e-9).toInt() + 2, "${f.name}: $n photos on ${p.photoLengthM} m")
        }
    }

    /**
     * AGREE for square pixels (GSD, spacing, trigger to 1e-9). DIFFERENCE, ours deliberate, for non-square pixels:
     * QGC scales both footprint sides by the width's GSD; ours uses each sensor side. 13.2 × 9.9 mm on 5472 × 3648 px
     * (2.41 × 2.71 µm pixels) at 100 m, 8.8 mm: along-track footprint 9.9 × 100 / 8.8 = 112.5 m (ours) vs
     * 3648 × 2.741 cm = 100.0 m (QGC), so QGC's trigger distance is 11 % short: more photos than the overlap needs.
     */
    @Test
    fun cameraMathsAgreeForSquarePixels() {
        val p4p = Camera(13.2, 8.8, 5472, 3648, 8.8)
        val q = qgcCameraCalc(p4p, 100.0, landscape = true, sideOverlapPct = 70.0, frontOverlapPct = 80.0)
        val fp = p4p.footprint(100.0)
        assertEquals(q.gsdCm, p4p.gsdM(100.0) * 100, 1e-9)
        assertEquals(q.spacingM, lineSpacingM(fp, 0.7), 1e-9)
        assertEquals(q.triggerM, triggerDistanceM(fp, 0.8), 1e-9)
        val portrait = qgcCameraCalc(p4p, 100.0, landscape = false, sideOverlapPct = 70.0, frontOverlapPct = 80.0)
        assertEquals(portrait.spacingM, lineSpacingM(p4p.footprint(100.0, CameraOrientation.PORTRAIT), 0.7), 1e-9)

        val odd = Camera(13.2, 9.9, 5472, 3648, 8.8)
        assertEquals(112.5, odd.footprint(100.0).alongM, 1e-9)
        assertEquals(100.0, qgcCameraCalc(odd, 100.0, true, 70.0, 80.0).footprintFrontalM, 1e-9)
    }
}
