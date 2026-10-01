package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.connection.BufferedMavConnection
import com.divpundir.mavlink.definitions.ardupilotmega.ArdupilotmegaDialect
import com.divpundir.mavlink.definitions.minimal.Heartbeat
import com.divpundir.mavlink.definitions.minimal.MavType
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import okio.Buffer

/**
 * Real sockets on localhost: proves our byte-level transports carry MAVLink frames both ways. JVM-only because it
 * uses java.net directly. No sleeps: each blocking read either has data already queued or a socket timeout.
 */
class SocketTransportsTest {

    private val gcsHeartbeat = Heartbeat(type = MavEnumValue.of(MavType.GCS), mavlinkVersion = 3u)

    @Test
    fun udpListenReceivesFromTheVehicleAndRepliesToIt() {
        val port = DatagramSocket(0).use { it.localPort } // a port that was free a moment ago
        val gcs = TransportMavConnection(UdpTransport(bindPort = port, fixedRemote = null)).apply { connect() }
        DatagramSocket().use { vehicle ->
            vehicle.soTimeout = 2_000
            val bytes = encode(copterHeartbeat())
            vehicle.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), port))

            assertEquals(MavType.QUADROTOR.value, (gcs.next().message as Heartbeat).type.value)

            // The GCS never learned the vehicle's port from config; it replies to where the datagram came from.
            gcs.sendUnsignedV2(255u, 190u, gcsHeartbeat)
            val reply = DatagramPacket(ByteArray(512), 512).also(vehicle::receive)
            assertEquals(MavType.GCS.value, (decode(reply.data.copyOf(reply.length)) as Heartbeat).type.value)
        }
        gcs.close()
    }

    /** ADR-005 §6: vehicles behind two senders. A frame from the GCS goes to both addresses, not just the last one heard. */
    @Test
    fun udpListenRepliesToEverySenderItHasHeard() {
        val port = DatagramSocket(0).use { it.localPort }
        val gcs = TransportMavConnection(UdpTransport(bindPort = port, fixedRemote = null)).apply { connect() }
        DatagramSocket().use { first ->
            DatagramSocket().use { second ->
                val bytes = encode(copterHeartbeat())
                for (vehicle in listOf(first, second, first)) { // `first` sends again: it must not be added twice
                    vehicle.soTimeout = 2_000
                    vehicle.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), port))
                    gcs.next()
                }
                gcs.sendUnsignedV2(255u, 190u, gcsHeartbeat)
                for (vehicle in listOf(first, second)) {
                    val reply = DatagramPacket(ByteArray(512), 512).also(vehicle::receive)
                    assertEquals(MavType.GCS.value, (decode(reply.data.copyOf(reply.length)) as Heartbeat).type.value)
                }
                // Exactly one copy each: a second read on `first` times out.
                first.soTimeout = 200
                assertFailsWith<SocketTimeoutException> { first.receive(DatagramPacket(ByteArray(512), 512)) }
            }
        }
        gcs.close()
    }

    @Test
    fun tcpClientCarriesFramesBothWays() {
        ServerSocket(0).use { server ->
            val gcs = TransportMavConnection(TcpTransport("127.0.0.1", server.localPort)).apply { connect() }
            server.accept().use { vehicle ->
                vehicle.soTimeout = 2_000
                vehicle.getOutputStream().write(encode(copterHeartbeat()))
                assertIs<Heartbeat>(gcs.next().message)

                gcs.sendUnsignedV2(255u, 190u, gcsHeartbeat)
                val buffer = ByteArray(512)
                val n = vehicle.getInputStream().read(buffer)
                assertEquals(MavType.GCS.value, (decode(buffer.copyOf(n)) as Heartbeat).type.value)
            }
            gcs.close()
        }
    }

    /** Frames a message exactly as a vehicle would, using mavlink-kotlin over an in-memory buffer. */
    private fun <T : MavMessage<T>> encode(message: T): ByteArray {
        val buffer = Buffer()
        BufferedMavConnection(buffer, buffer, buffer, ArdupilotmegaDialect).sendUnsignedV2(1u, 1u, message)
        return buffer.readByteArray()
    }

    private fun decode(bytes: ByteArray): MavMessage<*> {
        val buffer = Buffer().write(bytes)
        return BufferedMavConnection(buffer, buffer, buffer, ArdupilotmegaDialect).next().message
    }
}
