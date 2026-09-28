package com.kft.gcs.core.mavlink

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import okio.IOException

/**
 * Android serial ports:
 * - USB-OTG through usb-serial-for-android: FTDI, CP210x, CH34x and CDC-ACM (a flight controller's own USB port,
 *   or most USB telemetry radios).
 * - Bluetooth Classic SPP: every device paired in Android's Bluetooth settings (HC-05/HC-06 modules, Bluetooth
 *   telemetry bridges), opened as an RFCOMM socket. The same approach as the old KFT GCS app
 *   (`BluetoothMavConnection`: the SPP UUID, a blocking connect, paired devices only).
 *
 * USB access needs the user's permission per device. [open] asks for it and fails with an IOException; the
 * connection manager's reconnect loop then retries every few seconds and succeeds once the user taps Allow. That
 * avoids a broadcast receiver and its lifecycle entirely.
 *
 * Bluetooth needs the "Nearby devices" permission (BLUETOOTH_CONNECT, Android 12+), which only an activity can ask
 * for: the app shell sets [requestBluetoothPermission], and [list] calls it when the permission is missing. Nothing
 * else is asked for: connecting to a paired device needs no scanning (BLUETOOTH_SCAN) and no location, which the
 * old app requested.
 */
actual class SerialPorts(context: Context) {
    private val context = context.applicationContext
    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val bluetooth: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    /** Set by the app shell while its UI is up: shows Android's permission prompt for Bluetooth. */
    var requestBluetoothPermission: (() -> Unit)? = null

    actual fun list(): List<SerialPortInfo> = usbPorts() + bluetoothPorts()

    private fun usbPorts() = UsbSerialProber.getDefaultProber().findAllDrivers(usb).map { driver ->
        val device = driver.device
        // Android 10+ throws SecurityException for the serial number until the user has allowed the device.
        val serial = runCatching { device.serialNumber }.getOrNull()
        SerialPortInfo(
            device.deviceName,
            listOfNotNull(device.productName, driver.javaClass.simpleName.removeSuffix("SerialDriver")).joinToString(" · "),
            usb = UsbId(device.vendorId, device.productId, serial),
        )
    }

    /** Paired devices, by name. None when the tablet has no Bluetooth, it's off, or the permission is missing (then asked for). */
    private fun bluetoothPorts(): List<SerialPortInfo> {
        val adapter = bluetooth ?: return emptyList()
        if (!hasBluetoothPermission()) {
            // Posted, not called: the list is first read while the Links screen is being composed (its ViewModel is
            // created then), and a permission prompt must not be launched in the middle of a composition.
            requestBluetoothPermission?.let { Handler(Looper.getMainLooper()).post(it) }
            return emptyList()
        }
        if (!adapter.isEnabled) return emptyList()
        return try {
            adapter.bondedDevices.map { d ->
                SerialPortInfo(bluetoothPortName(d.address), "Bluetooth · ${d.name ?: d.address}", bluetooth = true)
            }.sortedBy { it.description }
        } catch (e: SecurityException) {
            emptyList() // the permission was withdrawn between the check and the call
        }
    }

    internal actual fun open(name: String, baud: Int, readTimeoutMs: Int, rts: Boolean): SerialLink {
        bluetoothAddressOf(name)?.let { return openBluetooth(it) }
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
            if (rts) port.rts = true
        } catch (e: java.io.IOException) {
            port.close()
            throw IOException("Couldn't configure $name: ${e.message}")
        }
        return UsbSerialLink(port, readTimeoutMs)
    }

    /**
     * An RFCOMM socket to the paired device at [address], on the SPP service. Blocking: connect() takes a few seconds
     * (and up to about 12 s to fail); it runs on the connection manager's IO thread. A module that's off, out of range
     * or connected to another phone fails here, and the reconnect loop tries again.
     * ponytail: no fallback for modules whose SDP record is broken (the reflective createRfcommSocket(1) trick).
     * The old app never needed it; add it if a field module refuses to connect with "service discovery failed".
     */
    private fun openBluetooth(address: String): SerialLink {
        val adapter = bluetooth ?: throw IOException("This tablet has no Bluetooth")
        if (!hasBluetoothPermission()) throw IOException("Allow \"Nearby devices\" for KFT GCS (Android settings → Apps), then connect again")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off: turn it on to connect")
        try {
            val socket = adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(SPP_UUID)
            try {
                socket.connect()
            } catch (e: java.io.IOException) {
                socket.close()
                throw IOException("Couldn't reach Bluetooth $address: is it on, in range, and not connected to another device? (${e.message})")
            }
            return StreamSerialLink(socket.inputStream, socket.outputStream, socket)
        } catch (e: SecurityException) {
            throw IOException("Bluetooth permission missing: allow \"Nearby devices\" for KFT GCS")
        }
    }

    /** Android 12+ asks at run time (BLUETOOTH_CONNECT); before that the install-time BLUETOOTH permission covers it. */
    private fun hasBluetoothPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

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

private class UsbSerialLink(private val port: UsbSerialPort, private val readTimeoutMs: Int) : SerialLink {
    override fun read(buffer: ByteArray): Int = port.read(buffer, readTimeoutMs)

    override fun write(bytes: ByteArray) = port.write(bytes, WRITE_TIMEOUT_MS)

    override fun close() = port.close()

    private companion object {
        const val WRITE_TIMEOUT_MS = 1_000
    }
}

/** The Bluetooth Serial Port Profile's service UUID (Bluetooth SIG assigned number 0x1101 on the base UUID). */
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
