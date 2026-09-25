package com.kft.gcs.feature.connections

import app.cash.turbine.test
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.LinkStats
import com.kft.gcs.core.mavlink.SerialPortInfo
import com.kft.gcs.core.mavlink.VehicleInfo
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.vehicle.KftLoginStatus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** A repository whose link state the test sets by hand. Records what the ViewModel asked for. */
private class FakeConnectionsRepository : ConnectionsRepository {
    override val profiles = MutableStateFlow(listOf(ConnectionProfile("p0", "SITL", LinkConfig.UdpListen(14550))))
    override val linkState = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val login = MutableStateFlow<KftLoginStatus?>(null)
    val connected = mutableListOf<String>()
    var disconnects = 0

    override fun addProfile(name: String, config: LinkConfig) =
        profiles.update { it + ConnectionProfile("p${it.size}", name, config) }
    override fun deleteProfile(id: String) = profiles.update { list -> list.filterNot { it.id == id } }
    override fun connect(profileId: String) { connected += profileId }
    override fun disconnect() { disconnects++ }

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
            assertEquals("KFT login: in progress…", expectMostRecentItem().link.login)
            repository.login.value = KftLoginStatus.NO_KEY
            runCurrent()
            val noKey = expectMostRecentItem().link
            assertEquals("KFT login: no key configured", noKey.login)
            assertTrue(noKey.loginWarning)
            repository.login.value = KftLoginStatus.AUTHENTICATED
            runCurrent()
            assertFalse(expectMostRecentItem().link.loginWarning)
            // No vehicle heard: no login line (it would describe a vehicle that isn't there).
            repository.linkState.value = LinkState.Connected(udp, null, LinkStats())
            runCurrent()
            assertEquals(null, expectMostRecentItem().link.login)
        }
    }

    @Test
    fun stateFollowsTheLinkFromIdleToVehicleFound() = runTest(dispatcher) {
        val vm = ConnectionsViewModel(repository)
        vm.state.test {
            assertEquals("Not connected", awaitItem().link.headline)

            repository.linkState.value = LinkState.Connecting(udp, attempt = 1, lastError = null)
            val connecting = awaitItem()
            assertEquals(Tone.BUSY, connecting.link.tone)
            assertTrue(connecting.profiles.single().isActive)
            assertTrue(connecting.canDisconnect)

            repository.linkState.value = LinkState.Connected(udp, copter, LinkStats(messagesPerSecond = 42, lossPercent = 1.25))
            val connected = awaitItem()
            assertEquals("ArduCopter · system 1 · disarmed", connected.link.headline)
            assertEquals("42 msg/s · 1.3% loss", connected.link.detail)
            assertEquals(Tone.OK, connected.link.tone)
        }
    }

    @Test
    fun clicksGoToTheRepository() = runTest(dispatcher) {
        val vm = ConnectionsViewModel(repository)
        vm.onConnectClicked("p0")
        vm.onDisconnectClicked()
        assertEquals(listOf("p0"), repository.connected)
        assertEquals(1, repository.disconnects)
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
            assertEquals(ProfileForm(), saved.form)
        }
    }

    @Test
    fun serialProfileNeedsAPortAndKeepsTheChosenBaud() = runTest(dispatcher) {
        val vm = ConnectionsViewModel(repository)
        backgroundScope.launch { vm.state.collect {} } // the screen's collector; values are read with runCurrent()
        runCurrent()
        assertEquals(listOf("COM7"), vm.state.value.serialPorts.map { it.name }, "ports listed at start")

        vm.onFormKindChanged(LinkKind.SERIAL)
        vm.onSaveProfileClicked()
        runCurrent()
        assertEquals("Pick a serial port (plug it in, then Refresh)", vm.state.value.form.error)

        vm.onFormSerialPortChanged("COM7")
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
            assertEquals(ConnectionsEffect.ShowMessage("Vehicle heartbeat lost"), awaitItem())
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
