package com.kft.gcs.core.planning

import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.geo.LocalPoint
import com.kft.gcs.core.geo.LocalProjection
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Corridor scan and crosshatch, on the Pass 13 worked example (SurveyTest): P4P at 100 m has a 150 m × 100 m footprint,
 * so 70 % side / 80 % front overlap gives 45 m lines and a 20 m trigger. Every expected number is worked by hand below.
 * East–west lines are 310 m, not 300: 300 m is an exact number of 20 m triggers, and then micrometres of map projection
 * decide between 15 and 16 photos (the coverage is the same either way). 310 m keeps the hand counts off that edge.
 */
class CorridorCrosshatchTest {
    private val metresPerDegreeNorth = 6_335_439.327292820 * kotlin.math.PI / 180
    private val metresPerDegreeEast = 6_378_137.0 * kotlin.math.PI / 180
    private fun m(x: Double, y: Double) = LatLon(y / metresPerDegreeNorth, x / metresPerDegreeEast)
    private val camera = Camera(13.2, 8.8, 5472, 3648, 8.8)
    private val rectangle = listOf(m(0.0, 0.0), m(310.0, 0.0), m(310.0, 200.0), m(0.0, 200.0))

    private fun assertNear(expected: LatLon, actual: LatLon, tolM: Double = 0.01) {
        val p = LocalProjection(expected).toLocal(actual)
        assertTrue(hypot(p.x, p.y) < tolM, "expected $expected, got $actual (${hypot(p.x, p.y)} m off)")
    }

    // ---- where the lines go across a corridor

    @Test
    fun offsetsWithoutTheCentreLineTileTheWidth() {
        // 100 m wide, 4 lines: s = 100 / 4 = 25, lines at −50 + 12.5, +25, +25, +25.
        val (offsets, s) = corridorOffsets(CorridorShape(50.0, 50.0, 4, includeCentreLine = false))
        assertEquals(25.0, s, 1e-12)
        assertEquals(listOf(-37.5, -12.5, 12.5, 37.5), offsets)
    }

    @Test
    fun offsetsWithTheCentreLine() {
        // 3 lines over ±50: one each side, s = 50 / 1.5 = 33.3; the outer strips end at 33.3 + 16.7 = 50.
        val (three, s3) = corridorOffsets(CorridorShape(50.0, 50.0, 3, includeCentreLine = true))
        assertEquals(100.0 / 3, s3, 1e-12)
        assertEquals(listOf(-100.0 / 3, 0.0, 100.0 / 3), three)
        // 2 lines over ±50 with one on the centre: the other must cover a whole side, s = 50 / 0.5 = 100.
        assertEquals(listOf(0.0, 100.0) to 100.0, corridorOffsets(CorridorShape(50.0, 50.0, 2, includeCentreLine = true)))
        // All on one side (a road edge): 0 to 60 m right, 3 lines, s = 60 / 2.5 = 24: 0, 24, 48 (+12 reaches 60).
        assertEquals(listOf(0.0, 24.0, 48.0) to 24.0, corridorOffsets(CorridorShape(0.0, 60.0, 3, includeCentreLine = true)))
    }

    // ---- the offset line itself

    /**
     * East, then north (a left turn). 10 m to the right is the outside of the turn: y = −10 along the first leg,
     * x = 110 up the second, meeting at (110, −10), 10·√2 from the corner, as a 90° miter must be. 10 m left is the
     * inside: (0, 10), (90, 10), (90, 100). Lengths: 110 + 110 = 220 m outside, 90 + 90 = 180 m inside.
     */
    @Test
    fun offsetLineAroundARightAngle() {
        val l = listOf(LocalPoint(0.0, 0.0), LocalPoint(100.0, 0.0), LocalPoint(100.0, 100.0))
        assertEquals(listOf(LocalPoint(0.0, -10.0), LocalPoint(110.0, -10.0), LocalPoint(110.0, 100.0)), offsetLine(l, 10.0).map(::round))
        assertEquals(listOf(LocalPoint(0.0, 10.0), LocalPoint(90.0, 10.0), LocalPoint(90.0, 100.0)), offsetLine(l, -10.0).map(::round))
    }

    /** Doubling back (about 174°): the offset would fold over itself, so it's refused with the point's number. */
    @Test
    fun hairpinIsRefused() {
        val l = listOf(LocalPoint(0.0, 0.0), LocalPoint(100.0, 0.0), LocalPoint(0.0, 10.0))
        assertTrue(assertFailsWith<IllegalArgumentException> { offsetLine(l, 5.0) }.message!!.contains("point 2"))
    }

    // ---- a whole corridor survey

    /**
     * A 310 m road running east, 75 m each side, 3 lines without the centre line: s = 150 / 3 = 50 m, lines 50 m
     * north, on, and 50 m south of it. Side overlap 1 − 50/150 = 67 % (no warning). Each 310 m line: ⌊310/20⌋ + 1 = 16
     * photos (the last 10 m strip is under half the 100 m footprint), 48 in all. Flight: 3 × (10 + 310 + 10) with
     * run-in/out d/2 = 10 m, + 2 hops of 50 m = 1090 m. Area 310 × 150 = 46 500 m².
     * Order: leftmost (north) first, flown east, then back west on the centre, then east on the south line.
     */
    @Test
    fun straightCorridor() {
        val plan = planSurvey(corridor(CorridorShape(75.0, 75.0, 3, includeCentreLine = false)))
        assertEquals(50.0, plan.lineSpacingM, 1e-9)
        assertEquals(3, plan.stats.lineCount)
        assertEquals(48, plan.stats.photoCount)
        assertEquals(1090.0, plan.stats.distanceM, 1e-3)
        assertEquals(46_500.0, plan.stats.areaM2, 1e-3)
        assertTrue(plan.warnings.isEmpty(), "${plan.warnings}")
        val p = plan.grid.passes
        assertNear(m(0.0, 150.0), p[0].photoStart)
        assertNear(m(310.0, 150.0), p[0].photoEnd)
        assertNear(m(310.0, 100.0), p[1].photoStart) // back west on the centre line
        assertNear(m(0.0, 50.0), p[2].photoStart)
        assertNear(m(-10.0, 150.0), p[0].entry) // the 10 m run-in, before the start
    }

    /** 2 lines over 150 m: s = 75, side overlap 1 − 75/150 = 50 %, under the 60 % minimum → warned. */
    @Test
    fun sparseCorridorIsWarned() {
        val plan = planSurvey(corridor(CorridorShape(75.0, 75.0, 2, includeCentreLine = false)))
        assertEquals(0.5, plan.warnings.filterIsInstance<SurveyWarning.CorridorSideOverlapLow>().single().overlap, 1e-9)
    }

    /**
     * A bent corridor: 200 m east, then 200 m north; one line 20 m to the right (outside the left turn). Its path:
     * (0, −20) → (220, −20) → (220, 200): 220 + 220 = 440 m, with the bend as a via point. 440 / 20 = 22 → 23 photos.
     * The camera goes off at (23 − ½)·20 = 450 m along: 10 m into the run-out, beyond the far end (220, 200).
     */
    @Test
    fun bentCorridorFollowsTheBend() {
        val bent = listOf(m(0.0, 0.0), m(200.0, 0.0), m(200.0, 200.0))
        val plan = planSurvey(corridor(CorridorShape(0.0, 40.0, 1, includeCentreLine = false), bent))
        val pass = plan.grid.passes.single()
        assertEquals(440.0, pass.photoLengthM, 1e-3)
        assertNear(m(220.0, -20.0), pass.via.single())
        assertEquals(23, plan.stats.photoCount)
        assertNear(m(220.0, 210.0), pass.cameraOff(plan.triggerDistanceM, plan.footprint.alongM))
    }

    // ---- crosshatch

    /**
     * A 310 × 200 m field twice. First pass: ⌈310/45⌉ = 7 north–south lines at x = 20…290 (centred), 200 m each: 11
     * photos, 77 in all; 7 × (10 + 200 + 10) + 6 × 45 = 1810 m, ending northbound on the last line at (290, 210).
     * Second pass at 90°: ⌈200/45⌉ = 5 east–west lines at y = 10…190, 310 m each: 16 photos, 80 in all;
     * 5 × (10 + 310 + 10) + 4 × 45 = 1830 m. The nearest start to (290, 210) is the north line's east end, run-in
     * included: (320, 190), a hop of √(30² + 20²) = 36.06 m. Total: 12 lines, 157 photos, 1810 + 1830 + 36.06 m.
     */
    @Test
    fun crosshatchAddsACrossingPass() {
        val plan = planSurvey(area(Crosshatch()))
        val second = plan.crosshatch!!
        assertEquals(100.0, second.altitudeM)
        assertEquals(5, second.grid.lineCount)
        assertEquals(12, plan.stats.lineCount)
        assertEquals(157, plan.stats.photoCount)
        assertNear(m(320.0, 190.0), second.grid.passes.first().entry)
        assertEquals(1810.0 + 1830.0 + hypot(30.0, 20.0), plan.stats.distanceM, 1e-3)
        assertEquals(2, plan.legs.size)
    }

    /**
     * 50 m higher: 150 m, footprint 225 × 150 m, so 67.5 m lines and a 30 m trigger keep the same overlaps.
     * ⌈200 / 67.5⌉ = 3 lines of ⌊310/30⌋ + 1 = 11 photos: 33, plus the first pass's 77 = 110.
     */
    @Test
    fun crosshatchAltitudeOffsetKeepsTheOverlaps() {
        val plan = planSurvey(area(Crosshatch(altitudeOffsetM = 50.0)))
        val second = plan.crosshatch!!
        assertEquals(150.0, second.altitudeM)
        assertEquals(67.5, second.lineSpacingM, 1e-9)
        assertEquals(30.0, second.triggerDistanceM, 1e-9)
        assertEquals(110, plan.stats.photoCount)
    }

    @Test
    fun crosshatchBelowTheGroundIsRefused() {
        assertFailsWith<IllegalArgumentException> { planSurvey(area(Crosshatch(altitudeOffsetM = -100.0))) }
    }

    private fun area(crosshatch: Crosshatch) = SurveyParams(
        rectangle, camera, SurveyHeight.Altitude(100.0), speedMs = 10.0, turnaround = Turnaround.Copter(), crosshatch = crosshatch,
    )

    private fun corridor(shape: CorridorShape, line: List<LatLon> = listOf(m(0.0, 100.0), m(310.0, 100.0))) = SurveyParams(
        line, camera, SurveyHeight.Altitude(100.0), speedMs = 10.0, turnaround = Turnaround.Copter(), corridor = shape,
    )

    private fun round(p: LocalPoint) = LocalPoint(kotlin.math.round(p.x * 1e6) / 1e6, kotlin.math.round(p.y * 1e6) / 1e6)
}
