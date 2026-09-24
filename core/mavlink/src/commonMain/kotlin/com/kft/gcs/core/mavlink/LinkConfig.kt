package com.kft.gcs.core.mavlink

/**
 * How to reach a vehicle. Plain data, so the Connections screen can save it as a profile. The transport that
 * actually opens the link is created inside `core:mavlink` and is never handed out.
 */
sealed interface LinkConfig {
    /** One line for lists and status bars, e.g. "UDP :14550". */
    val summary: String

    /** Wait for the vehicle to send to us on [port], and reply to whoever last sent. SITL and most radios/bridges. */
    data class UdpListen(val port: Int = 14550) : LinkConfig {
        init { requirePort(port) }
        override val summary get() = "UDP listen :$port"
    }

    /** Send to [host]:[port] first. For vehicles or bridges that wait for the GCS to speak first. */
    data class UdpClient(val host: String, val port: Int) : LinkConfig {
        init { requirePort(port); require(host.isNotBlank()) { "host is blank" } }
        override val summary get() = "UDP $host:$port"
    }

    /** TCP client. SITL's default is port 5760. */
    data class TcpClient(val host: String, val port: Int = 5760) : LinkConfig {
        init { requirePort(port); require(host.isNotBlank()) { "host is blank" } }
        override val summary get() = "TCP $host:$port"
    }
}

private fun requirePort(port: Int) = require(port in 1..65535) { "port out of range: $port" }
