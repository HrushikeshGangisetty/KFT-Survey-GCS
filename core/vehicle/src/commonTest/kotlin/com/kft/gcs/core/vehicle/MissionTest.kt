package com.kft.gcs.core.vehicle

import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.definitions.common.MavCmd
import com.divpundir.mavlink.definitions.common.MavFrame
import com.divpundir.mavlink.definitions.common.MissionItemInt
import com.kft.gcs.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Spec S11 and the item ↔ wire conversion. The numbers come from common.xml, not from our own constants. */
class MissionTest {
    private val home = Home(LatLon(-35.363261, 149.165230), 584.0)
    private val takeoff = MissionItem(MissionCommand.TAKEOFF, altitudeM = 20.0)
    private val wp = MissionItem(MissionCommand.WAYPOINT, LatLon(-35.3620, 149.1660), 30.0)

    /** S11: if this fails, every item on the vehicle is off by one. */
    @Test
    fun uploadSendsHomeAsSeqZeroAndItemsFromSeqOne() {
        val wire = missionToWire(home, listOf(takeoff, wp), targetSystem = 1u, targetComponent = 1u)

        assertEquals(listOf(0, 1, 2), wire.map { it.seq.toInt() })
        val seq0 = wire[0]
        assertEquals(MavCmd.NAV_WAYPOINT.value, seq0.command.value)
        assertEquals(MavFrame.GLOBAL.value, seq0.frame.value, "home altitude is AMSL")
        assertEquals(-353632610, seq0.x)
        assertEquals(1491652300, seq0.y)
        assertEquals(584f, seq0.z)

        assertEquals(MavCmd.NAV_TAKEOFF.value, wire[1].command.value, "the first planned item is seq 1")
        assertEquals(0, wire[1].x, "a copter takeoff has no location")
        assertEquals(20f, wire[1].z)
        assertEquals(MavCmd.NAV_WAYPOINT.value, wire[2].command.value)
        assertEquals(MavFrame.GLOBAL_RELATIVE_ALT.value, wire[2].frame.value)
        assertEquals(-353620000, wire[2].x)
        assertEquals(1491660000, wire[2].y)
        assertEquals(listOf(1.toUByte()), wire.map { it.targetSystem }.distinct())
    }

    /** S11 on the way back: seq 0 is home and is never shown as a waypoint. */
    @Test
    fun downloadTurnsSeqZeroIntoHome() {
        val wire = missionToWire(home, listOf(takeoff, wp), 1u, 1u).reversed() // arrival order must not matter
        val mission = missionFromWire(wire)
        assertEquals(home, mission.home)
        assertEquals(listOf(takeoff, wp), mission.items)
    }

    @Test
    fun emptyDownloadHasNoHomeAndNoItems() {
        val mission = missionFromWire(emptyList())
        assertNull(mission.home)
        assertEquals(emptyList(), mission.items)
    }

    @Test
    fun framesAreReadTheWayArduPilotReadsThem() {
        // AP_Mission treats the deprecated _INT frames (6 relative, 11 terrain, 5 global) like 3, 10 and 0.
        fun frameOf(f: MavFrame) = missionFromWire(listOf(item(0), item(1, f))).items.single().frame
        assertEquals(AltitudeFrame.RELATIVE, frameOf(MavFrame.GLOBAL_RELATIVE_ALT_INT))
        assertEquals(AltitudeFrame.TERRAIN, frameOf(MavFrame.GLOBAL_TERRAIN_ALT_INT))
        assertEquals(AltitudeFrame.AMSL, frameOf(MavFrame.GLOBAL_INT))
        assertEquals(AltitudeFrame.AMSL, frameOf(MavFrame.MISSION))
    }

    @Test
    fun commandNumbersMatchCommonXml() {
        assertEquals(MavCmd.NAV_WAYPOINT.value.toInt(), MissionCommand.WAYPOINT)
        assertEquals(MavCmd.NAV_RETURN_TO_LAUNCH.value.toInt(), MissionCommand.RETURN_TO_LAUNCH)
        assertEquals(MavCmd.NAV_LAND.value.toInt(), MissionCommand.LAND)
        assertEquals(MavCmd.NAV_TAKEOFF.value.toInt(), MissionCommand.TAKEOFF)
        assertEquals(MavCmd.DO_CHANGE_SPEED.value.toInt(), MissionCommand.DO_CHANGE_SPEED)
    }

    private fun item(seq: Int, frame: MavFrame = MavFrame.GLOBAL) =
        MissionItemInt(seq = seq.toUShort(), frame = MavEnumValue.of(frame), x = 1, y = 1)
}
