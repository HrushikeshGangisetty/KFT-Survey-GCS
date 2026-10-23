package com.kft.gcs.core.mavlink

import com.fazecast.jSerialComm.SerialPort
import com.fazecast.jSerialComm.SerialPortInvalidPortException
import okio.IOException

/**
 * Desktop serial ports through jSerialComm (Windows COM ports, including a paired Bluetooth SPP device, which
 * Windows exposes as a virtual COM port; spec §2.1).
 */
actual class SerialPorts {
    actual fun list(): List<SerialPortInfo> =
        SerialPort.getCommPorts().map {
            // Windows names them "Standard Serial over Bluetooth link (COM26)".
            SerialPortInfo(
                it.systemPortName,
                it.descriptivePortName,
                bluetooth = it.descriptivePortName.contains("Bluetooth", ignoreCase = true),
                // jSerialComm reports -1 for a port that isn't a USB device (a built-in or Bluetooth COM port).
                usb = if (it.vendorID >= 0) UsbId(it.vendorID, it.productID, it.serialNumber?.takeIf(String::isNotBlank)) else null,
            )
        }

    internal actual fun open(name: String, baud: Int, readTimeoutMs: Int, rts: Boolean): SerialLink {
        // On Linux and macOS, getCommPort throws an unchecked SerialPortInvalidPortException for a name that doesn't
        // exist (Windows doesn't; openPort just returns false there). Callers catch IOException, so turn it into one
        // that names the port: one readable error type on every OS.
        val port = try {
            SerialPort.getCommPort(name)
        } catch (e: SerialPortInvalidPortException) {
            throw IOException("Can't open $name (no such port)", e)
        }
        port.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        // Semi-blocking: a read returns as soon as any bytes arrive, or with 0 after the timeout.
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, readTimeoutMs, 0)
        if (!port.openPort()) throw IOException("Can't open $name (unplugged, or in use by another program?)")
        if (rts) {
            port.setDTR()
            port.setRTS()
        }
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
