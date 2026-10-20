package com.kft.gcs.core.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The store against the visionnav P4 scenarios, with GCS arrival times chosen by hand. Each record "arrives" 10 ms
 * after its Pi tick (an assumed link delay), so the GCS and Pi clocks are deliberately offset.
 */
class CompanionStateTest {
    private fun CompanionState.feed(text: String, arrivalS: Double) = reduce(parseRecord(text), arrivalS.seconds)

    /** Odometry ticks at 20 Hz from [fromS] until [toS], arriving 10 ms after each tick. */
    private fun CompanionState.odometry(fromS: Double, toS: Double, build: (Double) -> String): CompanionState {
        var s = this
        var k = (fromS * 20).toInt()
        while (k / 20.0 <= toS + 1e-9) {
            s = s.feed(build(k / 20.0), k / 20.0 + 0.01)
            k++
        }
        return s
    }

    /**
     * `stale`: from 5 s measurement_time freezes while publishing goes on. Age = Pi now − measurement time. At GCS
     * time 6.01 s the last record (tick 6.0) was published at Pi 6.005 s and arrived at 6.01, so Pi now = 6.005 s and the
     * age is 6.005 − 5.0 = 1.005 s: over the 0.5 s timeout although a message arrived this very instant.
     */
    @Test
    fun staleScenarioAgesFromMeasurementTimeNotArrival() {
        val s = CompanionState().odometry(0.0, 6.0) { t -> Feed.odometry(t, measurementS = minOf(t, 5.0)) }
        assertEquals(1005.milliseconds, s.odometryAge(6.01.seconds))
        // Before the freeze the age is just the 5 ms publish latency.
        val fresh = CompanionState().odometry(0.0, 4.0) { t -> Feed.odometry(t) }
        assertEquals(5.milliseconds, fresh.odometryAge(4.01.seconds))
        // And when nothing more arrives, the age keeps growing with GCS time: 1.005 s + 2 s.
        assertEquals(3005.milliseconds, s.odometryAge(8.01.seconds))
        assertEquals(0, s.rejected, "a frozen measurement time is stale data, not a broken record")
    }

    /**
     * `lost_reset`: 1 m/s north; LOST 10–13 s, INITIALIZING 13–13.5 s, then TRACKING with reset_counter 1 and odom
     * re-originated, so x restarts at 0. The track must not join 9.95 m to 0 m: the new points are another segment.
     */
    @Test
    fun lostResetScenarioCountsTheResetAndSplitsTheTrack() {
        val s = CompanionState().odometry(0.0, 14.0) { t ->
            when {
                t < 10.0 -> Feed.odometry(t, x = t)
                t < 13.0 -> Feed.odometry(t, state = "LOST")
                t < 13.5 -> Feed.odometry(t, state = "INITIALIZING")
                else -> Feed.odometry(t, x = t - 13.5, reset = 1)
            }
        }
        assertEquals(1, s.resetsSeen)
        assertEquals(1, s.odometry!!.record.resetCounter)
        assertEquals(VioState.TRACKING, s.odometry.record.state)
        val (before, after) = s.track.partition { it.segment == 0 }
        assertEquals(9.95, before.last().x, 1e-9, "last tracked point before LOST")
        assertEquals(TrackPoint(0.0, 0.0, 1), after.first(), "the new segment starts at the new origin")
        assertEquals(0.5, after.last().x, 1e-9)
        // While LOST the estimate has no position, so nothing was added between 10 and 13.5 s.
        assertEquals(200 + 11, s.track.size, "0..9.95 m every 5 cm, then 0..0.5 m (spacing limit is 2 cm)")
    }

    @Test
    fun lostStateIsKeptWhileItLasts() {
        val s = CompanionState().odometry(0.0, 11.0) { t -> if (t < 10.0) Feed.odometry(t, x = t) else Feed.odometry(t, state = "LOST") }
        assertEquals(VioState.LOST, s.odometry!!.record.state)
        assertEquals(0, s.resetsSeen)
        assertEquals(5.milliseconds, s.odometryAge(11.01.seconds), "LOST is fresh news, not stale data")
    }

    /**
     * `wrong_map`: an accepted fix 40 m north of truth. The GCS can't know it is wrong; it must show it as a map fix
     * with its own position and age. 40 m north at 17.4°N: 40 / 6 378 137 rad = 0.000359° → 17.400359°.
     * Its image was taken at Pi 8.5 s; at GCS 9.01 s Pi now is 9.005 s, so it is 0.505 s old.
     */
    @Test
    fun wrongMapFixIsKeptAsAMapFixWithItsAge() {
        var s = CompanionState().odometry(0.0, 9.0) { t -> Feed.odometry(t) }
        s = s.feed(Feed.mapFix(9.0, lat = 17.400359, lon = 78.5), 9.01)
        assertEquals(17.400359, s.mapFix!!.latDeg)
        assertEquals(true, s.mapFix.accepted)
        assertEquals(1, s.fixesAccepted)
        assertEquals(505.milliseconds, s.mapFixAge(9.01.seconds))
        s = s.feed(Feed.mapFix(11.0, lat = 17.4, lon = 78.5, accepted = false, reason = "too few inliers"), 11.01)
        assertEquals("too few inliers", s.mapFix!!.rejectReason)
        assertEquals(1, s.fixesRejected)
    }

    @Test
    fun aMapFixBeforeAnyOdometryHasNoAge() {
        val s = CompanionState().feed(Feed.mapFix(9.0, 17.4, 78.5), 9.01)
        assertNull(s.mapFixAge(9.01.seconds), "unknown, never 'fresh'")
    }

    @Test
    fun malformedAndWrongVersionRecordsChangeNothingButTheCounter() {
        val good = CompanionState().odometry(0.0, 1.0) { t -> Feed.odometry(t, x = t) }
        val afterJunk = good.feed("{not json", 1.02)
        assertEquals(good.copy(rejected = 1, lastRejection = afterJunk.lastRejection), afterJunk)
        val afterOld = afterJunk.feed(Feed.odometry(1.05, x = 99.0, version = "0.1"), 1.06)
        assertEquals("schema_version 0.1, expected 0.2", afterOld.lastRejection)
        assertEquals(2, afterOld.rejected)
        assertEquals(1.0, afterOld.odometry!!.record.positionM[0], "the 0.1 record's position was not used")
    }

    @Test
    fun duplicateAndOutOfOrderOdometryIsRefused() {
        val s = CompanionState().odometry(0.0, 1.0) { t -> Feed.odometry(t, x = t) }
        val dup = s.feed(Feed.odometry(1.0, x = 1.0), 1.02)
        assertEquals("odometry publish time did not advance", dup.lastRejection)
        val late = s.feed(Feed.odometry(1.05, measurementS = 0.5, x = 0.5), 1.06)
        assertEquals("odometry measurement time went backwards", late.lastRejection)
        assertEquals(s.odometry, late.odometry)
    }

    /** A new run_id (or source) is a new session: the old run's clock, resets and track are dropped, not mixed in. */
    @Test
    fun aNewRunStartsOver() {
        val s = CompanionState(Connection.Connected).odometry(0.0, 1.0) { t -> Feed.odometry(t, x = t) }
            .feed(Feed.odometry(0.05, x = 0.0, runId = "live-run-2", source = "live"), 1.1)
        assertEquals("live-run-2", s.runId)
        assertEquals(Source.LIVE, s.source)
        assertEquals(listOf(TrackPoint(0.0, 0.0, 0)), s.track)
        assertEquals(1, s.runChanges)
        assertEquals(Connection.Connected, s.connection)
    }

    /** Health has no timestamp in contract 0.2, so its age is from arrival. */
    @Test
    fun healthAgeIsSinceArrival() {
        val s = CompanionState().feed(Feed.health(), 3.0).feed(Feed.health("DEGRADED", "tracking lost"), 3.5)
        assertEquals(HealthStatus.DEGRADED, s.health.getValue("vio").record.status)
        assertEquals(1.5.seconds, s.healthAge("vio", 5.0.seconds))
    }
}
