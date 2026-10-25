package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.connection.BufferedMavConnection
import com.divpundir.mavlink.connection.MavRawFrame
import com.divpundir.mavlink.connection.StreamState
import com.divpundir.mavlink.definitions.ardupilotmega.ArdupilotmegaDialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Buffer

class BackpressureConnectionTest {

    /**
     * Pass 47: 1000 frames already waiting in the socket, a collector that takes 1 ms per frame. The reader waits for
     * it, so every frame arrives in order. With mavlink-kotlin's `asCoroutine` in its place the collector got only the newest 128 (frames 872..999).
     * When the bytes run out the stream fails (end of file), as a closed socket would.
     */
    @Test
    fun aSlowCollectorGetsEveryFrameOfABurst() = runTest {
        val wire = Buffer()
        val heartbeat = copterHeartbeat()
        repeat(1000) { i ->
            wire.write(MavRawFrame.createUnsignedV2(i.toUByte(), 1u, 1u, 0u, heartbeat.serializeV2(), heartbeat.instanceCompanion.crcExtra).rawBytes)
        }
        val connection = BackpressureConnection(BufferedMavConnection(wire, wire, wire, ArdupilotmegaDialect), StandardTestDispatcher(testScheduler))

        val got = mutableListOf<Int>()
        backgroundScope.launch {
            connection.mavFrame.collect {
                got += it.sequence.toInt()
                delay(1.milliseconds)
            }
        }
        runCurrent() // subscribed before anything is read
        connection.connect(backgroundScope)
        advanceTimeBy(1010.milliseconds) // 1 ms per frame, then some

        assertEquals(List(1000) { it % 256 }, got)
        assertIs<StreamState.Inactive.Failed>(connection.streamState.first())
    }
}
