package com.kft.gcs.core.planning

import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.geo.LocalPoint
import com.kft.gcs.core.geo.LocalProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Hand-worked: shapes drawn in metres on a flat map around CMAC, cut positions worked out on paper in each test.
 * Widths are deliberately not whole multiples of the spacing: "exactly 10 spacings wide" turns into 10 or 11 lines on
 * a rounding error of the projection, which a real field never is.
 */
class AreaSplitTest {
    private val projection = LocalProjection(LatLon(-35.363261, 149.165230))
    private fun at(east: Double, north: Double) = projection.toLatLon(LocalPoint(east, north))

    /** East and north extent of [polygon] in metres, rounded to centimetres: (west, east, south, north). */
    private fun extent(polygon: List<LatLon>): List<Double> {
        val p = polygon.map(projection::toLocal)
        return listOf(p.minOf { it.x }, p.maxOf { it.x }, p.minOf { it.y }, p.maxOf { it.y }).map { kotlin.math.round(it * 100) / 100 + 0.0 }
    }

    // 290 m east–west by 100 m north–south, its south-west corner at the origin.
    private val field = listOf(at(0.0, 0.0), at(290.0, 0.0), at(290.0, 100.0), at(0.0, 100.0))

    /**
     * Grid angle 0: lines run north–south, 30 m apart. ⌈290 / 30⌉ = 10 lines, centred: the first at (290 − 270) / 2 =
     * 10, so x = 10, 40, …, 280, each 100 m long. Two parts: 5 lines each, the cut midway between x = 130 and
     * x = 160, at x = 145. Two 145 m × 100 m halves.
     */
    @Test
    fun aRectangleInTwoIsCutMidwayBetweenTheMiddleLines() {
        val parts = splitArea(field, gridAngleDeg = 0.0, lineSpacingM = 30.0, parts = 2)
        assertEquals(listOf(listOf(0.0, 145.0, 0.0, 100.0), listOf(145.0, 290.0, 0.0, 100.0)), parts.map(::extent))
        parts.forEach { assertEquals(14_500.0, polygonAreaM2(it), 0.5) }
    }

    /**
     * Three parts of the same field. Running length after each line: 100, 200, …, 1000. Boundary 1 aims for 333.3: 300
     * (3 lines) is nearer than 400. Boundary 2 aims for 666.7: 700 (7 lines) is nearer than 600. So 3, 4 and 3 lines:
     * cuts midway between lines 3|4 (x = 70, 100 → 85) and 7|8 (x = 190, 220 → 205).
     */
    @Test
    fun threePartsTakeWholeLinesThreeFourThree() {
        val parts = splitArea(field, 0.0, 30.0, 3)
        assertEquals(listOf(0.0 to 85.0, 85.0 to 205.0, 205.0 to 290.0), parts.map { extent(it).let { e -> e[0] to e[1] } })
        // Planned on its own, each part has exactly its share of the 10 lines: none was broken by a cut.
        val lineCounts = parts.map { buildSurveyGrid(GridSpec(it, 0.0, 30.0, Turnaround.Copter())).lineCount }
        assertEquals(listOf(3, 4, 3), lineCounts)
    }

    /**
     * Grid angle 90: the lines run east–west, so the cuts do too. ⌈100 / 30⌉ = 4 lines, the first 5 m in:
     * y = 5, 35, 65, 95, each 290 m long. Two parts of 2 lines: the cut midway between y = 35 and y = 65, at y = 50.
     */
    @Test
    fun theCutFollowsTheGridAngle() {
        val parts = splitArea(field, gridAngleDeg = 90.0, lineSpacingM = 30.0, parts = 2)
        // At 90° "across" runs towards the south (see GridFrame), so the first part is the northern half.
        assertEquals(listOf(listOf(0.0, 290.0, 50.0, 100.0), listOf(0.0, 290.0, 0.0, 50.0)), parts.map(::extent))
    }

    /**
     * A right triangle: corners (0,0), (290,0), (0,290), lines north–south 30 m apart at x = 10, 40, …, 280. The line
     * at x is 290 − x long: 280, 250, 220, …, 10, in all 1450 m. Half is 725: after 2 lines 530, after 3 lines 750,
     * which is nearer, so the cut is after 3 lines, midway between x = 70 and x = 100: x = 85. By line count it would
     * have been x = 145, giving the first drone 1100 m of lines and the second 350 m.
     */
    @Test
    fun partsAreBalancedByLineLengthNotLineCount() {
        val triangle = listOf(at(0.0, 0.0), at(290.0, 0.0), at(0.0, 290.0))
        val parts = splitArea(triangle, 0.0, 30.0, 2)
        assertEquals(listOf(0.0 to 85.0, 85.0 to 290.0), parts.map { extent(it).let { e -> e[0] to e[1] } })
        // The first part is the trapezium between x = 0 and 85: heights 290 and 205, area 85 × 247.5 = 21 037.5 m².
        assertEquals(21_037.5, polygonAreaM2(parts[0]), 1.0)
        assertEquals(42_050.0 - 21_037.5, polygonAreaM2(parts[1]), 1.0)
    }

    /** 50 m wide at 30 m spacing is 2 lines: asking for 5 parts gives 2, one line each. One part is the area itself. */
    @Test
    fun neverMorePartsThanLines() {
        val narrow = listOf(at(0.0, 0.0), at(50.0, 0.0), at(50.0, 100.0), at(0.0, 100.0))
        assertEquals(2, splitArea(narrow, 0.0, 30.0, 5).size)
        assertEquals(listOf(field), splitArea(field, 0.0, 30.0, 1))
        assertFailsWith<IllegalArgumentException> { splitArea(field, 0.0, 30.0, 0) }
    }

    /** The parts tile the area: their areas add up to the whole, for an irregular shape at an odd angle too. */
    @Test
    fun thePartsAddUpToTheWholeArea() {
        val shape = listOf(at(0.0, 0.0), at(260.0, -40.0), at(340.0, 120.0), at(180.0, 220.0), at(-30.0, 150.0))
        val parts = splitArea(shape, gridAngleDeg = 37.0, lineSpacingM = 22.0, parts = 4)
        assertEquals(4, parts.size)
        assertEquals(polygonAreaM2(shape), parts.sumOf(::polygonAreaM2), 1.0)
        assertTrue(parts.all { polygonAreaM2(it) > 0 })
    }
}
