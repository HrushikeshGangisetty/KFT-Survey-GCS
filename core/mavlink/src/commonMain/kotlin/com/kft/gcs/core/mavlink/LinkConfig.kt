package com.kft.gcs.core.mavlink

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How to reach a vehicle. Plain data, so the Connections screen can save it as a profile. The transport that
 * actually opens the link is created inside `core:mavlink` and is never handed out.
 *
 * `@Serializable` so saved profiles survive a restart. The `@SerialName`s are the on-disk names: renaming a class
 * is safe, changing a SerialName breaks existing profile files. Loading runs the `init` checks, so a hand-edited
 * file with port 0 fails to load instead of creating a broken link.
 */
@Serializable
sealed interface LinkConfig {
    /** One line for lists and status bars, e.g. "UDP :14550". */
    val summary: String

    /** Wait for the vehicle to send to us on [port], and reply to whoever last sent. SITL and most radios/bridges. */
    @Serializable @SerialName("udp-listen")
    data class UdpListen(val port: Int = 14550) : LinkConfig {
        init { requirePort(port) }
        override val summary get() = "UDP listen :$port"
    }

    /** Send to [host]:[port] first. For vehicles or bridges that wait for the GCS to speak first. */
    @Serializable @SerialName("udp-client")
    data class UdpClient(val host: String, val port: Int) : LinkConfig {
        init { requirePort(port); require(host.isNotBlank()) { "host is blank" } }
        override val summary get() = "UDP $host:$port"
    }

    /** TCP client. SITL's default is port 5760. */
    @Serializable @SerialName("tcp-client")
    data class TcpClient(val host: String, val port: Int = 5760) : LinkConfig {
        init { requirePort(port); require(host.isNotBlank()) { "host is blank" } }
        override val summary get() = "TCP $host:$port"
    }

    /** A serial port: a telemetry radio or the flight controller's own USB. See [STANDARD_BAUD_RATES]. */
    @Serializable @SerialName("serial")
    data class Serial(val port: String, val baud: Int = 57600) : LinkConfig {
        init { require(port.isNotBlank()) { "port is blank" }; require(baud > 0) { "baud must be positive: $baud" } }
        override val summary get() = "Serial $port @ $baud"
    }
}

private fun requirePort(port: Int) = require(port in 1..65535) { "port out of range: $port" }
