package com.kft.gcs.core.mavlink

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import okio.IOException

/**
 * Android serial over USB-OTG through usb-serial-for-android: FTDI, CP210x, CH34x and CDC-ACM (a flight
 * controller's own USB port, or most USB telemetry radios).
 *
 * USB access needs the user's permission per device. [open] asks for it and fails with an IOException; the
 * connection manager's reconnect loop then retries every few seconds and succeeds once the user taps Allow. That
 * avoids a broadcast receiver and its lifecycle entirely.
 */
actual class SerialPorts(context: Context) {
    private val context = context.applicationContext
    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager

    actual fun list(): List<SerialPortInfo> = UsbSerialProber.getDefaultProber().findAllDrivers(usb).map { driver ->
        val device = driver.device
        SerialPortInfo(device.deviceName, listOfNotNull(device.productName, driver.javaClass.simpleName.removeSuffix("SerialDriver")).joinToString(" · "))
    }

    internal actual fun open(name: String, baud: Int): SerialLink {
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usb).firstOrNull { it.device.deviceName == name }
            ?: throw IOException("USB device $name isn't connected")
        if (!usb.hasPermission(driver.device)) {
            usb.requestPermission(driver.device, permissionIntent())
            throw IOException("Waiting for USB permission: tap Allow on the tablet")
        }
        val connection = usb.openDevice(driver.device) ?: throw IOException("Couldn't open USB device $name")
        val port = driver.ports.first()
        try {
            port.open(connection)
            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // Some CDC-ACM flight controllers only send once the host raises DTR, as a terminal program would.
            port.dtr = true
        } catch (e: java.io.IOException) {
            port.close()
            throw IOException("Couldn't configure $name: ${e.message}")
        }
        return UsbSerialLink(port)
    }

    /**
     * The permission dialog's reply goes to a broadcast nobody listens for: the next retry just checks
     * `hasPermission`. Explicit package and FLAG_MUTABLE, because Android 12+ requires a mutability flag, the system
     * adds the device to the intent, and Android 14 refuses mutable implicit intents.
     */
    private fun permissionIntent(): PendingIntent {
        val intent = Intent("com.kft.gcs.USB_PERMISSION").setPackage(context.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }
}

private class UsbSerialLink(private val port: UsbSerialPort) : SerialLink {
    override fun read(buffer: ByteArray): Int = port.read(buffer, SerialLink.READ_TIMEOUT_MS)

    override fun write(bytes: ByteArray) = port.write(bytes, WRITE_TIMEOUT_MS)

    override fun close() = port.close()

    private companion object {
        const val WRITE_TIMEOUT_MS = 1_000
    }
}
