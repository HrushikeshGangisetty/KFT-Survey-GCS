@file:Suppress("DEPRECATION") // MissionRequest: ArduPilot's upload request, see MissionProtocol

package com.kft.gcs.core.vehicle

import com.kft.gcs.core.mission.Home
import com.kft.gcs.core.mission.Mission
import com.kft.gcs.core.mission.MissionCommand
import com.kft.gcs.core.mission.MissionItem
import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.common.MavMissionResult
import com.divpundir.mavlink.definitions.common.MissionAck
import com.divpundir.mavlink.definitions.common.MissionClearAll
import com.divpundir.mavlink.definitions.common.MissionCount
import com.divpundir.mavlink.definitions.common.MissionItemInt
import com.divpundir.mavlink.definitions.common.MissionRequest
import com.divpundir.mavlink.definitions.common.MissionRequestInt
import com.divpundir.mavlink.definitions.common.MissionRequestList
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.MavTxGateway
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The mission protocol against a fake that behaves like ArduPilot's MissionItemProtocol, in virtual time. */
class MissionProtocolTest {
    private val fc = FakeFc()
    private val ap = ArduPilotMissionFake().also { ap -> fc.reply = ap::reply }
    private val protocol = MissionProtocol(fc.frames, fc)
    private val target = MissionProtocol.Target(1u, 1u)

    private val home = Home(LatLon(-35.363261, 149.165230), 584.0)
    private val plan = listOf(
        MissionItem(MissionCommand.TAKEOFF, altitudeM = 20.0),
        MissionItem(MissionCommand.WAYPOINT, LatLon(-35.3620, 149.1660), 30.0),
        MissionItem(MissionCommand.WAYPOINT, LatLon(-35.3640, 149.1670), 30.0),
    )
    private val wire = missionToWire(home, plan, 1u, 1u)

    @Test
    fun uploadAnsweringArduPilotsDeprecatedMissionRequest() = runTest {
        val progress = mutableListOf<Int>()
        assertTrue(protocol.upload(target, wire) { done, _ -> progress += done }.isSuccess)
        assertEquals(wire, ap.stored)
        assertEquals(listOf(1, 2, 3, 4), progress)
        assertEquals(0L, testScheduler.currentTime, "no timeouts on a clean link")
    }

    @Test
    fun uploadAnsweringMissionRequestIntToo() = runTest {
        ap.useRequestInt = true
        assertTrue(protocol.upload(target, wire) { _, _ -> }.isSuccess)
        assertEquals(wire, ap.stored)
    }

    @Test
    fun itemTheVehicleAsksForAgainIsResent() = runTest {
        // A stale request for item 2 arrives after item 2 was stored: we resend it, ArduPilot answers
        // INVALID_SEQUENCE (not fatal), and the upload carries on with item 3.
        ap.reaskOnce += 2
        assertTrue(protocol.upload(target, wire) { _, _ -> }.isSuccess)
        assertEquals(2, fc.sentOf<MissionItemInt>().count { it.seq.toInt() == 2 })
        assertEquals(wire, ap.stored)
        assertEquals(0L, testScheduler.currentTime, "answering the vehicle's own re-request needs no timeout")
    }

    @Test
    fun lostItemIsResentAfterTheTimeout() = runTest {
        ap.loseOnce += 1
        assertTrue(protocol.upload(target, wire) { _, _ -> }.isSuccess)
        assertEquals(1_500L, testScheduler.currentTime)
        assertEquals(wire, ap.stored)
    }

    @Test
    fun uploadRefusedByTheVehicle() = runTest {
        ap.countAnswer = MavMissionResult.MAV_MISSION_NO_SPACE
        val error = protocol.upload(target, wire) { _, _ -> }.exceptionOrNull()!!.message!!
        assertContains(error, "NO_SPACE")
        assertContains(error, "partial mission")
    }

    @Test
    fun silentVehicleFailsAfterFiveTries() = runTest {
        fc.reply = { emptyList() }
        val result = protocol.upload(target, wire) { _, _ -> }
        assertContains(result.exceptionOrNull()!!.message!!, "no answer")
        assertEquals(5, fc.sentOf<MissionCount>().size)
        assertEquals(7_500L, testScheduler.currentTime)
    }

    @Test
    fun cancellingAnUploadTellsTheVehicle() = runTest {
        fc.reply = { m -> if (m is MissionCount) ap.reply(m) else emptyList() } // stalls waiting for item 0
        val upload = async { protocol.upload(target, wire) { _, _ -> } }
        runCurrent()
        upload.cancel()
        runCurrent()
        val last = fc.sent.last() as MissionAck
        assertEquals(MavMissionResult.MAV_MISSION_OPERATION_CANCELLED.value, last.type.value)
    }

    @Test
    fun downloadReadsBackWhatWasUploaded() = runTest {
        protocol.upload(target, wire) { _, _ -> }.getOrThrow()
        val progress = mutableListOf<Int>()
        val read = protocol.download(target) { done, _ -> progress += done }.getOrThrow()
        assertEquals(Mission(home, plan), missionFromWire(read))
        assertEquals(listOf(0, 1, 2, 3, 4), progress)
        val ack = fc.sent.last() as MissionAck
        assertEquals(MavMissionResult.MAV_MISSION_ACCEPTED.value, ack.type.value, "a download ends with our ACK")
    }

    @Test
    fun downloadReRequestsALostItem() = runTest {
        ap.stored += wire
        ap.dropRequestOnce += 2
        assertEquals(Mission(home, plan), missionFromWire(protocol.download(target) { _, _ -> }.getOrThrow()))
        assertEquals(2, fc.sentOf<MissionRequestInt>().count { it.seq.toInt() == 2 })
        assertEquals(1_500L, testScheduler.currentTime)
    }

    @Test
    fun downloadOfAnEmptyVehicle() = runTest {
        assertEquals(emptyList(), protocol.download(target) { _, _ -> }.getOrThrow())
        assertTrue(fc.sent.last() is MissionAck)
    }

    @Test
    fun downloadDeniedDuringSomeoneElsesUpload() = runTest {
        ap.listAnswer = MavMissionResult.MAV_MISSION_DENIED
        assertContains(protocol.download(target) { _, _ -> }.exceptionOrNull()!!.message!!, "DENIED")
    }

    @Test
    fun clearKeepsOnlyHome() = runTest {
        ap.stored += wire
        assertTrue(protocol.clear(target).isSuccess)
        assertEquals(1, ap.stored.size)
    }
}

/**
 * Answers like ArduPilot's `MissionItemProtocol` (master 2026-09): MISSION_REQUEST (not _INT) for uploads unless
 * [useRequestInt], MISSION_ACK(INVALID_SEQUENCE) for an unexpected item without ending the upload, MISSION_COUNT
 * including home, and a clear that keeps home. Knobs make it lose or repeat messages once.
 */
private class ArduPilotMissionFake {
    val stored = mutableListOf<MissionItemInt>()
    var useRequestInt = false
    var countAnswer: MavMissionResult? = null
    var listAnswer: MavMissionResult? = null
    val loseOnce = mutableSetOf<Int>()        // item seqs whose first copy never arrives
    val reaskOnce = mutableSetOf<Int>()       // item seqs the vehicle asks for twice
    val dropRequestOnce = mutableSetOf<Int>() // download requests whose first copy never arrives
    private var expected = 0
    private var count = 0

    fun reply(m: MavMessage<*>): List<MavMessage<*>> = when (m) {
        is MissionCount -> countAnswer?.let { listOf(ack(it)) } ?: run {
            count = m.count.toInt(); expected = 0
            while (stored.size > count) stored.removeAt(stored.lastIndex)
            listOf(request(0))
        }
        is MissionItemInt -> when {
            loseOnce.remove(m.seq.toInt()) -> emptyList()
            m.seq.toInt() != expected -> listOf(ack(MavMissionResult.MAV_MISSION_INVALID_SEQUENCE))
            else -> {
                if (expected < stored.size) stored[expected] = m else stored += m
                expected++
                // A stale re-request (ArduPilot re-asks every second) that crossed our item on the radio.
                val stale = if (reaskOnce.remove(m.seq.toInt())) listOf(request(m.seq.toInt())) else emptyList()
                stale + if (expected == count) listOf(ack(MavMissionResult.MAV_MISSION_ACCEPTED)) else listOf(request(expected))
            }
        }
        is MissionRequestList -> listAnswer?.let { listOf(ack(it)) }
            ?: listOf(MissionCount(MavTxGateway.GCS_SYSTEM_ID, MavTxGateway.GCS_COMPONENT_ID, stored.size.toUShort()))
        is MissionRequestInt -> when {
            dropRequestOnce.remove(m.seq.toInt()) -> emptyList()
            else -> stored.getOrNull(m.seq.toInt())?.let { listOf(it.copy(targetSystem = MavTxGateway.GCS_SYSTEM_ID)) }
                ?: listOf(ack(MavMissionResult.MAV_MISSION_INVALID_SEQUENCE))
        }
        is MissionClearAll -> {
            while (stored.size > 1) stored.removeAt(stored.lastIndex)
            listOf(ack(MavMissionResult.MAV_MISSION_ACCEPTED))
        }
        else -> emptyList()
    }

    private fun request(seq: Int): MavMessage<*> =
        if (useRequestInt) MissionRequestInt(MavTxGateway.GCS_SYSTEM_ID, MavTxGateway.GCS_COMPONENT_ID, seq.toUShort())
        else MissionRequest(MavTxGateway.GCS_SYSTEM_ID, MavTxGateway.GCS_COMPONENT_ID, seq.toUShort())

    private fun ack(result: MavMissionResult) =
        MissionAck(MavTxGateway.GCS_SYSTEM_ID, MavTxGateway.GCS_COMPONENT_ID, MavEnumValue.of(result))
}
