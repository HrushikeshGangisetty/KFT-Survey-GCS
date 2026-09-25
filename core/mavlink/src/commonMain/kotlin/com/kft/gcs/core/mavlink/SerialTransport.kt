package com.kft.gcs.core.mavlink

import kotlin.concurrent.Volatile
import okio.Buffer
import okio.IOException
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer

/** A serial port the OS offers. [name] is what a [LinkConfig.Serial] stores: "COM7" on Windows, a USB device path on Android. */
data class SerialPortInfo(val name: String, val description: String)

/**
 * Lists and opens this platform's serial ports: jSerialComm on desktop, usb-serial-for-android over USB-OTG on
 * Android. Each shell creates one (Android needs a Context) and hands it to Koin; see `app:shared`.
 */
expect class SerialPorts {
    /** Ports present right now. Cheap enough to call when the user taps "Refresh". */
    fun list(): List<SerialPortInfo>

    /** Opens [name] at [baud], 8N1. Throws [IOException] with an operator-readable message if it can't. */
    internal fun open(name: String, baud: Int): SerialLink
}

/**
 * The few things we need from an open serial port. Internal and tiny on purpose: the platform adapters are thin
 * wrappers around a library, and tests use a fake, so [SerialTransport]'s logic is checked without hardware.
 */
internal interface SerialLink {
    /** Waits up to [READ_TIMEOUT_MS] for bytes. Returns how many were read, 0 on timeout. Throws [IOException] if the port is gone. */
    fun read(buffer: ByteArray): Int

    /** Writes all of [bytes] or throws [IOException]. */
    fun write(bytes: ByteArray)

    fun close()

    companion object {
        /** Short, so a closed port is noticed quickly by the reader loop. */
        const val READ_TIMEOUT_MS = 200
    }
}

/**
 * A serial port as a [MavTransport]. MAVLink over serial is a plain byte stream, like TCP.
 *
 * Reads poll with a short timeout rather than blocking forever, because neither library reliably unblocks a read
 * when another thread closes the port. [close] sets a flag, and the next timed-out read turns it into the
 * [IOException] that ends the connection, which is how the connection manager notices and reconnects.
 */
internal class SerialTransport(private val openLink: () -> SerialLink) : MavTransport {
    @Volatile private var link: SerialLink? = null
    @Volatile private var closed = false

    override fun open(): MavTransport.Streams {
        val l = openLink()
        link = l
        return MavTransport.Streams(LinkSource(l).buffer(), LinkSink(l).buffer())
    }

    override fun close() {
        closed = true
        link?.close()
    }

    private inner class LinkSource(private val link: SerialLink) : Source {
        private val chunk = ByteArray(1024)

        override fun read(sink: Buffer, byteCount: Long): Long {
            while (true) {
                if (closed) throw IOException("serial port closed")
                val n = link.read(chunk)
                if (n > 0) {
                    sink.write(chunk, 0, n)
                    return n.toLong()
                }
            }
        }

        override fun timeout() = Timeout.NONE
        override fun close() = this@SerialTransport.close()
    }

    /** Collects a frame's bytes and writes them in one go on flush (mavlink-kotlin flushes after every frame). */
    private inner class LinkSink(private val link: SerialLink) : Sink {
        private val pending = Buffer()

        override fun write(source: Buffer, byteCount: Long) = pending.write(source, byteCount)

        override fun flush() {
            if (pending.size > 0) link.write(pending.readByteArray())
        }

        override fun timeout() = Timeout.NONE
        override fun close() = this@SerialTransport.close()
    }
}

/**
 * Baud rates offered in the picker. 57600 is the SiK telemetry-radio default (ArduPilot SERIAL1_BAUD = 57), 115200
 * is the usual USB/console rate, and the higher ones are for fast radios and companion links. Over native USB (CDC)
 * the flight controller ignores the baud rate entirely.
 */
val STANDARD_BAUD_RATES = listOf(9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600)
