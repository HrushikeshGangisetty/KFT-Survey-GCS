package com.kft.gcs.core.mavlink

import com.fazecast.jSerialComm.SerialPort
import okio.IOException

/**
 * Desktop serial ports through jSerialComm (Windows COM ports, including a paired Bluetooth SPP device, which
 * Windows exposes as a virtual COM port; spec §2.1).
 */
actual class SerialPorts {
    actual fun list(): List<SerialPortInfo> =
        SerialPort.getCommPorts().map { SerialPortInfo(it.systemPortName, it.descriptivePortName) }

    internal actual fun open(name: String, baud: Int): SerialLink {
        val port = SerialPort.getCommPort(name)
        port.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        // Semi-blocking: a read returns as soon as any bytes arrive, or with 0 after the timeout.
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, SerialLink.READ_TIMEOUT_MS, 0)
        if (!port.openPort()) throw IOException("Can't open $name (unplugged, or in use by another program?)")
        return JSerialCommLink(port)
    }
}

private class JSerialCommLink(private val port: SerialPort) : SerialLink {
    override fun read(buffer: ByteArray): Int {
        val n = port.readBytes(buffer, buffer.size)
        if (n < 0) throw IOException("${port.systemPortName} read failed (error ${port.lastErrorCode})")
        return n
    }

    override fun write(bytes: ByteArray) {
        if (port.writeBytes(bytes, bytes.size) != bytes.size) throw IOException("${port.systemPortName} write failed")
    }

    override fun close() {
        port.closePort()
    }
}
