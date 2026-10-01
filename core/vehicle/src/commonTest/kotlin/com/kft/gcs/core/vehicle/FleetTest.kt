package com.kft.gcs.core.vehicle

import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.common.CommandLong
import com.divpundir.mavlink.definitions.common.MavMissionResult
import com.divpundir.mavlink.definitions.common.MissionAck
import com.divpundir.mavlink.definitions.common.MissionClearAll
import com.divpundir.mavlink.definitions.common.RequestDataStream
import com.divpundir.mavlink.definitions.common.VfrHud
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.LinkStats
import com.kft.gcs.core.mavlink.MavLink
import com.kft.gcs.core.mavlink.VehicleId
import com.kft.gcs.core.mavlink.VehicleInfo
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.mavlink.VehicleRecorder
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A scripted link whose vehicles are [FakeFc]s, all answering on the link's one [frames] flow. [senderFor] hands out
 * the fake for that system id, the way the real gateway hands out a sender bound to one vehicle (the gateway's own
 * checks are tested in `MavTxGatewayTest`).
 */
private class FakeLink(override val id: Int, vararg systemIds: Int, reply: (MavMessage<*>) -> List<MavMessage<*>>) : MavLink {
    override val state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val frames = MutableSharedFlow<MavFrame<out MavMessage<*>>>(extraBufferCapacity = 256)
    val fcs = systemIds.map { FakeFc(it.toUByte(), frames = frames).also { fc -> fc.reply = reply } }
    override fun senderFor(systemId: UByte) = fcs.first { it.systemId == systemId }

    /** The heartbeats of these system ids are being heard. */
    fun hears(vararg systemIds: Int, duplicate: Boolean = false) {
        val vehicles = systemIds.map { VehicleInfo(it.toUByte(), 1u, VehicleKind.COPTER, armed = false, customMode = 0u, duplicateId = duplicate) }
        state.value = LinkState.Connected(LinkConfig.UdpListen(), vehicles.firstOrNull(), LinkStats(), vehicles)
    }
}

class FleetTest {
    /** A link whose fake FCs ACK commands and accept MISSION_CLEAR_ALL, like ArduPilot. */
    private fun link(id: Int, vararg systemIds: Int) = FakeLink(id, *systemIds) { m ->
        when (m) {
            is CommandLong -> listOf(ackFor(m))
            is MissionClearAll -> listOf(MissionAck(type = MavEnumValue.of(MavMissionResult.MAV_MISSION_ACCEPTED)))
            else -> emptyList()
        }
    }

    private val sync = MissionSync()
    private val links = MutableStateFlow<List<MavLink>>(emptyList())

    private fun TestScope.fleet(recorder: VehicleRecorder? = null) =
        Fleet(backgroundScope, links, loginKey = null, sync, recorder, testScheduler.timeSource).also { runCurrent() }

    @Test
    fun eachVehicleOnALinkGetsItsOwnStateAndItsOwnRequests() = runTest {
        val link = link(1, 1, 2)
        val (fc1, fc2) = link.fcs
        links.value = listOf(link)
        val fleet = fleet()
        link.hears(1, 2)
        runCurrent()

        assertEquals(listOf(VehicleId(1, 1u), VehicleId(1, 2u)), fleet.vehicles.value.map { it.id })
        assertEquals(listOf(0, 1), fleet.vehicles.value.map { it.slot })
        assertTrue(fleet.vehicles.value.all { it.state.connected })
        // The connect-time stream request went to each vehicle once, addressed to that vehicle.
        assertEquals(listOf<UByte>(1u), fc1.sentOf<RequestDataStream>().map { it.targetSystem })
        assertEquals(listOf<UByte>(2u), fc2.sentOf<RequestDataStream>().map { it.targetSystem })

        // Telemetry from vehicle 2 changes vehicle 2 only.
        fc2.emit(VfrHud(groundspeed = 7f))
        runCurrent()
        assertEquals(listOf(null, 7.0), fleet.vehicles.value.map { it.state.groundspeedMs })
    }

    /** Two radios, both drones still on the factory system id 1: two vehicles, not one (ADR-005 §1). */
    @Test
    fun theSameSystemIdOnTwoLinksIsTwoVehicles() = runTest {
        val (linkA, linkB) = link(1, 1) to link(2, 1)
        links.value = listOf(linkA, linkB)
        val fleet = fleet()
        linkA.hears(1)
        linkB.hears(1)
        runCurrent()
        assertEquals(listOf(VehicleId(1, 1u), VehicleId(2, 1u)), fleet.vehicles.value.map { it.id })

        linkB.fcs.single().emit(VfrHud(groundspeed = 12f))
        runCurrent()
        assertEquals(listOf(null, 12.0), fleet.vehicles.value.map { it.state.groundspeedMs }, "link 2's telemetry isn't link 1's")
    }

    @Test
    fun theFirstVehicleIsSelectedAndTransfersGoToTheSelectedOne() = runTest {
        val link = link(1, 1, 2)
        val (fc1, fc2) = link.fcs
        links.value = listOf(link)
        val fleet = fleet()
        assertContains(fleet.missions.clear().exceptionOrNull()!!.message!!, "No vehicle", message = "no vehicle yet")
        link.hears(1, 2)
        runCurrent()
        assertEquals(VehicleId(1, 1u), fleet.selectedId.value)

        fleet.select(VehicleId(1, 2u))
        fc2.emit(VfrHud(groundspeed = 3f))
        runCurrent()
        assertEquals(3.0, fleet.selected.value.groundspeedMs, "the selected state is vehicle 2's")
        assertTrue(fleet.missions.clear().isSuccess)
        assertEquals(listOf<UByte>(2u), fc2.sentOf<MissionClearAll>().map { it.targetSystem })
        assertTrue(fc1.sentOf<MissionClearAll>().isEmpty(), "vehicle 1 was not selected: nothing may be sent to it")
        assertEquals(mapOf(VehicleId(1, 2u) to emptyList()), sync.state.value.held)

        fleet.select(VehicleId(9, 9u)) // not a vehicle: ignored
        assertEquals(VehicleId(1, 2u), fleet.selectedId.value)
    }

    /** A job sends each mission to its own vehicle, whichever one is selected. */
    @Test
    fun missionsOfReachesANamedVehicleWithoutSelectingIt() = runTest {
        val link = link(1, 1, 2)
        val (fc1, fc2) = link.fcs
        links.value = listOf(link)
        val fleet = fleet()
        link.hears(1, 2)
        runCurrent()
        assertTrue(fleet.missionsOf(VehicleId(1, 2u)).clear().isSuccess)
        assertEquals(VehicleId(1, 1u), fleet.selectedId.value, "the selection is untouched")
        assertEquals(1, fc2.sentOf<MissionClearAll>().size)
        assertTrue(fc1.sentOf<MissionClearAll>().isEmpty())
        assertEquals(setOf(VehicleId(1, 2u)), sync.state.value.held.keys)
        assertContains(fleet.missionsOf(VehicleId(1, 7u)).clear().exceptionOrNull()!!.message!!, "Vehicle 7 is not connected")
    }

    /** The operator selects vehicle 2 while vehicle 1's transfer is running: the result is still recorded for vehicle 1. */
    @Test
    fun aTransferStaysWithTheVehicleItStartedOn() = runTest {
        val link = link(1, 1, 2)
        val (fc1, fc2) = link.fcs
        links.value = listOf(link)
        val fleet = fleet()
        link.hears(1, 2)
        runCurrent()
        val ack = fc1.reply
        fc1.reply = { m ->
            if (m is MissionClearAll) fleet.select(VehicleId(1, 2u)) // the selection changes mid-transfer
            ack(m)
        }
        assertTrue(fleet.missions.clear().isSuccess)
        assertEquals(VehicleId(1, 2u), fleet.selectedId.value)
        assertEquals(setOf(VehicleId(1, 1u)), sync.state.value.held.keys, "recorded for vehicle 1, where it went")
        assertNull(sync.state.value.onVehicle, "nothing is known about vehicle 2's mission")
        assertTrue(fc2.sentOf<MissionClearAll>().isEmpty())
    }

    @Test
    fun closingALinkForgetsItsVehiclesAndMovesTheSelection() = runTest {
        val (linkA, linkB) = link(1, 1) to link(2, 1)
        links.value = listOf(linkA, linkB)
        val fleet = fleet()
        linkA.hears(1)
        linkB.hears(1)
        runCurrent()
        assertEquals(VehicleId(1, 1u), fleet.selectedId.value)

        links.value = listOf(linkB) // link 1 disconnected
        runCurrent()
        assertEquals(listOf(VehicleId(2, 1u)), fleet.vehicles.value.map { it.id })
        assertEquals(VehicleId(2, 1u), fleet.selectedId.value)
        assertEquals(VehicleId(2, 1u), sync.state.value.selected)
        assertEquals(1, fleet.vehicles.value.single().slot, "it keeps its colour")

        links.value = emptyList()
        runCurrent()
        assertNull(fleet.selectedId.value)
        assertEquals(VehicleState(), fleet.selected.value)
    }

    /** A vehicle whose heartbeat stops stays in the list, shown as not connected, until its link is closed. */
    @Test
    fun aSilentVehicleStaysListedAsNotConnected() = runTest {
        val link = link(1, 1, 2)
        links.value = listOf(link)
        val fleet = fleet()
        link.hears(1, 2)
        runCurrent()
        link.hears(1) // vehicle 2 timed out on the link
        runCurrent()
        assertEquals(listOf(true, false), fleet.vehicles.value.map { it.state.connected })
    }

    @Test
    fun aDuplicatedSystemIdIsReported() = runTest {
        val link = link(1, 1)
        links.value = listOf(link)
        val fleet = fleet()
        link.hears(1, duplicate = true)
        runCurrent()
        assertTrue(fleet.vehicles.value.single().duplicateId)
    }

    @Test
    fun eachVehicleIsRecordedWithOnlyItsOwnFrames() = runTest {
        val link = link(1, 1, 2)
        val (fc1, fc2) = link.fcs
        links.value = listOf(link)
        val recorded = mutableMapOf<VehicleId, MutableList<UByte>>()
        fleet { vehicle, frames -> frames.collect { recorded.getOrPut(vehicle) { mutableListOf() } += it.systemId } }
        link.hears(1, 2)
        runCurrent()
        fc1.emit(VfrHud())
        fc2.emit(VfrHud())
        fc2.emit(VfrHud())
        runCurrent()
        // Each also holds the COMMAND_ACKs to its connect-time requests; what matters is whose frames they are.
        assertTrue(recorded.getValue(VehicleId(1, 1u)).all { it == 1u.toUByte() })
        assertTrue(recorded.getValue(VehicleId(1, 2u)).all { it == 2u.toUByte() })
        assertEquals(setOf(VehicleId(1, 1u), VehicleId(1, 2u)), recorded.keys)
    }
}
