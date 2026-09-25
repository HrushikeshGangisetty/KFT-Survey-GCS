package com.kft.gcs.feature.connections

import com.kft.gcs.core.mavlink.ConnectionManager
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.SerialPortInfo
import com.kft.gcs.core.mavlink.SerialPorts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A saved way of connecting, e.g. "SITL (UDP 14550)". */
data class ConnectionProfile(val id: String, val name: String, val config: LinkConfig)

/**
 * What the Connections screen needs from the data layer: saved profiles and the live link.
 * An interface so the ViewModel test can use a fake instead of real sockets.
 */
interface ConnectionsRepository {
    val profiles: StateFlow<List<ConnectionProfile>>
    val linkState: StateFlow<LinkState>
    fun addProfile(name: String, config: LinkConfig)
    fun deleteProfile(id: String)
    fun connect(profileId: String)
    fun disconnect()

    /** Serial ports present right now (COM ports on desktop, USB-OTG devices on Android). */
    fun serialPorts(): List<SerialPortInfo>
}

/** Profiles held in memory, link delegated to the app-wide [ConnectionManager], ports listed by [SerialPorts]. */
class DefaultConnectionsRepository(
    private val manager: ConnectionManager,
    private val ports: SerialPorts,
) : ConnectionsRepository {

    // ponytail: profiles are in memory only and reset on restart. Persist them with the settings storage pass.
    private var nextId = 0
    private val _profiles = MutableStateFlow(
        listOf(
            // SITL in WSL2 sends to the Windows host on UDP 14550 (MAVProxy --out); TCP 5760 is SITL's direct port.
            profile("SITL (UDP 14550)", LinkConfig.UdpListen(14550)),
            profile("SITL (TCP 5760)", LinkConfig.TcpClient("127.0.0.1", 5760)),
        ),
    )
    override val profiles: StateFlow<List<ConnectionProfile>> = _profiles.asStateFlow()
    override val linkState: StateFlow<LinkState> = manager.state

    override fun addProfile(name: String, config: LinkConfig) = _profiles.update { it + profile(name, config) }

    override fun deleteProfile(id: String) = _profiles.update { list -> list.filterNot { it.id == id } }

    override fun connect(profileId: String) {
        profiles.value.firstOrNull { it.id == profileId }?.let { manager.connect(it.config) }
    }

    override fun disconnect() = manager.disconnect()

    override fun serialPorts() = ports.list()

    private fun profile(name: String, config: LinkConfig) = ConnectionProfile("p${nextId++}", name, config)
}
