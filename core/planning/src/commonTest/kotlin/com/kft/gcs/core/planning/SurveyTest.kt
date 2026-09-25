package com.kft.gcs.core.planning

import com.kft.gcs.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [planSurvey] on the Pass 13 worked example: P4P (13.2 × 8.8 mm, 5472 × 3648 px, 8.8 mm), 300 × 200 m at the
 * equator, north–south lines, 70 % side / 80 % front, copter. The camera here also has a 2.5 s minimum interval and
 * 8 MB photos (made-up values that divide nicely; the preset's own numbers are unverified).
 */
class SurveyTest {
    private val metresPerDegree = 111_195.08
    private fun m(x: Double, y: Double) = LatLon(y / metresPerDegree, x / metresPerDegree)
    private val rectangle = listOf(m(0.0, 0.0), m(300.0, 0.0), m(300.0, 200.0), m(0.0, 200.0))
    private val camera = Camera(13.2, 8.8, 5472, 3648, 8.8, minTriggerIntervalS = 2.5, mbPerPhoto = 8.0)

    private fun params(height: SurveyHeight, speed: Double = 10.0) =
        SurveyParams(rectangle, camera, height, speedMs = speed, turnaround = Turnaround.Copter())

    /**
     * Altitude first, 100 m: GSD 0.027412 m, spacing 45 m, trigger 20 m, 77 photos, 1670 m, 167 s (Pass 13 table).
     * - Batteries, 60 s usable each: ⌈167 / 60⌉ = ⌈2.78⌉ = 3.
     * - Data: 77 × 8 MB = 616 MB.
     * - Photo interval: 20 m / 10 m/s = 2.0 s, below the camera's 2.5 s → warning; the fastest that works is
     *   20 / 2.5 = 8 m/s.
     * - GSD 2.74 cm is above a 2.5 cm limit → warning.
     */
    @Test
    fun altitudeFirstWithStatsAndBothWarnings() {
        val plan = planSurvey(params(SurveyHeight.Altitude(100.0)), SurveyLimits(usableFlightTimeS = 60.0, maxGsdM = 0.025))
        assertEquals(100.0, plan.altitudeM)
        assertEquals(0.027412, plan.gsdM, 1e-6)
        assertEquals(45.0, plan.lineSpacingM, 1e-9)
        assertEquals(20.0, plan.triggerDistanceM, 1e-9)
        assertEquals(77, plan.stats.photoCount)
        assertEquals(167.0, plan.stats.flightTimeS, 1e-6)
        assertEquals(3, plan.batteries)
        assertEquals(616.0, plan.dataMb, 1e-9)
        val interval = plan.warnings.filterIsInstance<SurveyWarning.PhotoIntervalTooShort>().single()
        assertEquals(2.0, interval.intervalS, 1e-9)
        assertEquals(8.0, interval.maxSpeedMs, 1e-9)
        assertEquals(0.025, plan.warnings.filterIsInstance<SurveyWarning.GsdAboveLimit>().single().limitM)
    }

    /**
     * GSD first, 2 cm/px: altitude = 0.02 × 8.8 × 5472 / 13.2 = 72.96 m (CameraTest). Footprint across =
     * 13.2 × 72.96 / 8.8 = 1.5 × 72.96 = 109.44 m, along 72.96 m. Spacing 109.44 × 0.3 = 32.832 m, trigger
     * 72.96 × 0.2 = 14.592 m. At 5 m/s the interval is 2.918 s ≥ 2.5 s and there's no GSD limit: no warnings.
     */
    @Test
    fun gsdFirstComputesTheAltitude() {
        val plan = planSurvey(params(SurveyHeight.Gsd(0.02), speed = 5.0))
        assertEquals(72.96, plan.altitudeM, 1e-9)
        assertEquals(0.02, plan.gsdM, 1e-12)
        assertEquals(109.44, plan.footprint.acrossM, 1e-9)
        assertEquals(32.832, plan.lineSpacingM, 1e-9)
        assertEquals(14.592, plan.triggerDistanceM, 1e-9)
        assertTrue(plan.warnings.isEmpty(), "${plan.warnings}")
        assertEquals(null, plan.batteries, "no battery time set")
    }

    /** 167 s on 83.5 s batteries is exactly 2, not 3. */
    @Test
    fun exactlyTwoBatteries() {
        assertEquals(2, planSurvey(params(SurveyHeight.Altitude(100.0)), SurveyLimits(usableFlightTimeS = 83.5)).batteries)
    }

    /** The Plane example of SurveyGridTest reports no loops; a 3-line strip reports its 2 as a warning. */
    @Test
    fun planeLoopsBecomeAWarning() {
        val narrow = listOf(m(0.0, 0.0), m(120.0, 0.0), m(120.0, 200.0), m(0.0, 200.0))
        val plan = planSurvey(params(SurveyHeight.Altitude(100.0)).copy(polygon = narrow, turnaround = Turnaround.Plane(50.0, 0.0, 0.0)))
        assertEquals(SurveyWarning.PlaneLoopTurns(2), plan.warnings.filterIsInstance<SurveyWarning.PlaneLoopTurns>().single())
    }

    @Test
    fun badInputsAreRejectedWithAReason() {
        assertFailsWith<IllegalArgumentException> { planSurvey(params(SurveyHeight.Altitude(100.0), speed = 0.0)) }
        assertFailsWith<IllegalArgumentException> { planSurvey(params(SurveyHeight.Gsd(0.0))) }
        assertFailsWith<IllegalArgumentException> { planSurvey(params(SurveyHeight.Altitude(100.0)).copy(frontOverlap = 1.0)) }
    }
}
