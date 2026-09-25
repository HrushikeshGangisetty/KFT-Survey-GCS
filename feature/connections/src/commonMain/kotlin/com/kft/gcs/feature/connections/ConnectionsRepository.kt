package com.kft.gcs.feature.connections

import com.kft.gcs.core.mavlink.ConnectionManager
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.SerialPortInfo
import com.kft.gcs.core.mavlink.SerialPorts
import com.kft.gcs.core.vehicle.KftLoginStatus
import com.kft.gcs.core.vehicle.VehicleState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A saved way of connecting, e.g. "SITL (UDP 14550)". */
data class ConnectionProfile(val id: String, val name: String, val config: LinkConfig)

/**
 * What the Connections screen needs from the data layer: saved profiles and the live link.
 * An interface so the ViewModel test can use a fake instead of real sockets.
 */
interface ConnectionsRepository {
    val profiles: StateFlow<List<ConnectionProfile>>
    val linkState: StateFlow<LinkState>

    /** The KFT login for the vehicle on the link (spec S12), null while none is heard. */
    val login: Flow<KftLoginStatus?>
    fun addProfile(name: String, config: LinkConfig)
    fun deleteProfile(id: String)
    fun connect(profileId: String)
    fun disconnect()

    /** Serial ports present right now (COM ports on desktop, USB-OTG devices on Android). */
    fun serialPorts(): List<SerialPortInfo>
}

/**
 * Where the saved profiles live, as one text blob. The app shells back it with a file in the app's data folder;
 * tests back it with a string. [read] returns null when nothing has been saved yet (first run).
 */
interface ProfileStore {
    fun read(): String?
    fun write(text: String)
}

/**
 * Profiles saved through [store] and reloaded at startup, link delegated to the app-wide [ConnectionManager],
 * ports listed by [SerialPorts], login status read from the vehicle state.
 */
class DefaultConnectionsRepository(
    private val manager: ConnectionManager,
    private val ports: SerialPorts,
    private val store: ProfileStore,
    vehicle: StateFlow<VehicleState>,
) : ConnectionsRepository {

    // Ids only tell rows apart while the app runs, so they're handed out fresh on every start and never saved.
    private var nextId = 0
    private val _profiles = MutableStateFlow(
        (store.read()?.let(::decodeProfiles) ?: DEFAULT_PROFILES).map { (name, config) -> profile(name, config) },
    )
    override val profiles: StateFlow<List<ConnectionProfile>> = _profiles.asStateFlow()
    override val linkState: StateFlow<LinkState> = manager.state
    override val login: Flow<KftLoginStatus?> = vehicle.map { it.login }.distinctUntilChanged()

    override fun addProfile(name: String, config: LinkConfig) = save { it + profile(name, config) }

    override fun deleteProfile(id: String) = save { list -> list.filterNot { it.id == id } }

    override fun connect(profileId: String) {
        profiles.value.firstOrNull { it.id == profileId }?.let { manager.connect(it.config) }
    }

    override fun disconnect() = manager.disconnect()

    override fun serialPorts() = ports.list()

    private fun profile(name: String, config: LinkConfig) = ConnectionProfile("p${nextId++}", name, config)

    /** Every change is written straight away: profiles change rarely, and a crash must not lose one. */
    private fun save(change: (List<ConnectionProfile>) -> List<ConnectionProfile>) {
        _profiles.update(change)
        store.write(encodeProfiles(_profiles.value.map { it.name to it.config }))
    }
}

/**
 * Offered on first run. SITL in WSL2 or MAVProxy sends to UDP 14550 (MAVProxy --out); TCP 5760 is SITL's direct
 * port. Once the user deletes them, they stay deleted: an empty saved list is still a saved list.
 */
internal val DEFAULT_PROFILES = listOf(
    "SITL (UDP 14550)" to LinkConfig.UdpListen(14550),
    "SITL (TCP 5760)" to LinkConfig.TcpClient("127.0.0.1", 5760),
)

/** On-disk shape of one profile. A separate class so the file format doesn't change if [ConnectionProfile] does. */
@Serializable
private data class SavedProfile(val name: String, val link: LinkConfig)

// encodeDefaults: write every field, including ones equal to a default (UdpListen's 14550). Otherwise changing a
// default in code would silently change what an existing saved profile connects to.
private val json = Json { prettyPrint = true; encodeDefaults = true }

internal fun encodeProfiles(profiles: List<Pair<String, LinkConfig>>): String =
    json.encodeToString(profiles.map { (name, config) -> SavedProfile(name, config) })

/**
 * Null when [text] isn't a valid profile file (hand-edited, truncated by a crash, or from a future version). The
 * caller then falls back to the defaults. The broken file is overwritten only when the user next changes a profile.
 */
internal fun decodeProfiles(text: String): List<Pair<String, LinkConfig>>? = try {
    json.decodeFromString<List<SavedProfile>>(text).map { it.name to it.link }
} catch (e: IllegalArgumentException) {
    // Covers both bad JSON (SerializationException is an IllegalArgumentException) and a LinkConfig init check.
    null
}
