package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.connection.MavRawFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.ByteString.Companion.decodeHex

/** The S2 wiring: what a real link's reader sees has passed [com.kft.gcs.core.mavlink.signing.SigningSource], and only [FrameSink] writes. */
class TransportMavConnectionTest {

    @Test
    fun readsAreFilteredAndOnlyFrameSinkWrites() = runTest {
        val fromVehicle = Buffer()
        val toVehicle = Buffer()
        val transport = object : MavTransport {
            override fun open() = MavTransport.Streams(fromVehicle, toVehicle)
            override fun close() = Unit
        }
        val connection = transportConnection(transport)
        assertNull(connection.frameSink, "no sink before the transport opens")

        // A signed heartbeat this GCS has no key for (pymavlink, ADR-006), then an unsigned one from system 4.
        fromVehicle.write("fd090100000101000000050000000203510403bb7a07bc9a785634222b31429d76c5".decodeHex())
        val heartbeat = copterHeartbeat()
        fromVehicle.write(MavRawFrame.createUnsignedV2(0u, 4u, 1u, 0u, heartbeat.serializeV2(), heartbeat.instanceCompanion.crcExtra).rawBytes)

        connection.connect()
        assertEquals(4, connection.next().systemId.toInt(), "the signed frame without a key never reached the library")

        assertNotNull(connection.frameSink).write(heartbeat, 255u, 190u, signFor = null)
        connection.sendUnsignedV2(255u, 190u, heartbeat) // the library's own writer: goes nowhere
        val written = toVehicle.readByteArray()
        assertEquals(12 + 9, written.size, "exactly one frame on the wire, FrameSink's")
    }
}
