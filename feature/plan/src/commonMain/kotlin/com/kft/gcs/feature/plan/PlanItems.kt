package com.kft.gcs.feature.plan

import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.vehicle.AltitudeFrame
import com.kft.gcs.core.vehicle.MissionCommand
import com.kft.gcs.core.vehicle.MissionItem

/**
 * One row of the waypoint editor. P0 edits two kinds, takeoff and waypoint, and passes anything else read from the
 * vehicle (RTL, LAND, camera commands…) through untouched in [passthrough], so a read-then-upload never loses items.
 *
 * @property speedMs speed from this item on, or null to keep the current one. It isn't a field of NAV_WAYPOINT in
 *   MAVLink, so it goes up as a DO_CHANGE_SPEED item just before this one ([toMissionItems]).
 */
data class PlanItem(
    val command: Int,
    val position: LatLon? = null,
    val altitudeM: Double = 0.0,
    val speedMs: Double? = null,
    val passthrough: MissionItem? = null,
) {
    val editable: Boolean get() = passthrough == null

    companion object {
        /** Copter takeoff: climbs where it stands, so no position (ArduCopter ignores the item's lat/lon). */
        fun takeoff(altitudeM: Double) = PlanItem(MissionCommand.TAKEOFF, altitudeM = altitudeM)
        fun waypoint(at: LatLon, altitudeM: Double) = PlanItem(MissionCommand.WAYPOINT, at, altitudeM)
    }
}

/** Starting altitude above home for a new plan: low for a copter, a safe pattern height for a plane. */
fun defaultAltitudeM(kind: VehicleKind?): Double = if (kind == VehicleKind.PLANE) 100.0 else 30.0

/**
 * Adds a waypoint at [at]. It takes the altitude of the last editable item, so a survey block stays level while you
 * click. On a Copter, the first click of the mission ([startsMission]: no earlier group has items) also puts a
 * NAV_TAKEOFF in front (item 1): without it, AUTO on the ground doesn't climb. Planes get no automatic takeoff,
 * because an ArduPlane takeoff needs pitch and a runway heading the editor can't guess; the pilot takes off on the RC
 * and switches to AUTO in the air.
 */
fun List<PlanItem>.addWaypoint(at: LatLon, kind: VehicleKind?, startsMission: Boolean = true): List<PlanItem> {
    val altitude = lastOrNull { it.editable }?.altitudeM ?: defaultAltitudeM(kind)
    val takeoff = if (isEmpty() && startsMission && kind != VehicleKind.PLANE) listOf(PlanItem.takeoff(altitude)) else emptyList()
    return this + takeoff + PlanItem.waypoint(at, altitude)
}

/**
 * The vehicle's seq number for each row (spec S11: 1 is the first item after home). A speed change occupies a seq
 * of its own, so row numbers and seq numbers differ once speeds are set. Showing seq numbers keeps the editor, the
 * map labels and the Fly HUD's "Mission 3 / 7" talking about the same item.
 */
fun seqNumbers(items: List<PlanItem>): List<Int> {
    var next = 1
    return items.map { item ->
        if (item.editable && item.speedMs != null) next++ // its DO_CHANGE_SPEED comes first
        next++
    }
}

/**
 * Editor rows to mission items. Altitudes are relative to home (P0). A row with a speed becomes two items:
 * DO_CHANGE_SPEED (param1 speed type 0, param2 m/s, param3 -1 = throttle unchanged) and then the row itself.
 *
 * Speed type 0 on purpose (ArduPilot master 2026-09): ArduCopter treats 0 and 1 alike as horizontal speed
 * (`ModeAuto::do_change_speed`), but on ArduPlane type 1 sets the *minimum* groundspeed rather than a target
 * (`Plane::do_change_speed`), and type 0 sets the airspeed target, which is what a plane pilot means by speed.
 */
fun toMissionItems(items: List<PlanItem>): List<MissionItem> = items.flatMap { item ->
    val passthrough = item.passthrough
    if (passthrough != null) return@flatMap listOf(passthrough)
    val speed = item.speedMs?.let(::speedItem)
    listOfNotNull(speed, MissionItem(item.command, item.position, item.altitudeM, AltitudeFrame.RELATIVE))
}

/**
 * Mission items (home already removed, S11) back to editor rows. A DO_CHANGE_SPEED that looks exactly like one we
 * write, followed by a takeoff or waypoint, folds back into that row's speed. Anything else, including relative-frame
 * mismatches, is kept as a passthrough row so nothing is lost or silently changed.
 */
fun fromMissionItems(items: List<MissionItem>): List<PlanItem> {
    val rows = mutableListOf<PlanItem>()
    var pendingSpeed: MissionItem? = null
    for (item in items) {
        val editable = item.command in EDITABLE && item.frame == AltitudeFrame.RELATIVE
        when {
            item.isOurSpeedChange() && pendingSpeed == null -> { pendingSpeed = item; continue }
            editable -> rows += PlanItem(item.command, item.position, item.altitudeM, pendingSpeed?.param2?.toDouble())
            else -> {
                pendingSpeed?.let { rows += PlanItem(it.command, passthrough = it) }
                rows += PlanItem(item.command, item.position, item.altitudeM, passthrough = item)
            }
        }
        pendingSpeed = null
    }
    pendingSpeed?.let { rows += PlanItem(it.command, passthrough = it) }
    return rows
}

/** DO_CHANGE_SPEED as we write it: speed type 0 (see [toMissionItems]), [speedMs], throttle unchanged. */
internal fun speedItem(speedMs: Double) = MissionItem(MissionCommand.DO_CHANGE_SPEED, param1 = SPEED_TYPE, param2 = speedMs.toFloat(), param3 = -1f)

private const val SPEED_TYPE = 0f
private val EDITABLE = setOf(MissionCommand.TAKEOFF, MissionCommand.WAYPOINT)

private fun MissionItem.isOurSpeedChange() =
    command == MissionCommand.DO_CHANGE_SPEED && param1 == SPEED_TYPE && param3 == -1f && param2 > 0f
