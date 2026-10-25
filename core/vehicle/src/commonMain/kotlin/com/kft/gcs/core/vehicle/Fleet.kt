package com.kft.gcs.core.vehicle

import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.MavLink
import com.kft.gcs.core.mavlink.VehicleId
import com.kft.gcs.core.mavlink.VehicleRecorder
import com.kft.gcs.core.mavlink.forVehicle
import com.kft.gcs.core.mavlink.withStates
import com.kft.gcs.core.mission.Mission
import com.kft.gcs.core.mission.MissionItem
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One vehicle as the screens see it: who it is, what it reports, and whether it can be trusted.
 * @property slot 0, 1, 2… in the order vehicles were first heard. The map and the switcher pick the vehicle's colour by it.
 * @property duplicateId two autopilots on its link are using its system id (ADR-005 §4): nothing can be sent to it.
 * @property link its link in words ("UDP listen :14550"), for the fleet dashboard.
 */
data class FleetVehicle(val id: VehicleId, val slot: Int, val state: VehicleState, val duplicateId: Boolean = false, val link: String = "")

/**
 * Every vehicle on every link (ADR-005 §2), and which one is selected.
 *
 * ```
 * links ──▶ reconcile() ──▶ one Entry per (link, system id), created the first time its heartbeat is heard:
 *                              VehicleRepository (state, KFT login) · MissionRepository · ParamRepository · tlog
 *                           each fed by a view of its link that holds only that vehicle
 * vehicles  = every entry's state, for the map and the switcher
 * selected  = the selected entry's state; missions / params = the selected entry's repositories
 * ```
 * The per-vehicle classes are the single-vehicle ones from before, unchanged: each sees its link through
 * [forVehicle] (only its own vehicle), frames filtered to its system id, and a sender bound to it.
 *
 * A vehicle stays in the list when its heartbeat stops (it shows as not connected) and is forgotten only when its
 * link is disconnected.
 *
 * @param recorder writes each vehicle's frames to its tlog; null (tests, or no storage) records nothing.
 * @param logStore where downloaded flight logs go; null (tests, or no storage) and a download says so.
 */
@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
class Fleet(
    private val scope: CoroutineScope,
    links: StateFlow<List<MavLink>>,
    private val loginKey: ByteArray?,
    private val sync: MissionSync,
    private val recorder: VehicleRecorder? = null,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val logStore: LogStore? = null,
) {
    /** Everything that belongs to one vehicle. Cancelling [scope] stops its login, its requests and its tlog. */
    private class Entry(
        val id: VehicleId,
        val slot: Int,
        val scope: CoroutineScope,
        val link: StateFlow<LinkState>,
        val state: StateFlow<VehicleState>,
        val missions: MissionRepository,
        val params: ParamRepository,
        val setup: VehicleSetupRepository,
        val logs: VehicleLogRepository,
    )

    private val entries = MutableStateFlow<List<Entry>>(emptyList())
    private var nextSlot = 0 // touched only by the one collector below

    private val _selectedId = MutableStateFlow<VehicleId?>(null)

    /** The vehicle Fly's HUD, Plan's transfers and Params act on. The first vehicle heard is selected by itself. */
    val selectedId: StateFlow<VehicleId?> = _selectedId.asStateFlow()

    /** Every known vehicle with its latest state, by link and system id (so lists read 1, 2, 3 whichever was heard first). */
    val vehicles: StateFlow<List<FleetVehicle>> = entries
        .flatMapLatest { list ->
            if (list.isEmpty()) flowOf(emptyList())
            else combine(list.sortedWith(compareBy({ it.id.link }, { it.id.systemId })).map { e ->
                combine(e.state, e.link) { state, link -> FleetVehicle(e.id, e.slot, state, link.isDuplicate(), link.summary()) }
            }) { it.toList() }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The selected vehicle's state, or an empty [VehicleState] (not connected) when there is no vehicle. */
    val selected: StateFlow<VehicleState> = combine(vehicles, _selectedId) { list, id -> list.find { it.id == id }?.state ?: VehicleState() }
        .stateIn(scope, SharingStarted.Eagerly, VehicleState())

    init {
        // One collector for every link's state, so entries are only ever added and removed by one coroutine.
        scope.launch {
            links.withStates().collect(::reconcile)
        }
    }

    /** Selects [id] if it is a known vehicle. A transfer already running stays with the vehicle it started on. */
    fun select(id: VehicleId) {
        if (entries.value.none { it.id == id }) return
        _selectedId.value = id
        sync.select(id)
    }

    /** Adds an entry for each vehicle heard for the first time, and drops the entries of links that have closed. */
    private fun reconcile(links: List<Pair<MavLink, LinkState>>) {
        val open = links.map { (link, _) -> link.id }.toSet()
        val (kept, gone) = entries.value.partition { it.id.link in open }
        gone.forEach { it.scope.cancel() }
        val new = links.flatMap { (link, state) ->
            (state as? LinkState.Connected)?.vehicles.orEmpty()
                .map { VehicleId(link.id, it.systemId) }
                .filter { id -> kept.none { it.id == id } }
                .map { id -> entry(link, id) }
        }
        if (gone.isEmpty() && new.isEmpty()) return
        val all = kept + new
        entries.value = all
        // Keep the selection while its vehicle exists; otherwise fall back to the first one (or none).
        if (all.none { it.id == _selectedId.value }) {
            _selectedId.value = all.firstOrNull()?.id
            sync.select(_selectedId.value)
        }
    }

    private fun entry(link: MavLink, id: VehicleId): Entry {
        // A child of the app scope: cancelled with it, and by itself when the link goes. Supervisor, so a failure in
        // one vehicle's tlog doesn't stop its login or its neighbours.
        val child = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val frames = link.frames.filter { it.systemId == id.systemId }
        val view = link.state.map { it.forVehicle(id.systemId) }.stateIn(child, SharingStarted.Eagerly, link.state.value.forVehicle(id.systemId))
        val sender = link.senderFor(id.systemId)
        val repository = VehicleRepository(child, frames, view, sender, loginKey, timeSource)
        if (recorder != null) child.launch { recorder.record(id, frames) }
        return Entry(
            id, nextSlot++, child, view, repository.state,
            DefaultMissionRepository(frames, view, repository.state, sender),
            DefaultParamRepository(frames, view, repository.state, sender),
            DefaultVehicleSetupRepository(frames, view, repository.state, sender),
            DefaultVehicleLogRepository(frames, view, repository.state, sender, logStore),
        )
    }

    private fun selectedEntry() = entries.value.find { it.id == _selectedId.value }

    /**
     * Mission transfers with the selected vehicle. The vehicle is fixed when a call starts: the whole transfer, and
     * the note in [MissionSync] of what that vehicle now holds, stay with it even if the selection changes meanwhile.
     */
    val missions: MissionRepository = missionRepository(NO_VEHICLE) { selectedEntry() }

    /** Mission transfers with one named vehicle, selected or not: a job uploads each mission to its own vehicle. */
    fun missionsOf(id: VehicleId): MissionRepository =
        missionRepository("Vehicle ${id.systemId} is not connected.") { entries.value.find { it.id == id } }

    private fun missionRepository(missing: String, entry: () -> Entry?) = object : MissionRepository {
        override suspend fun upload(items: List<MissionItem>, onProgress: (Int, Int) -> Unit): Result<Unit> {
            val e = entry() ?: return noVehicle()
            return e.missions.upload(items, onProgress).onSuccess { sync.vehicleHolds(e.id, items) }
        }

        override suspend fun download(onProgress: (Int, Int) -> Unit): Result<Mission> {
            val e = entry() ?: return noVehicle()
            return e.missions.download(onProgress).onSuccess { sync.vehicleHolds(e.id, it.items) }
        }

        override suspend fun clear(): Result<Unit> {
            val e = entry() ?: return noVehicle()
            return e.missions.clear().onSuccess { sync.vehicleHolds(e.id, emptyList()) }
        }

        private fun <T> noVehicle(): Result<T> = Result.failure(MissionTransferException(missing))
    }

    /** Parameters of the selected vehicle; like [missions], each call stays with the vehicle it started on. */
    val params: ParamRepository = object : ParamRepository {
        // The selected vehicle's values: switching vehicle switches the map, so one drone's values never show for another.
        override val known: StateFlow<Map<String, Param>> = combine(entries, _selectedId) { list, id -> list.find { it.id == id } }
            .flatMapLatest { it?.params?.known ?: flowOf(emptyMap()) }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

        override suspend fun downloadAll(onProgress: (Int, Int) -> Unit): Result<List<Param>> =
            selectedEntry()?.params?.downloadAll(onProgress) ?: Result.failure(ParamTransferException(NO_VEHICLE))

        override suspend fun set(param: Param, value: Float): ParamSetResult =
            selectedEntry()?.params?.set(param, value) ?: ParamSetResult.NotApplied(NO_VEHICLE)
    }

    /**
     * Calibrations and the reboot for the selected vehicle. Like [params], a command goes to the vehicle selected when it
     * is sent; [VehicleSetupRepository.reports] follow the vehicle selected when collection starts.
     */
    val setup: VehicleSetupRepository = object : VehicleSetupRepository {
        override suspend fun run(command: SetupCommand): SetupResult =
            selectedEntry()?.setup?.run(command) ?: SetupResult.Failed(NO_VEHICLE)

        override val reports: Flow<SetupReport> = flow { selectedEntry()?.setup?.reports?.let { emitAll(it) } }
    }

    /** Flight logs of the selected vehicle; like [params], each call stays with the vehicle it started on. */
    val logs: VehicleLogRepository = object : VehicleLogRepository {
        override suspend fun list(): Result<List<FlightLog>> =
            selectedEntry()?.logs?.list() ?: Result.failure(LogTransferException(NO_VEHICLE))

        override suspend fun download(log: FlightLog, onProgress: (Long) -> Unit): Result<String> =
            selectedEntry()?.logs?.download(log, onProgress) ?: Result.failure(LogTransferException(NO_VEHICLE))
    }

    private companion object {
        const val NO_VEHICLE = "No vehicle connected."
    }
}

/**
 * A short label per vehicle: its system id ("2"). When two links carry the same system id, those get the link number
 * too ("L1·1", "L2·1"), so no two labels are ever the same. The map, the switcher and the Plan tab all use these.
 */
fun vehicleLabels(ids: List<VehicleId>): Map<VehicleId, String> = ids.associateWith { id ->
    if (ids.count { it.systemId == id.systemId } > 1) "L${id.link}·${id.systemId}" else "${id.systemId}"
}

private fun LinkState.summary() = when (this) {
    LinkState.Disconnected -> ""
    is LinkState.Connecting -> config.summary
    is LinkState.Connected -> config.summary
}

private fun LinkState.isDuplicate() = (this as? LinkState.Connected)?.vehicle?.duplicateId == true
