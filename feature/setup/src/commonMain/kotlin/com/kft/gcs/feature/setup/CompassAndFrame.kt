package com.kft.gcs.feature.setup

import com.kft.gcs.core.mavlink.VehicleKind

/*
 * The compass-details and frame pages' field tables. Like SetupFields.kt, every list is copied from the KFT fork's own
 * parameter documentation (8e89f41a): the @Values comments in AP_Compass.cpp, AP_AHRS.cpp, ArduCopter/Parameters.cpp
 * and ArduPlane/quadplane.cpp.
 */

/**
 * ArduPilot's rotations (AP_Math/rotations.h), the shared @Values list of AHRS_ORIENTATION and COMPASS_ORIENTx. Each
 * label lists the rotations in the order they are applied. 41 is missing on purpose: ArduPilot doesn't offer it.
 */
private val ROTATIONS = listOf(
    0 to "None", 1 to "Yaw45", 2 to "Yaw90", 3 to "Yaw135", 4 to "Yaw180", 5 to "Yaw225", 6 to "Yaw270", 7 to "Yaw315",
    8 to "Roll180", 9 to "Yaw45Roll180", 10 to "Yaw90Roll180", 11 to "Yaw135Roll180", 12 to "Pitch180", 13 to "Yaw225Roll180",
    14 to "Yaw270Roll180", 15 to "Yaw315Roll180", 16 to "Roll90", 17 to "Yaw45Roll90", 18 to "Yaw90Roll90", 19 to "Yaw135Roll90",
    20 to "Roll270", 21 to "Yaw45Roll270", 22 to "Yaw90Roll270", 23 to "Yaw135Roll270", 24 to "Pitch90", 25 to "Pitch270",
    26 to "Yaw90Pitch180", 27 to "Yaw270Pitch180", 28 to "Pitch90Roll90", 29 to "Pitch90Roll180", 30 to "Pitch90Roll270",
    31 to "Pitch180Roll90", 32 to "Pitch180Roll270", 33 to "Pitch270Roll90", 34 to "Pitch270Roll180", 35 to "Pitch270Roll270",
    36 to "Yaw90Pitch180Roll90", 37 to "Yaw270Roll90", 38 to "Yaw293Pitch68Roll180", 39 to "Pitch315", 40 to "Pitch315Roll90",
    42 to "Roll45", 43 to "Roll315", 100 to "Custom (4.1 and older)", 101 to "Custom 1", 102 to "Custom 2",
)

private val ON_OFF = FieldKind.Choice(listOf(0 to "Disabled", 1 to "Enabled"))

/** The n-th (1-based) of a numbered compass parameter: COMPASS_USE, COMPASS_USE2, COMPASS_USE3. */
private fun nth(base: String, n: Int) = if (n == 1) base else "$base$n"

/** The id parameters of every compass slot ArduPilot detects: COMPASS_DEV_ID, COMPASS_DEV_ID2..8 (4..8 detected, unused). */
private val DEV_ID_PARAMS = (1..8).map { nth("COMPASS_DEV_ID", it) }

private val ORDINALS = listOf("1st", "2nd", "3rd")

/** COMPASS_PRIO1_ID..3: which detected compass is 1st, 2nd and 3rd. */
internal val PRIORITY_PARAMS = (1..3).map { "COMPASS_PRIO${it}_ID" }

/**
 * The compass details page, built from [values] because the priority choices are this vehicle's own compasses.
 *
 * How ArduPilot numbers them (AP_Compass.cpp, KFT fork 8e89f41a), which is why the page has two kinds of section:
 * - COMPASS_PRIOn_ID and COMPASS_USEn go **by priority**: the 1st-priority compass gives the heading, and USE/USE2/USE3
 *   say whether the 1st/2nd/3rd may be used for it (`_use_for_yaw[Priority(i)]`). A priority holds a device id, so its
 *   choices are the compasses the FC detected (COMPASS_DEV_IDn). Changing one takes a reboot.
 * - COMPASS_EXTERNAL/EXTERNn and COMPASS_ORIENT/ORIENTn go **by detected compass**, in COMPASS_DEV_IDn order
 *   (`_state[StateIndex]`). ORIENT only applies to an external compass; an internal one follows the board (AHRS_ORIENTATION).
 */
fun compassSections(values: Map<String, Float>): List<SectionSpec> {
    val detected = DEV_ID_PARAMS.mapNotNull { values[it]?.toInt()?.takeIf { id -> id != 0 } }.distinct()
    val choices = FieldKind.Choice(listOf(0 to "None (filled in at boot)") + detected.map { it to compassLabel(it) })
    return listOf(
        SectionSpec(
            "Compasses",
            listOf(FieldSpec("COMPASS_ENABLE", "Compasses", ON_OFF, "Off turns every compass off; the heading must then come from GPS yaw or another source", reboot = true)),
        ),
        SectionSpec(
            "Priority and use",
            (1..3).flatMap { n ->
                listOf(
                    FieldSpec(PRIORITY_PARAMS[n - 1], "${ORDINALS[n - 1]} priority", choices, reboot = true),
                    FieldSpec(nth("COMPASS_USE", n), "Use the ${ORDINALS[n - 1]} for heading", ON_OFF),
                )
            },
            "The 1st-priority compass gives the heading; the others are backups the FC checks it against. Put the external " +
                "compass (on the GPS mast, away from motors and wires) first. Priority changes take effect after a reboot.",
        ),
    ) + (1..3).mapNotNull { n ->
        val id = values[nth("COMPASS_DEV_ID", n)]?.toInt()?.takeIf { it != 0 } ?: return@mapNotNull null
        SectionSpec(
            "Compass $n: ${compassLabel(id)}",
            listOf(
                // The first one is COMPASS_EXTERNAL, the others COMPASS_EXTERN2 and COMPASS_EXTERN3.
                FieldSpec(if (n == 1) "COMPASS_EXTERNAL" else "COMPASS_EXTERN$n", "Mounting", FieldKind.Choice(listOf(0 to "Internal", 1 to "External", 2 to "External (forced)"))),
                FieldSpec(nth("COMPASS_ORIENT", n), "Orientation", FieldKind.Choice(ROTATIONS), "Rotation of the compass from arrow-forward, top-up; external compasses only"),
            ),
            "\"External (forced)\" stops ArduPilot changing this from the bus it found the compass on.",
        )
    }
}

/**
 * A warning for the confirm question when [param] is a priority being set to a compass another priority already holds:
 * ArduPilot lists each compass once, so the operator has to swap them. Null otherwise.
 */
internal fun priorityClash(param: String, value: Float, values: Map<String, Float>): String? {
    if (param !in PRIORITY_PARAMS || value == 0f) return null
    val other = PRIORITY_PARAMS.filter { it != param }.firstOrNull { values[it] == value } ?: return null
    return "$other holds the same compass. Set it to the other compass too, so each compass is listed once."
}

/** Copter FRAME_CLASS. */
private val COPTER_CLASSES = listOf(
    0 to "Undefined", 1 to "Quad", 2 to "Hexa", 3 to "Octa", 4 to "OctaQuad", 5 to "Y6", 6 to "Heli", 7 to "Tri", 8 to "SingleCopter",
    9 to "CoaxCopter", 10 to "BiCopter", 11 to "Heli_Dual", 12 to "DodecaHexa", 13 to "HeliQuad", 14 to "Deca", 15 to "Scripting Matrix",
    16 to "6DoF Scripting", 17 to "Dynamic Scripting Matrix",
)

/** Copter FRAME_TYPE. */
private val COPTER_TYPES = listOf(
    0 to "Plus", 1 to "X", 2 to "V", 3 to "H", 4 to "V-Tail", 5 to "A-Tail", 10 to "Y6B", 11 to "Y6F", 12 to "BetaFlightX", 13 to "DJIX",
    14 to "ClockwiseX", 15 to "I", 18 to "BetaFlightXReversed", 19 to "Y4",
)

/** QuadPlane Q_FRAME_CLASS: fewer classes than Copter (no helicopters). */
private val Q_CLASSES = listOf(
    0 to "Undefined", 1 to "Quad", 2 to "Hexa", 3 to "Octa", 4 to "OctaQuad", 5 to "Y6", 7 to "Tri", 10 to "Single/Dual", 12 to "DodecaHexa",
    14 to "Deca", 15 to "Scripting Matrix", 17 to "Dynamic Scripting Matrix",
)

/** QuadPlane Q_FRAME_TYPE: Copter's list plus the two NYT frames. */
private val Q_TYPES = COPTER_TYPES + listOf(16 to "NYT Plus", 17 to "NYT X")

/**
 * The frame and board-orientation page for [kind]. Copter: FRAME_CLASS and FRAME_TYPE. Plane: Q_FRAME_CLASS and
 * Q_FRAME_TYPE when it is a QuadPlane (Q_ENABLE ≥ 1); a fixed-wing Plane has no frame class. Every field here takes
 * effect after a reboot (@RebootRequired), and a changed board orientation needs the level calibration again.
 */
fun frameSections(kind: VehicleKind?, values: Map<String, Float>): List<SectionSpec> {
    val frame = when {
        kind != VehicleKind.PLANE -> SectionSpec(
            "Frame",
            listOf(
                FieldSpec("FRAME_CLASS", "Frame class", FieldKind.Choice(COPTER_CLASSES), "How many motors, or a helicopter", reboot = true),
                FieldSpec("FRAME_TYPE", "Frame type", FieldKind.Choice(COPTER_TYPES), "Motor layout; not used for Tri, Heli, Single or Coax", reboot = true),
            ),
            "Sets the motor mixing. Takes effect after a reboot. Then check every motor's position and spin direction against " +
                "ArduPilot's motor diagram for this frame before the first flight (propellers off).",
        )
        (values["Q_ENABLE"] ?: 0f) >= 1f -> SectionSpec(
            "VTOL frame (QuadPlane)",
            listOf(
                FieldSpec("Q_FRAME_CLASS", "Frame class", FieldKind.Choice(Q_CLASSES), "The lift motors", reboot = true),
                FieldSpec("Q_FRAME_TYPE", "Frame type", FieldKind.Choice(Q_TYPES), "Lift motor layout", reboot = true),
            ),
            "Sets the lift motors' mixing. Takes effect after a reboot; then check each lift motor's position and direction (propellers off).",
        )
        else -> SectionSpec(
            "Frame",
            emptyList(),
            "A fixed-wing Plane has no frame class. A QuadPlane gets one once Q_ENABLE is set (Params tab) and the FC rebooted.",
        )
    }
    return listOf(
        frame,
        SectionSpec(
            "Board orientation",
            listOf(FieldSpec("AHRS_ORIENTATION", "Board orientation", FieldKind.Choice(ROTATIONS), "Rotation of the flight controller from arrow-forward, top-up", reboot = true)),
            "Leave at None when the board's arrow points forward and its top faces up. After a change: reboot, then Level on the " +
                "Accelerometer page. Check with the live attitude above: nose down makes pitch negative, right side down makes roll positive.",
        ),
    )
}
