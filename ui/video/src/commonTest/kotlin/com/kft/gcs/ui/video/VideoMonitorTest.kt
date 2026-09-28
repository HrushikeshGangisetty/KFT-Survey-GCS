package com.kft.gcs.ui.video

import kotlin.test.Test
import kotlin.test.assertEquals

/** Connecting → live → lost → reconnecting, with the times passed in by hand. */
class VideoMonitorTest {
    @Test
    fun framesMakeItLiveAndGiveTheFrameRate() {
        val m = VideoMonitor(nowMs = 0)
        assertEquals(VideoStatus.Connecting(1, null), m.status)
        m.onFrame(100, latencyMs = 40)
        assertEquals(VideoStatus.Live(0, 40), m.status)
        // 15 more frames in the next second: the T12's 15 fps.
        for (i in 1..15) m.onFrame(100 + i * 66L, latencyMs = 40)
        m.tick(1_100)
        assertEquals(VideoStatus.Live(15, 40), m.status)
    }

    @Test
    fun noPictureFor3SecondsIsLost() {
        val m = VideoMonitor(0)
        m.onFrame(100, null)
        m.tick(3_100) // exactly 3 s: still live
        assertEquals(VideoStatus.Live::class, m.status::class)
        m.tick(3_101)
        assertEquals(VideoStatus.Lost("No picture for 3 s"), m.status)
    }

    @Test
    fun aFailureIsLostAndTheRetrySaysWhy() {
        val m = VideoMonitor(0)
        m.onFailure("Can't reach the camera")
        m.onFrame(10, null) // a late frame from the failed session doesn't revive it
        assertEquals(VideoStatus.Lost("Can't reach the camera"), m.status)
        m.retry(2_000)
        assertEquals(VideoStatus.Connecting(2, "Can't reach the camera"), m.status)
    }

    @Test
    fun anAttemptThatNeverShowsAPictureIsGivenUpAfter10Seconds() {
        val m = VideoMonitor(0)
        m.tick(10_000)
        assertEquals(VideoStatus.Connecting(1, null), m.status)
        m.tick(10_001)
        assertEquals(VideoStatus.Lost("No picture after 10 s"), m.status)
    }

    @Test
    fun reconnectsWait1Then2Then3Seconds() {
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 3_000L), (1..4).map(VideoMonitor::backoffMs))
    }
}
