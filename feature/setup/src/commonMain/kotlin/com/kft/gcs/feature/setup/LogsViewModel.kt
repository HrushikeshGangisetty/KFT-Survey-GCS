package com.kft.gcs.feature.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kft.gcs.core.mavlink.VehicleId
import com.kft.gcs.core.vehicle.FlightLog
import com.kft.gcs.core.vehicle.VehicleLogRepository
import com.kft.gcs.core.vehicle.VehicleState
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One log in the list. [saved] is where this session downloaded it to, if it did. */
data class LogRow(val id: Int, val title: String, val date: String, val size: String, val saved: String?)

/** The download in progress: which log, how far ([fraction] 0..1), and "8.2 of 17.3 MB · 410 kB/s". */
data class LogDownloadUi(val id: Int, val title: String, val fraction: Float, val text: String)

/**
 * The flight-log page.
 * @property listed a list has been read from the vehicle (so an empty [rows] means "no logs", not "not asked yet").
 * @property hint why the vehicle can't be asked right now, or null.
 */
data class LogsUiState(
    val rows: List<LogRow> = emptyList(),
    val listed: Boolean = false,
    val listing: Boolean = false,
    val download: LogDownloadUi? = null,
    val canList: Boolean = false,
    val canDownload: Boolean = false,
    val hint: String? = "Connect a vehicle to read its logs.",
)

/**
 * Lists the flight controller's logs and downloads one at a time into the logs folder. The list belongs to the vehicle
 * it was read from: selecting another vehicle clears it. A download keeps going on its own vehicle (the repository
 * fixes the vehicle when it starts) until it finishes, fails or is cancelled.
 *
 * @param timeSource for the download rate; tests pass virtual time.
 */
class LogsViewModel(
    private val vehicle: StateFlow<VehicleState>,
    private val logs: VehicleLogRepository,
    private val selectedId: StateFlow<VehicleId?> = MutableStateFlow(null),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ViewModel() {

    private data class Model(
        val logs: List<FlightLog>? = null,
        val listing: Boolean = false,
        val download: LogDownloadUi? = null,
        val saved: Map<Int, String> = emptyMap(),
    )

    private val model = MutableStateFlow(Model())
    private var job: Job? = null

    val state: StateFlow<LogsUiState> = combine(model, vehicle, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), build(model.value, vehicle.value))

    private val _effects = Channel<SetupEffect>(Channel.BUFFERED)
    val effects: Flow<SetupEffect> = _effects.receiveAsFlow()

    init {
        // drop(1): the selection at start isn't a change.
        viewModelScope.launch { selectedId.drop(1).collect { model.update { it.copy(logs = null, saved = emptyMap()) } } }
    }

    fun onListClicked() {
        if (!state.value.canList) return
        model.update { it.copy(listing = true) }
        viewModelScope.launch {
            logs.list()
                .onSuccess { list -> model.update { it.copy(logs = list) } }
                .onFailure { say("Couldn't list the logs: ${it.message}") }
            model.update { it.copy(listing = false) }
        }
    }

    fun onDownloadClicked(id: Int) {
        val log = model.value.logs?.find { it.id == id } ?: return
        if (!state.value.canDownload) return
        val title = "Log $id"
        val started = timeSource.markNow()
        model.update { it.copy(download = LogDownloadUi(id, title, 0f, "Starting…")) }
        job = viewModelScope.launch {
            try {
                logs.download(log) { done ->
                    val seconds = started.elapsedNow().inWholeMilliseconds / 1000.0
                    val rate = if (seconds > 0) " · ${bytes((done / seconds).toLong())}/s" else ""
                    val fraction = if (log.sizeBytes > 0) (done.toFloat() / log.sizeBytes).coerceIn(0f, 1f) else 1f
                    model.update { it.copy(download = LogDownloadUi(id, title, fraction, "${bytes(done)} of ${bytes(log.sizeBytes)}$rate")) }
                }
                    .onSuccess { path -> model.update { it.copy(saved = it.saved + (id to path)) }; say("$title saved to $path") }
                    .onFailure { say("$title not saved: ${it.message}") }
            } finally {
                model.update { it.copy(download = null) }
            }
        }
    }

    /** Stops the download. The repository deletes the partial file and tells the FC to stop sending. */
    fun onCancelClicked() {
        if (job?.isActive != true) return
        job?.cancel()
        say("Download cancelled. Nothing was saved.")
    }

    private fun say(text: String) {
        _effects.trySend(SetupEffect.ShowMessage(text))
    }

    private fun build(m: Model, v: VehicleState): LogsUiState {
        val hint = when {
            !v.connected -> "Connect a vehicle to read its logs."
            v.login?.allowsTraffic == false -> "${v.login?.label}. Log downloads wait for the login."
            v.armed -> "The vehicle is armed. ArduPilot only sends its logs on the ground, disarmed."
            else -> null
        }
        val idle = hint == null && !m.listing && m.download == null
        return LogsUiState(
            rows = m.logs.orEmpty().sortedByDescending { it.id }.map { log ->
                LogRow(log.id, "Log ${log.id}", log.timeUtcSeconds?.let { utcText(it) } ?: "No date (no GPS time when it started)", bytes(log.sizeBytes), m.saved[log.id])
            },
            listed = m.logs != null,
            listing = m.listing,
            download = m.download,
            canList = idle,
            canDownload = idle,
            hint = hint,
        )
    }
}

/** "17.3 MB", "512 kB", "90 B": decimal units, as file managers on both platforms show them. */
internal fun bytes(n: Long): String = when {
    n >= 1_000_000 -> "${(n / 100_000) / 10.0} MB"
    n >= 1_000 -> "${n / 1_000} kB"
    else -> "$n B"
}

/**
 * "2025-10-25 09:14 UTC" from seconds since 1970. UTC on purpose: it is what the FC logged (GPS time), whatever the
 * tablet's time zone. Days to a civil date by Howard Hinnant's `civil_from_days` ("chrono-Compatible Low-Level Date
 * Algorithms", public domain), so common code needs no date library.
 */
internal fun utcText(seconds: Long): String {
    val days = seconds.floorDiv(86_400L)
    val secOfDay = seconds - days * 86_400L
    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097                                   // day of the 400-year era, 0..146096
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365 // year of the era, 0..399
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)             // day of the year, starting 1 March
    val mp = (5 * doy + 2) / 153                                  // month, 0 = March
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = yoe + era * 400 + if (month <= 2) 1 else 0
    fun two(n: Long) = n.toString().padStart(2, '0')
    return "$year-${two(month)}-${two(day)} ${two(secOfDay / 3_600)}:${two(secOfDay % 3_600 / 60)} UTC"
}
