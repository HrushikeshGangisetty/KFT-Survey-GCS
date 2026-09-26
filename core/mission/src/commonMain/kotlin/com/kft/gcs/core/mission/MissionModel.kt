package com.kft.gcs.core.mission

import com.kft.gcs.core.geo.LatLon

/**
 * One mission item, in plain units, with no MAVLink types, so screens and planners can build missions freely.
 * [command] is a MAV_CMD number ([MissionCommand] names the ones we use). Items without a location (a DO_ command,
 * or a Copter takeoff that climbs where it stands) have [position] null.
 *
 * [frame] only means something for commands that store a location (waypoints, takeoff, land…). ArduPilot keeps no
 * frame for the others and reads them back as AMSL (AP_Mission `mission_cmd_to_mavlink_int`), so don't compare it.
 */
data class MissionItem(
    val command: Int,
    val position: LatLon? = null,
    val altitudeM: Double = 0.0,
    val frame: AltitudeFrame = AltitudeFrame.RELATIVE,
    val param1: Float = 0f,
    val param2: Float = 0f,
    val param3: Float = 0f,
    val param4: Float = 0f,
)

/** What an item's altitude is measured from. P0 plans use [RELATIVE]; the others round-trip from downloads. */
enum class AltitudeFrame { AMSL, RELATIVE, TERRAIN }

/** MAV_CMD numbers from `common.xml`, for the commands the planner creates. */
object MissionCommand {
    const val WAYPOINT = 16
    const val RETURN_TO_LAUNCH = 20
    const val LAND = 21
    const val TAKEOFF = 22
    const val DO_CHANGE_SPEED = 178

    /**
     * DO_SET_CAM_TRIGG_DIST (common.xml 206): param1 distance in metres (0 = stop), param3 1 = also take one photo
     * now. ArduPilot (AP_Mission, master 2026-09) stores param1, param3 and param4 (camera instance, 0 = all) and
     * drops param2, so we always send param2 = 0 and a read-back compares equal.
     */
    const val DO_SET_CAM_TRIGG_DIST = 206
}

/** A mission as read from the vehicle: home (seq 0) kept apart from the items the user planned (seq 1…). */
data class Mission(val home: Home?, val items: List<MissionItem>)

/** The vehicle's home. [altitudeMslM] is above mean sea level, which is what mission seq 0 carries. */
data class Home(val position: LatLon, val altitudeMslM: Double)
