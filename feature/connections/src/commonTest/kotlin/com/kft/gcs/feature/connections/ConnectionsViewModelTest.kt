package com.kft.gcs.feature.connections

import app.cash.turbine.test
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.LinkStats
import com.kft.gcs.core.mavlink.SerialPortInfo
import com.kft.gcs.core.mavlink.VehicleId
import com.kft.gcs.core.mavlink.VehicleInfo
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.mavlink.signing.SigningStatus
import com.kft.gcs.core.vehicle.KftLoginStatus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** A repository whose link state the test sets by hand. Records what the ViewModel asked for. */
internal class FakeConnectionsRepository : ConnectionsRepository {
    override val profiles = MutableStateFlow(listOf(ConnectionProfile("p0", "SITL", LinkConfig.UdpListen(14550))))
    /** The one link most tests use, as link 1; [LinkState.Disconnected] = no link open. */
    val linkState = MutableStateFlow<LinkState>(LinkState.Disconnected)

    /** A second open link (link 2), for the multi-link tests. */
    val secondLink = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val links = combine(linkState, secondLink) { a, b -> listOf(OpenLink(1, a), OpenLink(2, b)).filter { it.state != LinkState.Disconnected } }

    /** The login of system 1 on link 1. */
    val login = MutableStateFlow<KftLoginStatus?>(null)
    override val logins = login.map { mapOf(VehicleId(1, 1u) to it) }
    val connected = mutableListOf<String>()
    val disconnected = mutableListOf<Int>()

    override fun addProfile(name: String, config: LinkConfig) =
        profiles.update { it + ConnectionProfile("p${it.size}", name, config) }
    override fun deleteProfile(id: String) = profiles.update { list -> list.filterNot { it.id == id } }
    override fun connect(profileId: String) { connected += profileId }
    val connectedTo = mutableListOf<LinkConfig>()
    override fun connectTo(config: LinkConfig) { connectedTo += config }
    override fun disconnect(linkId: Int) { disconnected += linkId }

    var ports = listOf(SerialPortInfo("COM7", "Silicon Labs CP210x"))
    override fun serialPorts() = ports
}

class ConnectionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeConnectionsRepository()
    private val udp = LinkConfig.UdpListen(14550)
    private val copter = VehicleInfo(systemId = 1u, componentId = 1u, kind = VehicleKind.COPTER, armed = false, customMode = 0u)

    // viewModelScope runs on Dispatchers.Main, which doesn't exist in a unit test; point it at the test dispatcher.
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** S12: the Links card says where the KFT login is, and flags the states the operator must fix. */
    @Test
    fun linkCardShowsTheKftLogin() = runTest(dispatcher) {
        val vm = ConnectionsViewModel(repository)
        vm.state.test {
            repository.linkState.value = LinkState.Connected(udp, copter, LinkStats())
            repository.login.value = KftLoginStatus.LOGGING_IN
            runCurrent()
            assertEquals("KFT login: in progress…", expectMostRecentItem().links.single().login)
            repository.login.value = KftLoginStatus.NO_KEY
            runCurrent()
            val noKey = expectMostRecentItem().links.single()
            assertEquals("KFT login: no key configured", noKey.login)
            assertTrue(noKey.loginWarning)
            repository.login.value = KftLoginStatus.AUTHENTICATED
            runCurrent()
            assertFalse(expectMostRecentItem().links.single().loginWarning)
            // No vehicle heard: no login line (it would describe a vehicle that isn't there).
            repository.linkState.value = LinkState.Connected(udp, null, LinkStats())
            runCurrent()
            assertEquals(null, expectMostRecentItem().links.single().login)
        }
    }

    /** ADR-005: two links open at once, each with its own card and Disconnect; one of them carries two vehicles. */
    @Test
    fun everyOpenLinkHasItsOwnCardAndAManyVehicleLinkListsThem() = runTest(dispatcher) {
        val tcp = LinkConfig.TcpClient("127.0.0.1", 5772)
        repository.profiles.value += ConnectionProfile("p1", "Second", tcp)
        val vm = ConnectionsViewModel(repository)
        vm.state.test {
            repository.linkState.value = LinkState.Connected(udp, copter, LinkStats(), listOf(copter, copter.copy(systemId = 2u, armed = true)))
            repository.secondLink.value = LinkState.Connecting(tcp, attempt = 1, lastError = null)
            repository.login.value = KftLoginStatus.AUTHENTICATED
            runCurrent()
            val state = expectMostRecentItem()
            assertEquals(listOf(1, 2), state.links.map { it.linkId })
            val (first, second) = state.links
            assertEquals("2 vehicles on UDP listen :14550", first.headline)
            assertEquals(
                listOf(
                    VehicleLineUi("ArduCopter · system 1 · disarmed", "KFT login: OK", loginWarning = false, signing = SigningStatus.Unsigned),
                    VehicleLineUi("ArduCopter · system 2 · ARMED", null, loginWarning = false, signing = SigningStatus.Unsigned), // no login known for system 2 yet
                ),
                first.vehicles,
            )
            assertEquals("Connecting to TCP 127.0.0.1:5772…", second.headline)
            assertEquals(listOf(true, true), state.profiles.map { it.isActive }, "both profiles are open")
        }
        vm.onDisconnectClicked(2)
        assertEquals(listOf(2), repository.disconnected)
    }

    /** PRD FR-8: a single vehicle's card carries how it signs, so the badge sits beside its login. */
    @Test
    fun aSingleVehicleCardShowsHowItSigns() {
        val signed = copter.copy(signing = SigningStatus.Signed("a".repeat(24)))
        assertEquals(SigningStatus.Signed("a".repeat(24)), LinkState.Connected(udp, signed, LinkStats()).toStatusUi(linkId = 1).signing)
        assertEquals(SigningStatus.Unsigned, LinkState.Connected(udp, copter, LinkStats()).toStatusUi(linkId = 1).signing)
    }

    /**
     * A vehicle that signs, with no key here or keys locked, never gets a heartbeat through, so it is not in `vehicles`.
     * The card must still say it is out there and why it cannot be reached, as a warning.
     */
    @Test
    fun aSigningVehicleWeCannotReadStillShowsOnItsLink() {
        val blocked = mapOf(5.toUByte() to SigningStatus.Locked)

        val alone = LinkState.Connected(udp, null, LinkStats(), emptyList(), blocked).toStatusUi(linkId = 1)
        assertEquals(Tone.WARNING, alone.tone)
        assertEquals(listOf(VehicleLineUi("System 5 · signs its MAVLink", null, false, SigningStatus.Locked)), alone.vehicles)

        val beside = LinkState.Connected(udp, copter, LinkStats(), listOf(copter), mapOf(5.toUByte() to SigningStatus.NoKey)).toStatusUi(linkId = 1)
        assertEquals(Tone.OK, beside.tone, "the readable vehicle keeps the card healthy")
        assertEquals(SigningStatus.NoKey, beside.vehicles.single().signing)
    }

    /** ADR-005 §4: a duplicated system id is a warning that says what to do, not a vehicle. */
    @Test
    fun aDuplicateSystemIdIsShownAsAnError() {
        val ui = LinkState.Connected(udp, copter.copy(duplicateId = true), LinkStats()).toStatusUi(linkId = 1)
        assertEquals("Two vehicles are using system id 1 on UDP listen :14550", ui.headline)
        assertEquals(Tone.WARNING, ui.tone)
        assertTrue(ui.detail!!.contains("MAV_SYSID"))
    }

    @Test
    fun stateFollowsTheLinkFromIdleToVehicleFound() = runTest(dispatcher) {
        val vm = ConnectionsViewModel(repository)
        vm.state.test {
            runCurrent()
            val idle = expectMostRecentItem().links.single()
            assertEquals("Not connected", idle.headline)
            assertEquals(null, idle.linkId, "nothing to disconnect")

            repository.linkState.value = LinkState.Connecting(udp, attempt = 1, lastError = null)
            runCurrent()
            val connecting = expectMostRecentItem()
            assertEquals(Tone.BUSY, connecting.links.single().tone)
            assertTrue(connecting.profiles.single().isActive)
            assertEquals(1, connecting.links.single().linkId, "the card can disconnect link 1")

            repository.linkState.value = LinkState.Connected(udp, copter, LinkStats(messagesPerSecond = 42, lossPercent = 1.25))
            runCurrent()
            val connected = expectMostRecentItem().links.single()
            assertEquals("ArduCopter · system 1 · disarmed", connected.headline)
            assertEquals("42 msg/s · 1.3% loss", connected.detail)
            assertEquals(Tone.OK, connected.tone)
        }
    }

    @Test
    fun clicksGoToTheRepository() = runTest(dispatcher) {
        val vm = ConnectionsViewModel(repository)
        vm.onConnectClicked("p0")
        vm.onDisconnectClicked(1)
        assertEquals(listOf("p0"), repository.connected)
        assertEquals(listOf(1), repository.disconnected)
    }

    /** Serial: the first free port is preselected, and Connect opens it at the chosen baud with no profile saved. */
    @Test
    fun serialConnectOpensThePreselectedPortDirectly() = runTest(dispatcher) {
        val vm = ConnectionsViewModel(repository)
        vm.onFormKindChanged(LinkKind.SERIAL)
        vm.onFormBaudChanged(115200)
        vm.onConnectSerialClicked()
        assertEquals(listOf<LinkConfig>(LinkConfig.Serial("COM7", 115200)), repository.connectedTo)
        assertEquals(1, repository.profiles.value.size)
    }

    @Test
    fun serialConnectWithNoPortShowsAnErrorAndDoesNotConnect() = runTest(dispatcher) {
        repository.ports = emptyList()
        val vm = ConnectionsViewModel(repository)
        vm.onFormKindChanged(LinkKind.SERIAL)
        backgroundScope.launch { vm.state.collect {} }
        vm.onConnectSerialClicked()
        runCurrent()
        assertTrue(repository.connectedTo.isEmpty())
        assertEquals("Pick a serial port (plug it in, then Refresh)", vm.state.value.form.error)
    }

    @Test
    fun savingAValidFormAddsAProfileAndClearsTheForm() = runTest(dispatcher) {
        val vm = ConnectionsViewModel(repository)
        vm.state.test {
            skipItems(1)
            vm.onFormKindChanged(LinkKind.TCP)
            assertEquals("5760", awaitItem().form.port, "switching kind fills in that kind's usual port")
            vm.onFormHostChanged("192.168.4.1")
            skipItems(1)
            vm.onSaveProfileClicked()
            val saved = awaitItem()
            assertEquals("TCP 192.168.4.1:5760", saved.profiles.last().name, "a blank name falls back to the summary")
            // Kind change and Save each rewrote the form, so the screen's fields reload: generation 0 → 1 → 2.
            assertEquals(ProfileForm(generation = 2), saved.form)
        }
    }

    @Test
    fun serialProfileNeedsAPortAndKeepsTheChosenBaud() = runTest(dispatcher) {
        val vm = ConnectionsViewModel(repository)
        backgroundScope.launch { vm.state.collect {} } // the screen's collector; values are read with runCurrent()
        runCurrent()
        assertEquals(listOf("COM7"), vm.state.value.serialPorts.map { it.name }, "ports listed at start")

        vm.onFormKindChanged(LinkKind.SERIAL)
        runCurrent()
        assertEquals("COM7", vm.state.value.form.serialPort, "the only free port is preselected")
        vm.onFormBaudChanged(115200)
        vm.onSaveProfileClicked()
        runCurrent()
        assertEquals(LinkConfig.Serial("COM7", 115200), repository.profiles.value.last().config)
        assertEquals("Serial COM7 @ 115200", vm.state.value.profiles.last().name)
    }

    @Test
    fun refreshListsNewlyPluggedPorts() = runTest(dispatcher) {
        val vm = ConnectionsViewModel(repository)
        vm.state.test {
            skipItems(1)
            repository.ports = repository.ports + SerialPortInfo("COM9", "FTDI")
            vm.onRefreshSerialPortsClicked()
            assertEquals(listOf("COM7", "COM9"), awaitItem().serialPorts.map { it.name })
        }
    }

    @Test
    fun invalidFormShowsAnErrorAndSavesNothing() = runTest(dispatcher) {
        val vm = ConnectionsViewModel(repository)
        vm.state.test {
            skipItems(1)
            vm.onFormPortChanged("70000")
            skipItems(1)
            vm.onSaveProfileClicked()
            assertEquals("Port must be a number from 1 to 65535", awaitItem().form.error)
            assertEquals(1, repository.profiles.value.size)
        }
    }

    @Test
    fun vehicleFoundAndLostAreOneShotMessages() = runTest(dispatcher) {
        val vm = ConnectionsViewModel(repository)
        runCurrent() // let init start watching; StateFlow keeps only the latest value, so steps must not blur together
        vm.effects.test {
            repository.linkState.value = LinkState.Connected(udp, null, LinkStats())
            runCurrent()
            repository.linkState.value = LinkState.Connected(udp, copter, LinkStats())
            assertEquals(ConnectionsEffect.ShowMessage("Vehicle found: ArduCopter · system 1 · disarmed"), awaitItem())
            // Arming changes the description but not "is there a vehicle", so no second message.
            repository.linkState.value = LinkState.Connected(udp, copter.copy(armed = true), LinkStats())
            runCurrent()
            repository.linkState.value = LinkState.Connected(udp, null, LinkStats())
            assertEquals(ConnectionsEffect.ShowMessage("Vehicle heartbeat lost: system 1"), awaitItem())
        }
    }

    @Test
    fun reconnectingShowsTheReasonAsAWarning() {
        val ui = LinkState.Connecting(udp, attempt = 3, lastError = "cable pulled").toStatusUi()
        assertEquals(LinkStatusUi("Reconnecting to UDP listen :14550 (attempt 3)", "cable pulled", Tone.WARNING), ui)
    }

    @Test
    fun udpListenNeedsNoHostButClientsDo() {
        assertFalse(ProfileForm(kind = LinkKind.UDP_LISTEN).needsHost)
        assertTrue(ProfileForm(kind = LinkKind.TCP, host = " ").toConfigOrError().isFailure)
        assertEquals(LinkConfig.UdpClient("10.0.0.2", 14550), ProfileForm(kind = LinkKind.UDP_CLIENT, host = " 10.0.0.2 ").toConfigOrError().getOrNull())
    }
}
