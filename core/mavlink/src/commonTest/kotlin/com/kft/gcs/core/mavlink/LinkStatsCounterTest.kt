package com.kft.gcs.core.mavlink

import kotlin.test.Test
import kotlin.test.assertEquals

class LinkStatsCounterTest {

    @Test
    fun countsGapsAcrossTheSequenceWrap() {
        val counter = LinkStatsCounter()
        // Received 254, 255, 0, 3: the 255 -> 0 wrap is not a gap, 0 -> 3 skips 1 and 2.
        listOf(254, 255, 0, 3).forEach { counter.onFrame(1u, 1u, it.toUByte()) }
        // Hand calculation: 4 received, 2 lost -> 2 / (4 + 2) = 33.33 %.
        assertEquals(100.0 * 2 / 6, counter.snapshot().lossPercent, 1e-9)
    }

    @Test
    fun sendersAreTrackedSeparately() {
        val counter = LinkStatsCounter()
        // Autopilot (1/1) and a gimbal (1/154) interleave, each with its own unbroken counter: no loss.
        counter.onFrame(1u, 1u, 10u)
        counter.onFrame(1u, 154u, 200u)
        counter.onFrame(1u, 1u, 11u)
        counter.onFrame(1u, 154u, 201u)
        assertEquals(0.0, counter.snapshot().lossPercent)
    }

    @Test
    fun rateIsFramesInTheLastClosedSecond() {
        val counter = LinkStatsCounter()
        repeat(7) { counter.onFrame(1u, 1u, it.toUByte()) }
        assertEquals(0, counter.snapshot().messagesPerSecond, "window not closed yet")
        counter.tick()
        assertEquals(7, counter.snapshot().messagesPerSecond)
        counter.tick()
        assertEquals(0, counter.snapshot().messagesPerSecond, "nothing arrived in the second window")
    }
}
