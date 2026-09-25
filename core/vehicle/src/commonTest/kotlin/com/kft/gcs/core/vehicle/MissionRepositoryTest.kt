package com.kft.gcs.core.vehicle

import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.LinkStats
import com.kft.gcs.core.mavlink.VehicleInfo
import com.kft.gcs.core.mavlink.VehicleKind
import com.divpundir.mavlink.definitions.common.MissionCount
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/** The repository's own job: refuse to start without a vehicle or a home, and address the vehicle on the link. */
class MissionRepositoryTest {
    private val fc = FakeFc(systemId = 7u, componentId = 1u)
    private val link = MutableStateFlow<LinkState>(LinkState.Disconnected)
    private val vehicle = MutableStateFlow(VehicleState())
    private val repo = DefaultMissionRepository(fc.frames, link, vehicle, fc)
    private val waypoint = MissionItem(MissionCommand.WAYPOINT, LatLon(-35.36, 149.16), 30.0)

    @Test
    fun noVehicleNoTransfer() = runTest {
        assertContains(repo.upload(listOf(waypoint)).exceptionOrNull()!!.message!!, "No vehicle")
        assertTrue(repo.download().isFailure)
        assertTrue(fc.sent.isEmpty())
    }

    @Test
    fun noHomeNoUpload() = runTest {
        link.value = LinkState.Connected(LinkConfig.UdpListen(), VehicleInfo(7u, 1u, VehicleKind.COPTER, false, 0u), LinkStats())
        assertContains(repo.upload(listOf(waypoint)).exceptionOrNull()!!.message!!, "Home isn't known")
        assertTrue(fc.sent.isEmpty(), "without home there is no valid seq 0, so nothing is sent (S11)")
    }

    @Test
    fun uploadIsAddressedToTheLinkedVehicleWithHomeFirst() = runTest {
        link.value = LinkState.Connected(LinkConfig.UdpListen(), VehicleInfo(7u, 1u, VehicleKind.COPTER, false, 0u), LinkStats())
        vehicle.value = VehicleState(home = Home(LatLon(-35.363261, 149.165230), 584.0))
        repo.upload(listOf(waypoint)) // the fake stays silent, so this times out; we only check what was sent
        val count = fc.sentOf<MissionCount>().first()
        assertEquals(7.toUByte(), count.targetSystem)
        assertEquals(2, count.count.toInt(), "home + 1 item")
    }
}
