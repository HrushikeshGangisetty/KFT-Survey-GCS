package com.kft.gcs.core.vehicle

import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.VehicleId
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * One mission as it was uploaded to a vehicle, and how far the vehicle got with it.
 * @property id also the name of its files in the [RecordStore].
 * @property plan the Plan tab's own text for the mission (its groups, as in a plan file). Opaque here; the Plan tab
 *   reads it back to build the mission for what is left.
 * @property lastReached the last item the vehicle reported reaching (MISSION_ITEM_REACHED), if any.
 * @property photos where each photo was taken (CAMERA_FEEDBACK), in the order taken.
 * @property dismissed the operator has put it away: it is neither listed nor drawn any more.
 */
data class MissionRecord(
    val id: String,
    val name: String,
    val vehicle: VehicleId,
    val plan: String,
    val itemCount: Int,
    val plannedPhotos: Int,
    val lastReached: Int? = null,
    val photos: List<LatLon> = emptyList(),
    val complete: Boolean = false,
    val dismissed: Boolean = false,
) {
    /** Something was flown and something is left: the missions a resume is for. */
    val interrupted: Boolean get() = !complete && !dismissed && (photos.isNotEmpty() || (lastReached ?: 0) > 0)
}

/**
 * Where records are kept: a folder of small text files, by name. [append] must reach the disk before it returns
 * (as far as the platform allows), because the point of a record is to survive the app being killed mid-flight.
 */
interface RecordStore {
    fun names(): List<String>
    fun read(name: String): String?
    fun write(name: String, text: String)
    fun append(name: String, text: String)
}

/** A [RecordStore] in memory: tests, and a build with no storage wired in. */
class MemoryRecordStore : RecordStore {
    val files = LinkedHashMap<String, String>()
    override fun names() = files.keys.toList()
    override fun read(name: String) = files[name]
    override fun write(name: String, text: String) { files[name] = text }
    override fun append(name: String, text: String) { files[name] = files[name].orEmpty() + text }
}

/**
 * Progress of every uploaded mission, written down as it happens (Pass 31).
 *
 * ```
 * Plan uploads a mission ─▶ started(vehicle, plan, …) ─▶ <id>.plan (the plan) + <id>.log (header)
 * that vehicle's state changes ─▶ a new photo        ─▶ append "photo <lat> <lon>"
 *                              ─▶ a new item reached ─▶ append "reached <seq>"
 *                              ─▶ mission complete   ─▶ append "complete"
 * app start ─▶ every <id>.log is read back ─▶ records (a truncated last line is skipped)
 * ```
 * One line per event, appended: a crash loses at most the line being written, never what was already saved.
 *
 * After a restart, a record goes on being filled when a vehicle with the same system id appears holding a mission of
 * the same length (MISSION_CURRENT's total) and the record isn't complete: the drone kept flying while the app was
 * down. Photos taken in between are missing from the record; the resume point uses the last photo, so it is still right.
 *
 * All state is owned by one coroutine ([started] and [dismiss] only post to it), so nothing needs a lock.
 *
 * @param io where the file reads and writes run (the app passes its I/O dispatcher).
 * @param clockMs wall-clock milliseconds, for the record's id.
 */
class MissionRecords(
    scope: CoroutineScope,
    vehicles: StateFlow<List<FleetVehicle>>,
    private val store: RecordStore,
    private val clockMs: () -> Long,
    io: CoroutineContext = EmptyCoroutineContext,
) {
    private val _records = MutableStateFlow<List<MissionRecord>>(emptyList())

    /** Every record that isn't dismissed, newest first. */
    val records: StateFlow<List<MissionRecord>> = _records.asStateFlow()

    private sealed interface Event {
        data class Vehicles(val list: List<FleetVehicle>) : Event
        data class Started(val record: MissionRecord) : Event
        data class Dismissed(val id: String) : Event
    }

    private val commands = Channel<Event>(Channel.UNLIMITED)

    /** What is being followed: the record a vehicle is flying, and how many of the vehicle's photos are already in it. */
    private class Tracking(val recordId: String, var seenPhotos: Int, val reachedAtStart: Int?)

    private val tracking = HashMap<VehicleId, Tracking>()
    private var latest = emptyList<FleetVehicle>()

    init {
        scope.launch(io) {
            _records.value = store.names().filter { it.endsWith(".log") }.mapNotNull(::load).sortedByDescending { it.id }
            merge(vehicles.map { Event.Vehicles(it) }, commands.receiveAsFlow()).collect { event ->
                when (event) {
                    is Event.Vehicles -> { latest = event.list; event.list.forEach(::follow) }
                    is Event.Started -> begin(event.record)
                    is Event.Dismissed -> { store.append("${event.id}.log", "dismissed\n"); change(event.id) { it.copy(dismissed = true) } }
                }
            }
        }
    }

    /** A mission was uploaded to [vehicle]: from now on its progress is recorded. Replaces what that vehicle was flying before. */
    fun started(vehicle: VehicleId, name: String, plan: String, itemCount: Int, plannedPhotos: Int) {
        val id = "${clockMs()}-sys${vehicle.systemId}"
        commands.trySend(Event.Started(MissionRecord(id, name, vehicle, plan, itemCount, plannedPhotos)))
    }

    /** Puts a record away (it stays on disk, marked). */
    fun dismiss(id: String) {
        commands.trySend(Event.Dismissed(id))
    }

    private fun begin(record: MissionRecord) {
        store.write("${record.id}.plan", record.plan)
        store.write("${record.id}.log", header(record))
        _records.value = listOf(record) + _records.value
        val state = latest.find { it.id == record.vehicle }?.state
        // Photos and "reached" from before the upload belong to an earlier flight, not to this record.
        tracking[record.vehicle] = Tracking(record.id, state?.photos?.size ?: 0, state?.mission?.lastReached)
    }

    private fun follow(vehicle: FleetVehicle) {
        val t = tracking[vehicle.id] ?: reattach(vehicle) ?: return
        val record = _records.value.find { it.id == t.recordId } ?: return
        if (record.complete) return
        val state = vehicle.state
        val log = "${record.id}.log"

        // A new link session starts the vehicle's photo list again from nothing.
        if (state.photos.size < t.seenPhotos) t.seenPhotos = 0
        val newPhotos = state.photos.drop(t.seenPhotos)
        t.seenPhotos = state.photos.size
        newPhotos.forEach { store.append(log, "photo ${it.latitude} ${it.longitude}\n") }

        val reached = state.mission?.lastReached?.takeIf { it != record.lastReached && !(record.lastReached == null && it == t.reachedAtStart) }
        if (reached != null) store.append(log, "reached $reached\n")

        // "Complete" only counts once this record has made progress: straight after an upload the vehicle may still
        // be reporting the mission before it as complete.
        val progressed = record.photos.isNotEmpty() || newPhotos.isNotEmpty() || (reached ?: record.lastReached ?: 0) > 0
        val complete = progressed && state.mission?.complete == true
        if (complete) store.append(log, "complete\n")

        if (newPhotos.isNotEmpty() || reached != null || complete) {
            change(record.id) { it.copy(photos = it.photos + newPhotos, lastReached = reached ?: it.lastReached, complete = complete) }
        }
    }

    /** After a restart: this vehicle is still holding the mission of its newest unfinished record. */
    private fun reattach(vehicle: FleetVehicle): Tracking? {
        val total = vehicle.state.mission?.total ?: return null
        val record = _records.value.firstOrNull { it.vehicle.systemId == vehicle.id.systemId } ?: return null
        if (record.complete || record.dismissed || record.itemCount != total || tracking.values.any { it.recordId == record.id }) return null
        return Tracking(record.id, vehicle.state.photos.size, vehicle.state.mission?.lastReached).also { tracking[vehicle.id] = it }
    }

    private fun change(id: String, change: (MissionRecord) -> MissionRecord) {
        _records.value = _records.value.map { if (it.id == id) change(it) else it }.filterNot { it.dismissed }
    }

    private fun header(r: MissionRecord) =
        "$MAGIC\nname ${r.name}\nvehicle ${r.vehicle.link} ${r.vehicle.systemId}\nitems ${r.itemCount}\nplanned-photos ${r.plannedPhotos}\n"

    /** Reads `<id>.log` (and `<id>.plan`) back. Lines it can't read are skipped: a crash can cut the last one short. */
    private fun load(logName: String): MissionRecord? {
        val id = logName.removeSuffix(".log")
        val lines = store.read(logName)?.lines() ?: return null
        if (lines.firstOrNull() != MAGIC) return null
        var record = MissionRecord(id, id, VehicleId(0, 0u), store.read("$id.plan") ?: return null, 0, 0)
        for (line in lines.drop(1)) {
            val word = line.substringBefore(' ')
            val rest = line.substringAfter(' ', "")
            val numbers = rest.split(' ').mapNotNull { it.toDoubleOrNull() }
            record = when {
                word == "name" -> record.copy(name = rest)
                word == "vehicle" && numbers.size == 2 -> record.copy(vehicle = VehicleId(numbers[0].toInt(), numbers[1].toInt().toUByte()))
                word == "items" && numbers.size == 1 -> record.copy(itemCount = numbers[0].toInt())
                word == "planned-photos" && numbers.size == 1 -> record.copy(plannedPhotos = numbers[0].toInt())
                word == "reached" && numbers.size == 1 -> record.copy(lastReached = numbers[0].toInt())
                word == "photo" && numbers.size == 2 && numbers[0] in -90.0..90.0 && numbers[1] in -180.0..180.0 ->
                    record.copy(photos = record.photos + LatLon(numbers[0], numbers[1]))
                word == "complete" -> record.copy(complete = true)
                word == "dismissed" -> record.copy(dismissed = true)
                else -> record
            }
        }
        return record.takeIf { !it.dismissed }
    }

    private companion object {
        const val MAGIC = "kft-mission-record 1"
    }
}
