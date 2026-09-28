package com.kft.gcs.core.mavlink

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okio.IOException

/**
 * Which serial ports this app has open, and who holds each one. Two users open serial ports: a Serial profile's link
 * ([SERIAL_OWNER]) and [T12Link] ([T12_OWNER]). Both claim the port here before opening it, so the second one is
 * refused with a clear message instead of opening the port twice.
 *
 * Why it matters: on Android, usb-serial-for-android force-claims the USB interface, so a second open silently takes
 * it from the first and both links die (the old KFT app hit this with the T12). On Windows the second open just fails
 * with "in use by another program", which doesn't say which program. The Links screen reads [holders] to show a port
 * as "in use by Skydroid T12" before anyone tries.
 *
 * Process-wide, because a port is: one table for the whole app.
 */
object PortClaims {
    private val held = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Port name → who holds it ([SERIAL_OWNER] or [T12_OWNER]). */
    val holders: StateFlow<Map<String, String>> = held.asStateFlow()

    /** Takes [port] for [owner], or throws [IOException] naming who has it. */
    internal fun claim(port: String, owner: String) {
        while (true) {
            val now = held.value
            now[port]?.let { throw IOException("$port is in use by $it") }
            if (held.compareAndSet(now, now + (port to owner))) return
        }
    }

    /** Gives [port] back, if [owner] is the one holding it. */
    internal fun release(port: String, owner: String) = held.update { if (it[port] == owner) it - port else it }
}

/** Who holds a port: a Serial profile's MAVLink link. */
const val SERIAL_OWNER = "a Serial link"

/** Who holds a port: the Skydroid T12's video and data link ([T12Link]). */
const val T12_OWNER = "Skydroid T12"

/** These ports with [SerialPortInfo.inUseBy] filled in from [PortClaims]. */
fun List<SerialPortInfo>.withHolders(): List<SerialPortInfo> {
    val holders = PortClaims.holders.value
    return map { it.copy(inUseBy = holders[it.name]) }
}

/**
 * Claims [port] for [owner], then opens it with [open]. The returned link gives the claim back when it's closed (and
 * so does a failed open), so a port is never left marked as held.
 */
internal fun claimed(port: String, owner: String, open: () -> SerialLink): SerialLink {
    PortClaims.claim(port, owner)
    val link = try {
        open()
    } catch (e: Throwable) {
        PortClaims.release(port, owner)
        throw e
    }
    return object : SerialLink by link {
        override fun close() {
            try {
                link.close()
            } finally {
                PortClaims.release(port, owner)
            }
        }
    }
}
