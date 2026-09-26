package com.kft.gcs.core.vehicle

import com.kft.gcs.core.mission.MissionItem
import com.kft.gcs.core.mission.AltitudeFrame
import com.kft.gcs.core.mission.MissionCommand
import com.kft.gcs.core.mission.Mission
import com.kft.gcs.core.mission.Home
import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.definitions.common.MavFrame
import com.divpundir.mavlink.definitions.common.MissionItemInt
import com.kft.gcs.core.geo.LatLon

/**
 * The wire list for an upload (spec S11): seq 0 is [home], seq 1 is `items[0]`, and so on.
 *
 * ArduPilot stores home at index 0 and ignores whatever arrives there (AP_Mission `replace_cmd`: "Writing index zero
 * is not allowed, it must be home"). We still send the real home rather than a placeholder, so the wire list means the
 * same thing to any tool that logs or replays it.
 */
internal fun missionToWire(home: Home, items: List<MissionItem>, targetSystem: UByte, targetComponent: UByte): List<MissionItemInt> {
    val homeItem = MissionItem(MissionCommand.WAYPOINT, home.position, home.altitudeMslM, AltitudeFrame.AMSL)
    return (listOf(homeItem) + items).mapIndexed { seq, item ->
        MissionItemInt(
            targetSystem = targetSystem,
            targetComponent = targetComponent,
            seq = seq.toUShort(),
            frame = MavEnumValue.of(item.frame.toMav()),
            command = MavEnumValue.fromValue(item.command.toUInt()),
            current = 0u,
            autocontinue = 1u,
            param1 = item.param1,
            param2 = item.param2,
            param3 = item.param3,
            param4 = item.param4,
            x = item.position?.latE7 ?: 0,
            y = item.position?.lonE7 ?: 0,
            z = item.altitudeM.toFloat(),
        )
    }
}

/** A downloaded wire list back to a [Mission]: seq 0 becomes [Mission.home] and never appears as an item (S11). */
internal fun missionFromWire(wire: List<MissionItemInt>): Mission {
    val sorted = wire.sortedBy { it.seq }
    val home = sorted.firstOrNull { it.seq.toInt() == 0 }?.let { Home(LatLon.fromE7(it.x, it.y), it.z.toDouble()) }
    return Mission(home, sorted.filter { it.seq.toInt() > 0 }.map { it.toItem() })
}

private fun MissionItemInt.toItem(): MissionItem {
    val frame = frameOf(frame.value)
    return MissionItem(
        command = command.value.toInt(),
        // ArduPilot sends 0,0 for commands that don't store a location.
        position = if (x == 0 && y == 0) null else LatLon.fromE7(x, y),
        altitudeM = z.toDouble(),
        frame = frame,
        param1 = param1, param2 = param2, param3 = param3, param4 = param4,
    )
}

private fun AltitudeFrame.toMav() = when (this) {
    AltitudeFrame.AMSL -> MavFrame.GLOBAL
    AltitudeFrame.RELATIVE -> MavFrame.GLOBAL_RELATIVE_ALT
    AltitudeFrame.TERRAIN -> MavFrame.GLOBAL_TERRAIN_ALT
}

/**
 * MAV_FRAME (common.xml) to our frame. ArduPilot downloads only GLOBAL (0), GLOBAL_RELATIVE_ALT (3) and
 * GLOBAL_TERRAIN_ALT (10), and on upload treats the deprecated `_INT` twins (5, 6, 11) and MISSION (2) the same way
 * (AP_Mission `mavlink_int_to_mission_cmd`), so we map them the way it does.
 */
private fun frameOf(value: UInt) = when (value) {
    MavFrame.GLOBAL_RELATIVE_ALT.value, MavFrame.GLOBAL_RELATIVE_ALT_INT.value -> AltitudeFrame.RELATIVE
    MavFrame.GLOBAL_TERRAIN_ALT.value, MavFrame.GLOBAL_TERRAIN_ALT_INT.value -> AltitudeFrame.TERRAIN
    else -> AltitudeFrame.AMSL
}

