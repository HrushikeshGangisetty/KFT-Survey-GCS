package com.kft.gcs.feature.replay

import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.geo.LocalPoint
import com.kft.gcs.core.geo.LocalProjection
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlightAnalysisTest {
    private val home = LatLon(31.2, 75.9)
    private val map = LocalProjection(home)

    /**
     * The flight used below: 10 Hz, 10 m/s due east for 10 s (100 m), then due north for 10 s. Truth in map metres
     * (x east, y north). The VIO started facing east, so its odom frame is x = east, y = right of east = south:
     * odom (x, y) = (east, −north). No noise, so the fit must be exact.
     */
    private fun truthEN(t: Double) = if (t <= 10) LocalPoint(10 * t, 0.0) else LocalPoint(100.0, 10 * (t - 10))
    private val times = (0..200).map { it / 10.0 }
    private val truth = times.map { TruthSample(it + T0, map.toLatLon(truthEN(it)), 50.0, 10.0, 14) }

    private fun visp(drift: (Double) -> Double = { 0.0 }, reset: (Double) -> Int = { 0 }, shift: Double = 0.0) = times.map { t ->
        val p = truthEN(t)
        // Drift is added along odom x (east here). Pi time is FC time − 100 s: the bridge's clock, not the FC's.
        VispSample(t + T0, t + T0 - 100, p.x + drift(t) + shift, -p.y + shift, reset(t), ignored = false)
    }

    @Test
    fun rigidFitRecoversARotationAndShift() {
        // A unit square turned 90° anticlockwise and moved by (10, 20): (1,0) → (0,1) → (10, 21). Analytic.
        val src = listOf(LocalPoint(0.0, 0.0), LocalPoint(1.0, 0.0), LocalPoint(1.0, 1.0), LocalPoint(0.0, 1.0))
        val dst = src.map { LocalPoint(10 - it.y, 20 + it.x) }
        val f = rigidFit(src, dst)
        src.zip(dst).forEach { (s, d) ->
            assertEquals(d.x, f(s).x, 1e-9)
            assertEquals(d.y, f(s).y, 1e-9)
        }
    }

    @Test
    fun noDriftMeansNoErrorWhateverTheOdomFrame() {
        val flight = analyse(FcLog(truth, TruthSource.GPS, visp(shift = 500.0), emptyList()), null)
        assertEquals(truth.size, flight.published.size)
        assertTrue(flight.published.all { it.errorM!! < 1e-6 }, "max ${flight.published.maxOf { it.errorM!! }}")
        assertEquals(1, flight.stats.alignments)
        assertEquals(0.0, flight.stats.anchorErrorM!!, 1e-6)
        // 100 m east + 100 m north, all of it with vision.
        assertEquals(200.0, flight.stats.distanceM, 0.05)
        assertEquals(200.0, flight.stats.visionDistanceM, 0.05)
    }

    @Test
    fun driftAfterTheAnchorIsMeasuredNotAbsorbed() {
        // Anchor: the first 30 m of truth, i.e. t ≤ 3 s. Drift starts at t = 5 s and grows 0.5 m/s along odom x (east),
        // so at t = 20 s it is 7.5 m. Distance from the anchor's start (t = 0) to the end: 200 m → drift 3.75 %.
        val flight = analyse(FcLog(truth, TruthSource.GPS, visp(drift = { t -> if (t > 5) 0.5 * (t - 5) else 0.0 }), emptyList()), null)
        assertEquals(7.5, flight.published.last().errorM!!, 1e-6)
        assertEquals(7.5, flight.stats.peakErrorM!!, 1e-6)
        assertEquals(3.75, flight.stats.driftPct!!, 1e-3)
        assertEquals(0.0, flight.published.at(T0 + 3.0).errorM!!, 1e-6)
    }

    @Test
    fun eachVioResetGetsItsOwnAnchorAndShortSegmentsAreNotDrawn() {
        // Reset counter 0 until 8 s, 1 for 8.0–8.9 s (9 m flown: too short to anchor), 2 from 9 s on in a far-off frame.
        fun reset(t: Double) = if (t < 8) 0 else if (t < 9) 1 else 2
        val odom = times.map { t ->
            val p = truthEN(t)
            val far = if (reset(t) == 2) 1000.0 else 0.0
            OdomSample(t + T0 - 100, p.x + far, -p.y - far, reset(t), "TRACKING")
        }
        // VISP carries the same counter but stays continuous (ArduPilot re-anchors before logging): one anchor, all rows.
        val flight = analyse(FcLog(truth, TruthSource.GPS, visp(reset = ::reset), emptyList()), odom)
        assertEquals(truth.size, flight.published.size)
        assertEquals(2, flight.stats.visionResets)
        assertEquals(1 + 2, flight.stats.alignments)
        assertEquals(truth.size - 10, flight.vio.size, "the 10 rows of VIO segment 1 are left out")
        assertTrue(flight.vio.all { it.errorM!! < 1e-6 })
        assertTrue(flight.notes.any { "too short" in it })
        assertEquals(2, flight.events.count { it.text.startsWith("VIO aligned") })
        assertEquals(2, flight.events.count { it.text.startsWith("VIO reset") })
    }

    @Test
    fun aPiRecordingFromAnotherRunIsLeftOut() {
        val other = listOf(OdomSample(5.0, 0.0, 0.0, 0, "TRACKING")) // Pi time 5 s → FC time 105 s, long before T0
        val flight = analyse(FcLog(truth, TruthSource.GPS, visp(), emptyList()), other)
        assertTrue(flight.odometry.isEmpty())
        assertEquals(T0, flight.startS)
        assertTrue(flight.notes.any { "doesn't overlap" in it })
    }

    @Test
    fun piOdometryIsPutOnTheFcClockThroughVisp() {
        // The recording uses Pi time (FC − 100 s, as VISP's RTimeUS says). A 3 m east offset after 12 s shows only if
        // the clocks were matched: with the wrong offset, the error would be tens of metres from the 10 m/s motion.
        val odom = times.map { t ->
            val p = truthEN(t)
            OdomSample(t + T0 - 100, p.x + if (t > 12) 3.0 else 0.0, -p.y, 0, if (t > 15) "DEGRADED" else "TRACKING")
        }
        val flight = analyse(FcLog(truth, TruthSource.GPS, visp(), emptyList()), odom)
        assertEquals(T0 + 20, flight.vio.last().t, 1e-6)
        assertEquals(3.0, flight.vio.last().errorM!!, 1e-6)
        assertEquals(0.0, flight.vio.at(T0 + 10.0).errorM!!, 1e-6)
        assertTrue(flight.events.any { it.text == "VIO TRACKING → DEGRADED" && abs(it.t - (T0 + 15.1)) < 1e-6 })
    }

    @Test
    fun withoutVispThePiClockCantBeMatched() {
        val flight = analyse(FcLog(truth, TruthSource.GPS, emptyList(), emptyList()), listOf(OdomSample(1.0, 0.0, 0.0, 0, "TRACKING")))
        assertTrue(flight.vio.isEmpty())
        assertTrue(flight.notes.any { "clock" in it })
        assertNull(flight.stats.meanErrorM)
    }

    @Test
    fun aLogWithoutTruthSaysSo() {
        val flight = analyse(FcLog(emptyList(), null, visp(), emptyList()), null)
        assertTrue(flight.published.isEmpty())
        assertTrue(flight.notes.first().startsWith("No GPS"))
    }

    @Test
    fun fcLogUsesFixedGpsAndFallsBackToSitlTruth() {
        val gpsLog = DataFlashBuilder()
            .fmt(1, "GPS", "QBBLLfB", "TimeUS,I,Status,Lat,Lng,Spd,NSats")
            .fmt(2, "POS", "Qf", "TimeUS,RelHomeAlt")
            .row(2, 1_000_000L, 12.5f)
            .row(1, 2_000_000L, 0, 1, 31.0, 75.0, 0f, 3) // no fix: left out
            .row(1, 3_000_000L, 0, 3, 31.2, 75.9, 4.5f, 14)
            .row(1, 3_000_000L, 1, 3, 31.3, 75.8, 4.5f, 9) // second GPS: left out
            .build()
        val fromGps = fcLogFrom(com.kft.gcs.core.geoio.readDataFlash(gpsLog, FC_LOG_MESSAGES))
        assertEquals(TruthSource.GPS, fromGps.truthSource)
        val only = fromGps.truth.single()
        assertEquals(3.0, only.t)
        assertEquals(31.2, only.position.latitude, 1e-7)
        assertEquals(12.5, only.altM)
        assertEquals(14, only.sats)

        // SITL with the GPS disabled: SIM is the truth, speed from consecutive positions. 0.0001° of latitude at 31° is
        // 11.087 m: meridian radius M = a(1−e²)/(1−e²sin²φ)^1.5 = 6 352 348 m (Snyder eq. 4-18), × π/180 = 110 870 m/°.
        val simLog = DataFlashBuilder()
            .fmt(3, "SIM", "QLL", "TimeUS,Lat,Lng")
            .row(3, 1_000_000L, 31.0, 75.0)
            .row(3, 2_000_000L, 31.0001, 75.0)
            .build()
        val fromSim = fcLogFrom(com.kft.gcs.core.geoio.readDataFlash(simLog, FC_LOG_MESSAGES))
        assertEquals(TruthSource.SITL, fromSim.truthSource)
        assertEquals(11.09, fromSim.truth[1].speedMS!!, 0.02)
        assertNotNull(fromSim.truth[0])
    }

    private fun List<AlignedPoint>.at(t: Double) = first { abs(it.t - t) < 1e-6 }

    private companion object {
        /** FC boot time at the start of the test flight. */
        const val T0 = 1000.0
    }
}
