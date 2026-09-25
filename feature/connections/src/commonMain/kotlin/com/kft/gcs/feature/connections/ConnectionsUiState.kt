package com.kft.gcs.feature.connections

import androidx.compose.runtime.Immutable
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.SerialPortInfo
import com.kft.gcs.core.mavlink.VehicleInfo
import com.kft.gcs.core.mavlink.VehicleKind
import kotlin.math.roundToInt

/** Everything the Connections screen draws. Built only by [buildUiState], so the screen never computes anything. */
@Immutable
data class ConnectionsUiState(
    val link: LinkStatusUi,
    val profiles: List<ProfileRow>,
    val form: ProfileForm,
    /** True while a link is open or being opened, i.e. there is something to disconnect. */
    val canDisconnect: Boolean,
    /** Ports offered by the Serial form, from the last refresh. */
    val serialPorts: List<SerialPortInfo>,
)

/** The status card at the top. [tone] picks the colour; the screen decides which colour each tone is. */
data class LinkStatusUi(val headline: String, val detail: String?, val tone: Tone)

enum class Tone { IDLE, BUSY, OK, WARNING }

data class ProfileRow(val id: String, val name: String, val summary: String, val isActive: Boolean)

enum class LinkKind(val label: String, val defaultPort: Int) {
    UDP_LISTEN("UDP listen", 14550),
    UDP_CLIENT("UDP client", 14550),
    TCP("TCP client", 5760),
    SERIAL("Serial", 0), // no network port: a serial profile picks a device and a baud rate instead
}

/** The "new profile" form. Fields are raw text so the user can type freely; [error] shows why Save was refused. */
data class ProfileForm(
    val kind: LinkKind = LinkKind.UDP_LISTEN,
    val name: String = "",
    val host: String = "",
    val port: String = LinkKind.UDP_LISTEN.defaultPort.toString(),
    /** Serial only: the chosen device ([SerialPortInfo.name]) and baud rate. */
    val serialPort: String = "",
    val baud: Int = 57600,
    val error: String? = null,
) {
    val needsHost: Boolean get() = kind == LinkKind.UDP_CLIENT || kind == LinkKind.TCP
    val isSerial: Boolean get() = kind == LinkKind.SERIAL
}

/** Pure: repository data in, screen state out. Tested directly, no ViewModel needed. */
fun buildUiState(profiles: List<ConnectionProfile>, link: LinkState, form: ProfileForm, serialPorts: List<SerialPortInfo>): ConnectionsUiState {
    val activeConfig = when (link) {
        LinkState.Disconnected -> null
        is LinkState.Connecting -> link.config
        is LinkState.Connected -> link.config
    }
    return ConnectionsUiState(
        link = link.toStatusUi(),
        profiles = profiles.map { ProfileRow(it.id, it.name, it.config.summary, isActive = it.config == activeConfig) },
        form = form,
        canDisconnect = activeConfig != null,
        serialPorts = serialPorts,
    )
}

fun LinkState.toStatusUi(): LinkStatusUi = when (this) {
    LinkState.Disconnected -> LinkStatusUi("Not connected", null, Tone.IDLE)
    is LinkState.Connecting ->
        if (attempt == 1) LinkStatusUi("Connecting to ${config.summary}…", null, Tone.BUSY)
        else LinkStatusUi("Reconnecting to ${config.summary} (attempt $attempt)", lastError, Tone.WARNING)
    is LinkState.Connected -> {
        val traffic = "${stats.messagesPerSecond} msg/s · ${(stats.lossPercent * 10).roundToInt() / 10.0}% loss"
        val v = vehicle // local copy: smart casts don't work on another module's public property
        if (v == null) LinkStatusUi("Link open on ${config.summary}", "Waiting for a vehicle heartbeat · $traffic", Tone.BUSY)
        else LinkStatusUi(v.describe(), traffic, Tone.OK)
    }
}

/** e.g. "ArduCopter · system 1 · disarmed". */
fun VehicleInfo.describe(): String {
    val firmware = when (kind) {
        VehicleKind.COPTER -> "ArduCopter"
        VehicleKind.PLANE -> "ArduPlane"
        VehicleKind.UNKNOWN -> "ArduPilot (unknown type)"
    }
    return "$firmware · system $systemId · ${if (armed) "ARMED" else "disarmed"}"
}

/** Checks the form and builds the config, or returns the message to show. Kept apart from the ViewModel so it's easy to test. */
fun ProfileForm.toConfigOrError(): Result<LinkConfig> {
    if (isSerial) {
        return if (serialPort.isBlank()) Result.failure(IllegalArgumentException("Pick a serial port (plug it in, then Refresh)"))
        else Result.success(LinkConfig.Serial(serialPort, baud))
    }
    val portNumber = port.trim().toIntOrNull()
    return when {
        portNumber == null || portNumber !in 1..65535 -> Result.failure(IllegalArgumentException("Port must be a number from 1 to 65535"))
        needsHost && host.isBlank() -> Result.failure(IllegalArgumentException("Enter a host, e.g. 192.168.1.10"))
        else -> Result.success(
            when (kind) {
                LinkKind.UDP_LISTEN -> LinkConfig.UdpListen(portNumber)
                LinkKind.UDP_CLIENT -> LinkConfig.UdpClient(host.trim(), portNumber)
                LinkKind.TCP -> LinkConfig.TcpClient(host.trim(), portNumber)
                LinkKind.SERIAL -> error("handled above")
            },
        )
    }
}
