package com.kft.gcs.core.vehicle

import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.VehicleId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class MissionRecordsTest {
    private val drone = VehicleId(1, 1u)
    private val a = LatLon(-35.3629913, 149.1656706)
    private val b = LatLon(-35.3629013, 149.1656706)
    private val c = LatLon(-35.3628113, 149.1656706)
    private val store = MemoryRecordStore()
    private val vehicles = MutableStateFlow(listOf(FleetVehicle(drone, 0, VehicleState(connected = true))))

    private fun state(photos: List<LatLon> = emptyList(), reached: Int? = null, complete: Boolean = false, total: Int? = 39) =
        VehicleState(connected = true, photos = photos, mission = MissionProgress(current = reached ?: 0, total = total, lastReached = reached, complete = complete))

    private fun set(state: VehicleState, id: VehicleId = drone) { vehicles.value = listOf(FleetVehicle(id, 0, state)) }

    private fun TestScope.records() = MissionRecords(backgroundScope, vehicles, store, clockMs = { 1_790_000_000_000 }).also { runCurrent() }

    @Test
    fun everyPhotoAndItemIsWrittenAsItHappens() = runTest {
        val records = records()
        records.started(drone, "Field A", "the plan text", itemCount = 39, plannedPhotos = 48)
        runCurrent()
        assertEquals("the plan text", store.read("1790000000000-sys1.plan"))

        set(state(photos = listOf(a), reached = 3))
        runCurrent()
        set(state(photos = listOf(a, b), reached = 3))
        runCurrent()
        assertEquals(
            "kft-mission-record 1\nname Field A\nvehicle 1 1\nitems 39\nplanned-photos 48\n" +
                "photo ${a.latitude} ${a.longitude}\nreached 3\nphoto ${b.latitude} ${b.longitude}\n",
            store.read("1790000000000-sys1.log"),
            "one line per event, in the order they happened",
        )
        val record = records.records.value.single()
        assertEquals(listOf(a, b), record.photos)
        assertEquals(3, record.lastReached)
        assertTrue(record.interrupted, "flown part-way: a resume is possible")

        set(state(photos = listOf(a, b, c), reached = 38, complete = true))
        runCurrent()
        assertTrue(records.records.value.single().complete)
        assertFalse(records.records.value.single().interrupted)
    }

    /** Photos and "reached" from the flight before the upload don't belong to the new record, nor does its "complete". */
    @Test
    fun whatHappenedBeforeTheUploadIsNotCounted() = runTest {
        set(state(photos = listOf(a, b), reached = 38, complete = true))
        val records = records()
        records.started(drone, "Field B", "plan", 39, 48)
        runCurrent()
        set(state(photos = listOf(a, b), reached = 38, complete = true).copy(batteryPercent = 80)) // any other telemetry
        runCurrent()
        val record = records.records.value.single()
        assertEquals(emptyList(), record.photos)
        assertEquals(null, record.lastReached)
        assertFalse(record.complete)

        set(state(photos = listOf(a, b, c), reached = 2))
        runCurrent()
        assertEquals(listOf(c), records.records.value.single().photos, "only the photo taken after the upload")
    }

    /** The app is killed mid-flight: a new MissionRecords over the same files has everything up to the last full line. */
    @Test
    fun aRecordSurvivesARestartEvenWithItsLastLineCutShort() = runTest {
        val first = records()
        first.started(drone, "Field A", "plan", 39, 48)
        runCurrent()
        set(state(photos = listOf(a, b), reached = 5))
        runCurrent()
        store.append("1790000000000-sys1.log", "photo -35.36") // the write the crash interrupted

        vehicles.value = emptyList()
        val second = MissionRecords(backgroundScope, vehicles, store, clockMs = { 1_790_000_100_000 }).also { runCurrent() }
        val record = second.records.value.single()
        assertEquals("Field A" to drone, record.name to record.vehicle)
        assertEquals(listOf(a, b), record.photos)
        assertEquals(5, record.lastReached)
        assertEquals(39 to 48, record.itemCount to record.plannedPhotos)

        // The drone kept flying: it reappears (on a new link number) still holding a 39-item mission, and takes a photo.
        val again = VehicleId(2, 1u)
        set(state(photos = emptyList(), reached = 5), again)
        runCurrent()
        set(state(photos = listOf(c), reached = 6), again)
        runCurrent()
        assertEquals(listOf(a, b, c), second.records.value.single().photos, "the record goes on being filled")
        assertEquals(6, second.records.value.single().lastReached)
    }

    /** A vehicle holding some other mission (a different length) is not mistaken for the one in the record. */
    @Test
    fun aRestartDoesNotAttachToADifferentMission() = runTest {
        val first = records()
        first.started(drone, "Field A", "plan", 39, 48)
        runCurrent()
        set(state(photos = listOf(a), reached = 5))
        runCurrent()
        val second = MissionRecords(backgroundScope, MutableStateFlow(listOf(FleetVehicle(drone, 0, state(photos = listOf(a, b), reached = 7, total = 12)))), store, { 2 }).also { runCurrent() }
        assertEquals(listOf(a), second.records.value.single().photos)
    }

    @Test
    fun aDismissedRecordIsGoneNowAndAfterARestart() = runTest {
        val records = records()
        records.started(drone, "Field A", "plan", 39, 48)
        runCurrent()
        records.dismiss(records.records.value.single().id)
        runCurrent()
        assertTrue(records.records.value.isEmpty())
        assertTrue(MissionRecords(backgroundScope, vehicles, store, { 3 }).also { runCurrent() }.records.value.isEmpty())
    }
}
