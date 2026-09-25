package com.kft.gcs.core.planning

import com.kft.gcs.core.geo.LatLon
import kotlin.math.cos
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Most shapes sit on the equator, so a position converts to metres by hand with one constant:
 * 1° = 6 371 008.8 m × π / 180 = 111 195.08 m, the same on both axes. `m(x, y)` = x metres east, y north of (0, 0).
 */
class SurveyGridTest {
    private val metresPerDegree = 111_195.08

    private fun m(x: Double, y: Double) = LatLon(y / metresPerDegree, x / metresPerDegree)
    private fun LatLon.xy() = Pair(longitude * metresPerDegree, latitude * metresPerDegree)
    private fun assertAt(x: Double, y: Double, p: LatLon, what: String) {
        val (px, py) = p.xy()
        assertEquals(x, px, 1e-3, "$what x")
        assertEquals(y, py, 1e-3, "$what y")
    }

    /** 300 m east–west by 200 m north–south, south-west corner at (0, 0). */
    private val rectangle = listOf(m(0.0, 0.0), m(300.0, 0.0), m(300.0, 200.0), m(0.0, 200.0))
    private val copter = Turnaround.Copter()

    private fun grid(polygon: List<LatLon>, angle: Double, spacing: Double, turn: Turnaround = copter, entry: EntryCorner = EntryCorner.BOTTOM_LEFT) =
        GridSpec(polygon, angle, spacing, turn, entry).let { it to buildSurveyGrid(it) }

    /**
     * THE worked example (pass summary). Grid angle 0 = north–south lines, spacing 45 m (P4P at 100 m, 70 % side).
     * Width across the lines = 300 m → n = ⌈300 / 45⌉ = ⌈6.67⌉ = 7 lines. They span 6 × 45 = 270 m, leaving
     * (300 − 270) / 2 = 15 m each side: lines at x = 15, 60, 105, 150, 195, 240, 285 m, each 200 m long.
     * Stats with a 20 m trigger at 10 m/s: photos 7 × (⌊200/20⌋ + 1) = 7 × 11 = 77; distance 7 × 200 + 6 × 45
     * = 1670 m; time 167 s; area 300 × 200 = 60 000 m².
     */
    @Test
    fun rectangleWithNorthSouthLines() {
        val (spec, g) = grid(rectangle, angle = 0.0, spacing = 45.0)
        assertEquals(7, g.lineCount)
        assertEquals(7, g.passes.size)
        g.passes.forEachIndexed { i, p ->
            val x = 15.0 + 45.0 * i
            val northbound = i % 2 == 0 // BOTTOM_LEFT: the first line goes up (north), then they alternate
            assertAt(x, if (northbound) 0.0 else 200.0, p.photoStart, "line $i start")
            assertAt(x, if (northbound) 200.0 else 0.0, p.photoEnd, "line $i end")
            assertEquals(200.0, p.photoLengthM, 1e-3)
        }
        g.connectorsM.forEach { assertEquals(45.0, it, 1e-3, "copter hop to the next line") }

        val stats = surveyStats(spec, g, triggerDistanceM = 20.0, speedMs = 10.0)
        assertEquals(60_000.0, stats.areaM2, 0.01)
        assertEquals(7, stats.lineCount)
        assertEquals(77, stats.photoCount)
        assertEquals(1670.0, stats.distanceM, 0.01)
        assertEquals(167.0, stats.flightTimeS, 0.001)
    }

    /**
     * Grid angle 90 = east–west lines. "Up" is east, so "left" is north. Width across = 200 m → ⌈200/45⌉ = 5 lines
     * spanning 180 m, 10 m margins: y = 190, 145, 100, 55, 10 (from the north). Distance 5 × 300 + 4 × 45 = 1680 m.
     */
    @Test
    fun rectangleWithEastWestLines() {
        val (spec, g) = grid(rectangle, angle = 90.0, spacing = 45.0)
        assertEquals(5, g.lineCount)
        listOf(190.0, 145.0, 100.0, 55.0, 10.0).forEachIndexed { i, y ->
            val eastbound = i % 2 == 0
            assertAt(if (eastbound) 0.0 else 300.0, y, g.passes[i].photoStart, "line $i start")
            assertEquals(300.0, g.passes[i].photoLengthM, 1e-3)
        }
        assertEquals(1680.0, surveyStats(spec, g, 20.0, 10.0).distanceM, 0.01)
    }

    /**
     * Grid angle 45 over a 100 m square: in the grid frame the square is a diamond with corners 50√2 = 70.71 m from
     * its centre. Width across = 141.42 m → ⌈141.42/30⌉ = 5 lines at −60, −30, 0, 30, 60 m from the centre (margins
     * (141.42 − 120)/2 = 10.71 m). A diamond's chord at offset t is 2 × (70.71 − |t|): 21.42, 81.42, 141.42, 81.42,
     * 21.42 m, total 347.11 m.
     */
    @Test
    fun squareAt45Degrees() {
        val square = listOf(m(0.0, 0.0), m(100.0, 0.0), m(100.0, 100.0), m(0.0, 100.0))
        val (_, g) = grid(square, angle = 45.0, spacing = 30.0)
        val expected = listOf(21.4214, 81.4214, 141.4214, 81.4214, 21.4214)
        assertEquals(expected.size, g.lineCount)
        g.passes.zip(expected).forEach { (p, len) -> assertEquals(len, p.photoLengthM, 1e-3) }
        assertEquals(347.1068, g.passes.sumOf { it.photoLengthM }, 1e-3)
        // The middle line runs along the diagonal from (0, 0) to (100, 100): heading 45°.
        assertAt(0.0, 0.0, g.passes[2].photoStart, "diagonal start")
        assertAt(100.0, 100.0, g.passes[2].photoEnd, "diagonal end")
    }

    /**
     * Concave: a U open to the north (outer 300 × 200, notch x 100..200 from y 100 up). East–west lines, spacing 60:
     * ⌈200/60⌉ = 4 lines spanning 180 m, margins 10 m → y = 190, 130, 70, 10. The two upper lines cross both arms:
     * two passes each (0..100 and 200..300), flown straight across the 100 m notch. The lower two cross the base:
     * one 300 m pass. Photo length 4 × 100 + 2 × 300 = 1000 m; gaps 2 × 100; line hops 3 × 60. Distance 1380 m.
     */
    @Test
    fun concaveAreaGetsSeparatePassesPerArm() {
        val u = listOf(
            m(0.0, 0.0), m(300.0, 0.0), m(300.0, 200.0), m(200.0, 200.0),
            m(200.0, 100.0), m(100.0, 100.0), m(100.0, 200.0), m(0.0, 200.0),
        )
        val (spec, g) = grid(u, angle = 90.0, spacing = 60.0)
        assertEquals(4, g.lineCount)
        assertEquals(listOf(0, 0, 1, 1, 2, 3), g.passes.map { it.line })
        assertEquals(listOf(100.0, 100.0, 100.0, 100.0, 300.0, 300.0), g.passes.map { it.photoLengthM }.map { kotlin.math.round(it * 1000) / 1000 })
        assertAt(100.0, 190.0, g.passes[0].photoEnd, "first arm ends at the notch")
        assertAt(200.0, 190.0, g.passes[1].photoStart, "second arm starts after the notch")
        assertEquals(100.0, g.connectorsM[0], 1e-3, "straight on across the notch")
        assertEquals(1380.0, surveyStats(spec, g, 20.0, 10.0).distanceM, 0.01)
    }

    /** A strip narrower than one spacing gets one line down the middle: 1000 × 10 m, spacing 45 → 1 line at y = 5. */
    @Test
    fun veryThinAreaGetsOneCentreLine() {
        val strip = listOf(m(0.0, 0.0), m(1000.0, 0.0), m(1000.0, 10.0), m(0.0, 10.0))
        val (spec, g) = grid(strip, angle = 90.0, spacing = 45.0)
        assertEquals(1, g.lineCount)
        assertAt(0.0, 5.0, g.passes.single().photoStart, "centre line")
        assertEquals(51, surveyStats(spec, g, 20.0, 10.0).photoCount, "⌊1000/20⌋ + 1")
    }

    /** Smaller than one photo: still one line and at least one photo (the trigger fires when switched on). */
    @Test
    fun verySmallAreaStillGetsAPhoto() {
        val tiny = listOf(m(0.0, 0.0), m(5.0, 0.0), m(5.0, 5.0), m(0.0, 5.0))
        val (spec, g) = grid(tiny, angle = 0.0, spacing = 45.0)
        assertEquals(1, g.lineCount)
        assertEquals(5.0, g.passes.single().photoLengthM, 1e-3)
        val stats = surveyStats(spec, g, 20.0, 10.0)
        assertEquals(1, stats.photoCount)
        assertEquals(25.0, stats.areaM2, 1e-3)
    }

    @Test
    fun entryCornerPicksTheFirstLineAndDirection() {
        fun first(entry: EntryCorner) = grid(rectangle, 0.0, 45.0, entry = entry).second.passes.first().photoStart
        assertAt(15.0, 0.0, first(EntryCorner.BOTTOM_LEFT), "south-west")
        assertAt(285.0, 0.0, first(EntryCorner.BOTTOM_RIGHT), "south-east")
        assertAt(15.0, 200.0, first(EntryCorner.TOP_LEFT), "north-west")
        assertAt(285.0, 200.0, first(EntryCorner.TOP_RIGHT), "north-east")
    }

    /**
     * Copter with a 10 m extension: each line starts 10 m before the area and ends 10 m after it (entry y = −10, exit
     * y = 210 on the first line). Hops stay 45 m. Distance 7 × (10 + 200 + 10) + 6 × 45 = 1540 + 270 = 1810 m.
     */
    @Test
    fun copterExtensionRunsInAndOutOfTheArea() {
        val (spec, g) = grid(rectangle, 0.0, 45.0, turn = Turnaround.Copter(extensionM = 10.0))
        assertAt(15.0, -10.0, g.passes[0].entry, "run-in starts outside")
        assertAt(15.0, 210.0, g.passes[0].exit, "run-out ends outside")
        assertAt(15.0, 0.0, g.passes[0].photoStart, "photos still start at the edge")
        assertEquals(1810.0, surveyStats(spec, g, 20.0, 10.0).distanceM, 0.01)
    }

    /**
     * Plane, turn radius 50 m, lead-in 30 m, lead-out 20 m. Each pass is 30 + 200 + 20 = 250 m. Lines are 45 m apart,
     * less than 2r = 100 m, so every turn is a bulb turn: cos γ = (45 + 100) / 200 = 0.725, γ = 0.75953 rad,
     * length 50 × (π + 4 × 0.75953) = 309.032 m. The next line's entry is 10 m further on than this line's exit
     * (lead-in 30 vs lead-out 20), so each leg is 319.032 m. Distance 7 × 250 + 6 × 319.032 = 3664.19 m.
     */
    @Test
    fun planeLeadInLeadOutAndTurns() {
        val (spec, g) = grid(rectangle, 0.0, 45.0, turn = Turnaround.Plane(turnRadiusM = 50.0, leadInM = 30.0, leadOutM = 20.0))
        assertAt(15.0, -30.0, g.passes[0].entry, "lead-in before the area")
        assertAt(15.0, 220.0, g.passes[0].exit, "lead-out after it")
        assertAt(60.0, 230.0, g.passes[1].entry, "southbound line: lead-in north of the area")
        g.connectorsM.forEach { assertEquals(319.032, it, 1e-3) }
        assertEquals(3664.19, surveyStats(spec, g, 20.0, 20.0).distanceM, 0.01)
    }

    /**
     * Turn lengths by hand, r = 50 m:
     * - lateral 100 (= 2r): π × 50 = 157.080; lateral 150: 157.080 + 50 = 207.080.
     * - lateral 0 (back onto the same line): cos γ = 1/2, γ = π/3, 50 × (π + 4π/3) = 50 × 7π/3 = 366.519.
     * - lateral 50: cos γ = 150/200 = 0.75, γ = 0.722734, 50 × (π + 2.890937) = 301.626.
     * Also checked with Python while writing the pass.
     */
    @Test
    fun planeTurnLengths() {
        assertEquals(157.0796, planeTurnM(100.0, 50.0), 1e-4)
        assertEquals(207.0796, planeTurnM(150.0, 50.0), 1e-4)
        assertEquals(366.5191, planeTurnM(0.0, 50.0), 1e-4)
        assertEquals(301.6265, planeTurnM(50.0, 50.0), 1e-4)
        // Continuous at lateral = 2r, but steep: just below it γ ≈ √((2r − lateral)/(2r)), so the bulb grows like
        // 4r·γ. 1e-10 m short of 2r: γ ≈ 1e-6, extra ≈ 2e-4 m. (1e-6 m short already costs 2 cm.)
        assertEquals(planeTurnM(100.0, 50.0), planeTurnM(100.0 - 1e-10, 50.0), 1e-3, "no jump where the two cases meet")
        assertTrue(planeTurnM(99.0, 50.0) > planeTurnM(100.0, 50.0), "closer lines never make the turn shorter")
    }

    /** 20 m/s at 30° bank: r = 400 / (9.80665 × tan 30°) = 400 / (9.80665 × 0.577350) = 400 / 5.661845 = 70.648 m. */
    @Test
    fun planeTurnRadius() {
        assertEquals(70.648, planeTurnRadiusM(20.0, 30.0), 1e-3)
    }

    /**
     * Away from the equator: the same 300 × 200 m rectangle at CMAC (35.3633° S). Longitude degrees shrink by
     * cos(35.3633°) = 0.815478, so 300 m east = 300 / (111 195.08 × 0.815478) degrees. Area still 60 000 m².
     */
    @Test
    fun areaAtCmacLatitude() {
        val lat0 = -35.363261
        val lonPerMetre = 1 / (metresPerDegree * cos(lat0 * PI / 180))
        val latPerMetre = 1 / metresPerDegree
        val rect = listOf(
            LatLon(lat0, 149.0), LatLon(lat0, 149.0 + 300 * lonPerMetre),
            LatLon(lat0 + 200 * latPerMetre, 149.0 + 300 * lonPerMetre), LatLon(lat0 + 200 * latPerMetre, 149.0),
        )
        assertEquals(60_000.0, polygonAreaM2(rect), 5.0) // the projection's centre is 100 m north: 0.001 % scale drift
        assertEquals(7, buildSurveyGrid(GridSpec(rect, 0.0, 45.0, copter)).lineCount)
    }

    @Test
    fun invalidAreasAreRejected() {
        fun rejects(polygon: List<LatLon>, spacing: Double = 45.0, reason: String) {
            val e = assertFailsWith<IllegalArgumentException> { buildSurveyGrid(GridSpec(polygon, 0.0, spacing, copter)) }
            assertTrue(e.message!!.contains(reason), "${e.message}")
        }
        rejects(rectangle.take(2), reason = "at least 3")
        rejects(listOf(m(0.0, 0.0), m(100.0, 0.0), m(200.0, 0.0)), reason = "no size")
        rejects(rectangle, spacing = 0.0, reason = "spacing")
        rejects(rectangle, spacing = 0.1, reason = "too many") // 300 / 0.1 = 3000 lines
        // Straddling 180°: from 179.999° E to 179.999° W is 222 m, but the longitudes jump by 359.998°.
        rejects(listOf(LatLon(0.0, 179.999), LatLon(0.0, -179.999), LatLon(0.001, -179.999), LatLon(0.001, 179.999)), reason = "antimeridian")
    }

    @Test
    fun anAreaNextToTheAntimeridianIsFine() {
        val nearby = listOf(LatLon(0.0, 179.990), LatLon(0.0, 179.999), LatLon(0.001, 179.999), LatLon(0.001, 179.990))
        assertTrue(buildSurveyGrid(GridSpec(nearby, 0.0, 45.0, copter)).lineCount > 0)
    }
}
