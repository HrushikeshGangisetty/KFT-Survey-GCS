package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.adapters.coroutines.CoroutinesMavConnection
import com.divpundir.mavlink.adapters.coroutines.asCoroutine
import com.divpundir.mavlink.connection.AbstractMavConnection
import com.divpundir.mavlink.connection.BufferedMavConnection
import com.divpundir.mavlink.connection.MavConnection
import com.divpundir.mavlink.definitions.ardupilotmega.ArdupilotmegaDialect
import kotlinx.coroutines.CoroutineDispatcher
import okio.BufferedSink
import okio.BufferedSource
import okio.IOException

/**
 * A raw byte pipe to a vehicle (UDP, TCP, later serial and Bluetooth). MAVLink framing sits on top of it.
 *
 * `internal` on purpose: code outside `core:mavlink` can't even name a transport, so it can't write bytes that
 * bypass [MavTxGateway]. That's how CLAUDE.md §4 ("only the gateway writes") is enforced by the compiler.
 */
internal interface MavTransport {
    /** Blocking. Opens the link and returns its byte streams. Throws [IOException] if it can't. */
    fun open(): Streams

    /** Closes the link. Also unblocks a read in progress (it throws), which is how the reader loop stops. */
    fun close()

    class Streams(val source: BufferedSource, val sink: BufferedSink)
}

/**
 * Adapts a [MavTransport] to mavlink-kotlin: [BufferedMavConnection] does the framing and checksums over our
 * byte streams, with the ArduPilot dialect so ArduPilot-only messages decode too.
 */
internal class TransportMavConnection(private val transport: MavTransport) : AbstractMavConnection() {
    override fun open(): MavConnection {
        val streams = transport.open()
        return BufferedMavConnection(
            streams.source,
            streams.sink,
            object : okio.Closeable { override fun close() = transport.close() },
            ArdupilotmegaDialect,
        )
    }

    // Closing the transport is what interrupts a blocking connect/accept.
    override fun interruptOpen() = transport.close()
}

/** Builds the platform transport for [config], wrapped for coroutines. Socket code lives in `jvmCommonMain`. */
internal fun openTransportConnection(config: LinkConfig, ioDispatcher: CoroutineDispatcher, serialPorts: SerialPorts): CoroutinesMavConnection =
    TransportMavConnection(createTransport(config, serialPorts)).asCoroutine(ioDispatcher)

internal expect fun createTransport(config: LinkConfig, serialPorts: SerialPorts): MavTransport
