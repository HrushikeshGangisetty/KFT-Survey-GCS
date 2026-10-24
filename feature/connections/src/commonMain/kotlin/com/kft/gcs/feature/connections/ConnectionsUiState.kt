package com.kft.gcs.feature.connections

import androidx.compose.runtime.Immutable
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.bluetoothAddressOf
import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.SerialPortInfo
import com.kft.gcs.core.mavlink.VehicleId
import com.kft.gcs.core.mavlink.VehicleInfo
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.mavlink.signing.SigningStatus
import com.kft.gcs.core.vehicle.KftLoginStatus
import kotlin.math.roundToInt

/** Everything the Connections screen draws. Built only by [buildUiState], so the screen never computes anything. */
@Immutable
data class ConnectionsUiState(
    /** One card per open link, oldest first; a single "Not connected" card when none is open. */
    val links: List<LinkStatusUi>,
    val profiles: List<ProfileRow>,
    val form: ProfileForm,
    /** Ports offered by the Serial form, from the last refresh. */
    val serialPorts: List<SerialPortInfo>,
)

/**
 * One link's status card. [tone] picks the colour; the screen decides which colour each tone is.
 * With one vehicle on the link, the card is about that vehicle: [headline] describes it and [login] is its KFT login
 * line (spec S12; [loginWarning] = the operator must act). With several, [headline] counts them and [vehicles] lists
 * each with its own login.
 * @property signing how the single vehicle signs its traffic (PRD FR-8); null on a card that isn't about one vehicle.
 * @property linkId the link to disconnect, or null on the "Not connected" card.
 */
data class LinkStatusUi(
    val headline: String,
    val detail: String?,
    val tone: Tone,
    val login: String? = null,
    val loginWarning: Boolean = false,
    val linkId: Int? = null,
    val vehicles: List<VehicleLineUi> = emptyList(),
    val signing: SigningStatus? = null,
)

/**
 * One vehicle on a link that carries several: "ArduCopter · system 2 · disarmed", its login line and how it signs.
 * Also used for a vehicle that signs but can't be talked to (no key, keys locked): it has no heartbeat we can read, so
 * [text] says only its system id.
 */
data class VehicleLineUi(val text: String, val login: String?, val loginWarning: Boolean, val signing: SigningStatus? = null)

enum class Tone { IDLE, BUSY, OK, WARNING }

data class ProfileRow(val id: String, val name: String, val summary: String, val isActive: Boolean)

enum class LinkKind(val label: String, val defaultPort: Int) {
    UDP_LISTEN("UDP listen", 14550),
    UDP_CLIENT("UDP client", 14550),
    TCP("TCP client", 5760),
    SERIAL("Serial", 0), // no network port: a serial profile picks a device and a baud rate instead
    T12("Skydroid T12", 0), // nothing to pick: the T12 is found by its USB IDs (LinkConfig.T12)
}

/**
 * The "new profile" form. Fields are raw text so the user can type freely; [error] shows why Save was refused.
 * @property generation goes up whenever the ViewModel itself rewrites the text (a new link kind resets the port, Save
 *   empties the form). The screen's text fields keep their own text and reload it from here only then.
 */
data class ProfileForm(
    val kind: LinkKind = LinkKind.UDP_LISTEN,
    val name: String = "",
    val host: String = "",
    val port: String = LinkKind.UDP_LISTEN.defaultPort.toString(),
    /** Serial only: the chosen device ([SerialPortInfo.name]) and baud rate. */
    val serialPort: String = "",
    val baud: Int = 57600,
    val error: String? = null,
    val generation: Int = 0,
) {
    val needsHost: Boolean get() = kind == LinkKind.UDP_CLIENT || kind == LinkKind.TCP
    val isSerial: Boolean get() = kind == LinkKind.SERIAL
    val isT12: Boolean get() = kind == LinkKind.T12
}

/** Pure: repository data in, screen state out. Tested directly, no ViewModel needed. */
fun buildUiState(
    profiles: List<ConnectionProfile>,
    links: List<OpenLink>,
    form: ProfileForm,
    serialPorts: List<SerialPortInfo>,
    logins: Map<VehicleId, KftLoginStatus?>,
): ConnectionsUiState {
    val openConfigs = links.mapNotNull { link ->
        when (val s = link.state) {
            LinkState.Disconnected -> null
            is LinkState.Connecting -> s.config
            is LinkState.Connected -> s.config
        }
    }
    return ConnectionsUiState(
        links = links.map { it.state.toStatusUi(it.id, logins) }.ifEmpty { listOf(LinkState.Disconnected.toStatusUi()) },
        profiles = profiles.map { ProfileRow(it.id, it.name, it.config.summary, isActive = it.config in openConfigs) },
        form = form,
        serialPorts = serialPorts,
    )
}

/** The card for one link. [linkId] and [logins] are the link's number and its vehicles' KFT logins, when it is open. */
fun LinkState.toStatusUi(linkId: Int? = null, logins: Map<VehicleId, KftLoginStatus?> = emptyMap()): LinkStatusUi = when (this) {
    LinkState.Disconnected -> LinkStatusUi("Not connected", null, Tone.IDLE)
    is LinkState.Connecting ->
        if (attempt == 1) LinkStatusUi("Connecting to ${config.summary}…", null, Tone.BUSY, linkId = linkId)
        else LinkStatusUi("Reconnecting to ${config.summary} (attempt $attempt)", lastError, Tone.WARNING, linkId = linkId)
    is LinkState.Connected -> {
        val traffic = "${stats.messagesPerSecond} msg/s · ${(stats.lossPercent * 10).roundToInt() / 10.0}% loss"
        fun login(v: VehicleInfo) = linkId?.let { logins[VehicleId(it, v.systemId)] }
        val duplicate = vehicles.firstOrNull { it.duplicateId }
        val only = vehicles.singleOrNull()
        // Vehicles that sign but can't be read (PRD FR-8): they have no heartbeat of ours, so the link would otherwise say nothing.
        val blocked = signingBlocked.map { (id, status) -> VehicleLineUi("System $id · signs its MAVLink", null, false, status) }
        when {
            // ADR-005 §4: said in words and as a warning, because nothing can be sent to that id until it's fixed.
            duplicate != null -> LinkStatusUi(
                "Two vehicles are using system id ${duplicate.systemId} on ${config.summary}",
                "Give each its own MAV_SYSID (SYSID_THISMAV on older firmware), then reconnect. Nothing is sent to that id until then.",
                Tone.WARNING, linkId = linkId,
            )
            vehicles.isEmpty() && blocked.isNotEmpty() -> LinkStatusUi(
                "Link open on ${config.summary}", "A vehicle here signs its MAVLink and can't be reached: add its key, or unlock the keys · $traffic",
                Tone.WARNING, linkId = linkId, vehicles = blocked,
            )
            vehicles.isEmpty() -> LinkStatusUi("Link open on ${config.summary}", "Waiting for a vehicle heartbeat · $traffic", Tone.BUSY, linkId = linkId)
            only != null -> login(only).let { l -> LinkStatusUi(only.describe(), traffic, Tone.OK, l?.label, l?.warning == true, linkId, blocked, only.signing) }
            else -> LinkStatusUi(
                "${vehicles.size} vehicles on ${config.summary}", traffic, Tone.OK, linkId = linkId,
                vehicles = vehicles.map { v -> login(v).let { l -> VehicleLineUi(v.describe(), l?.label, l?.warning == true, v.signing) } } + blocked,
            )
        }
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
    if (isT12) return Result.success(LinkConfig.T12)
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
                LinkKind.SERIAL, LinkKind.T12 -> error("handled above")
            },
        )
    }
}

/** A port chip's text: "COM9 · Silicon Labs CP210x", plus "· in use by Skydroid T12" when this app holds it. */
fun portLabel(port: SerialPortInfo): String =
    listOfNotNull(port.name, port.description.ifBlank { null }, port.inUseBy?.let { "in use by $it" }).joinToString(" · ")

/**
 * What to tell the operator about a Bluetooth port, or null for any other port.
 * - Android lists the paired device itself ("bt:…"): no baud rate to pick; the module's UART must match the flight
 *   controller's SERIALn_BAUD.
 * - Windows makes two COM ports for each paired SPP device: "Outgoing" (the PC calls the module: the one to use) and
 *   "Incoming" (waits to be called). jSerialComm names both the same, so the operator is pointed at where Windows
 *   says which is which.
 */
fun bluetoothNote(port: SerialPortInfo): String? = when {
    !port.bluetooth -> null
    bluetoothAddressOf(port.name) != null ->
        "Bluetooth: no baud rate to set here. The module's own baud rate must match the flight controller's SERIAL port (SERIALn_BAUD)."
    else ->
        "Bluetooth COM port: Windows makes two per paired module. Use the one marked \"Outgoing\" (Settings → Bluetooth & devices → Devices → " +
            "More Bluetooth settings → COM Ports). The baud rate here isn't used; the module's own setting must match the flight controller."
}
