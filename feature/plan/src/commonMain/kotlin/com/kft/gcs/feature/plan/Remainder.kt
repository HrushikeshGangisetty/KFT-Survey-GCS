package com.kft.gcs.feature.plan

import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.planning.Camera
import com.kft.gcs.core.planning.SurveyLimits
import com.kft.gcs.core.planning.gsdM
import com.kft.gcs.core.planning.locateLastPhoto
import com.kft.gcs.core.planning.remainingArea

/** The groups that fly what is left of a mission, and a sentence telling the operator what was done. */
internal data class Remainder(val groups: List<MissionGroup>, val note: String)

/**
 * What is left of a mission that stopped part-way (Pass 31), as groups for a new mission.
 *
 * - **Photos were taken:** the survey the last photo belongs to continues from that photo, one photo spacing back
 *   ([SurveySettings.resume], worked out by `locateLastPhoto` / `remainderFrom`). Groups before it are done and
 *   dropped; groups after it follow unchanged.
 * - **[camera] is another camera than the survey's** (the drone taking over carries a different one): the old line
 *   positions don't suit it, so the area not yet flown is cut out ([remainingArea]) and planned again as a survey
 *   of its own with that camera. The line that was being flown is flown again in full.
 * - **No photos, but items were reached** (a waypoint mission, or a survey that never got to its first line): the
 *   waypoints after the last one reached, with a takeoff in front for a Copter; a survey not yet started stays whole.
 *
 * [groups] are the mission as it was uploaded, [photos] and [lastReached] what its record holds.
 * ponytail: with several surveys in one mission, the one being flown is picked by counting planned photos; photos
 * missed in an earlier survey could shift that by one group. One survey per mission (the usual job) has no such case.
 */
internal fun remainderOf(
    groups: List<MissionGroup>,
    photos: List<LatLon>,
    lastReached: Int?,
    kind: VehicleKind?,
    limits: SurveyLimits,
    camera: Camera? = null,
): Result<Remainder> = runCatching {
    val flat = flatten(groups, kind, limits).groups
    if (photos.isEmpty()) return@runCatching withoutPhotos(flat, lastReached ?: 0, kind)

    // The survey the last photo is in: the one whose planned photos include photo number `photos.size`.
    var before = 0
    val surveys = flat.withIndex().filter { it.value.plan != null }
    require(surveys.isNotEmpty()) { "This mission has no survey in it, but photos were taken: resume it by hand." }
    val (index, current) = surveys.firstOrNull { (_, fg) ->
        (photos.size <= before + fg.plan!!.stats.photoCount).also { fits -> if (!fits) before += fg.plan.stats.photoCount }
    } ?: surveys.last().also { before -= it.value.plan!!.stats.photoCount }
    val group = current.group as SurveyGroup
    val plan = current.plan!!
    val position = requireNotNull(plan.locateLastPhoto(photos.drop(before.coerceIn(0, photos.size - 1)))) { "The last photo can't be placed on ${group.name}." }
    val later = groups.drop(index + 1)
    val done = "${photos.size} of ${flat.sumOf { it.plan?.stats?.photoCount ?: 0 }} photos were taken"

    if (camera == null || camera == group.survey.camera) {
        val rest = group.copy(name = "${group.name} (rest)", survey = group.survey.copy(resume = position))
        return@runCatching Remainder(listOf(rest) + later, "$done. The rest starts one photo back from the last one, on line ${plan.legs[position.leg].grid.passes[position.pass].line + 1}.")
    }

    // Another camera: plan the unflown part of the area again.
    val s = group.survey
    require(!s.isCorridor && !s.crosshatch && plan.grid.lineSkip == 1) {
        "The rest of a corridor, a crosshatch or a plane's skipped-line survey can't be planned again for another camera. Resume with the same camera, or draw the remaining area by hand."
    }
    val line = plan.grid.passes[position.pass].line
    val area = requireNotNull(remainingArea(s.polygon, s.gridAngleDeg, plan.lineSpacingM, s.entry, line)) { "Nothing is left of ${group.name}." }
    val rest = group.copy(
        name = "${group.name} (rest, ${camera.name})",
        // Altitude-first keeps the altitude (the GSD follows the new camera); GSD-first keeps the GSD (the altitude follows).
        survey = s.copy(polygon = area, camera = camera, gsdCm = if (s.heightMode == HeightMode.ALTITUDE) camera.gsdM(s.altitudeM) * 100 else s.gsdCm, resume = null),
    )
    Remainder(
        listOf(rest) + later,
        "$done. WARNING: the camera is different (${camera.name} instead of ${s.camera.name}), so the area not yet flown was planned again for it: " +
            "new line spacing and photo spacing. The line that was being flown (line ${line + 1}) is flown again in full. Check the plan before uploading.",
    )
}

/** No photo was taken: continue after the last item the vehicle reached. */
private fun withoutPhotos(flat: List<FlatGroup>, lastReached: Int, kind: VehicleKind?): Remainder {
    require(lastReached > 0) { "Nothing of this mission was flown: upload it again as it is." }
    // The group that holds the next item (seq numbers run through the mission, the first item being 1).
    val index = flat.indexOfFirst { lastReached + 1 < it.firstSeq + it.items.size }
    require(index >= 0) { "Every item of this mission was reached: nothing is left." }
    val later = flat.drop(index + 1).map { it.group }
    val current = flat[index]
    val group = current.group
    if (group !is WaypointGroup) return Remainder(listOf(group) + later, "No photo was taken yet: ${group.name} is flown from its start.")
    // Rows whose own seq is after the last one reached. A row with a speed is two items; its seq is the row's own.
    val seqs = seqNumbers(group.items).map { it + current.firstSeq - 1 }
    val rows = group.items.filterIndexed { i, row -> seqs[i] > lastReached && row.command != com.kft.gcs.core.mission.MissionCommand.TAKEOFF }
    val takeoff = if (kind != VehicleKind.PLANE) listOfNotNull(rows.firstOrNull { it.position != null }?.let { PlanItem.takeoff(it.altitudeM) }) else emptyList()
    return Remainder(
        listOf(group.copy(name = "${group.name} (rest)", items = takeoff + rows)) + later,
        "Item $lastReached was the last one reached: the rest starts at item ${lastReached + 1}.",
    )
}
