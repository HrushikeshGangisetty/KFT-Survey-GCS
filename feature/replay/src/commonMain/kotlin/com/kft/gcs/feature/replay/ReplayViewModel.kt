package com.kft.gcs.feature.replay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kft.gcs.core.companion.LocalOdometry
import com.kft.gcs.core.companion.Parsed
import com.kft.gcs.core.companion.parseRecord
import com.kft.gcs.core.geo.Geodesy
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.geoio.ImportFile
import com.kft.gcs.core.geoio.readDataFlash
import com.kft.gcs.ui.map.CameraRequest
import com.kft.gcs.ui.map.MapOverlay
import kotlin.math.cos
import kotlin.math.ln
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The platform's "open a file" dialog: the same one Plan imports with. Null when the operator cancels. */
fun interface ReplayFiles {
    suspend fun open(): ImportFile?
}

/**
 * The Replay tab: open an FC log (and optionally the Pi recording's odometry.jsonl), then play the flight back on
 * the map with the vision error against the truth. Nothing here talks to a vehicle; it only reads files.
 *
 * [compute] runs the parsing and analysis (a 10 MB log is ~100k messages), off the UI thread.
 */
class ReplayViewModel(private val files: ReplayFiles, private val compute: CoroutineDispatcher) : ViewModel() {
    private val _state = MutableStateFlow(ReplayUiState())
    val state: StateFlow<ReplayUiState> = _state.asStateFlow()

    private var fcLog: FcLog? = null
    private var logName: String? = null
    private var odometry: List<OdomSample>? = null
    private var odometryName: String? = null
    private var flight: Flight? = null
    private var tracks: List<MapOverlay> = emptyList()
    private var playJob: Job? = null
    private var lastFollowS = Double.NEGATIVE_INFINITY

    fun onOpenLogClicked() = load { file ->
        val log = fcLogFrom(readDataFlash(file.bytes, FC_LOG_MESSAGES))
        require(log.truth.isNotEmpty() || log.visp.isNotEmpty()) { "${file.name} has no GPS, SITL truth or VISP rows. Is it an ArduPilot .bin log?" }
        fcLog = log
        logName = file.name
        // A Pi recording belongs to one flight; keeping it for the next log would only show "doesn't overlap".
        odometry = null
        odometryName = null
    }

    fun onOpenRecordingClicked() = load { file ->
        val records = file.bytes.decodeToString().lineSequence().filter { it.isNotBlank() }.map { line ->
            // Recorder lines are bare contract records; parseRecord reads the WebSocket envelope, so wrap them in one.
            when (val parsed = parseRecord("""{"type":"local_odometry","msg":$line}""")) {
                is Parsed.Ok -> parsed.record as LocalOdometry
                is Parsed.Rejected -> throw IllegalArgumentException("${file.name}: ${parsed.reason}")
            }
        }.toList()
        require(records.isNotEmpty()) { "${file.name} has no odometry records." }
        odometry = odomSamples(records.sortedBy { it.measurementTimePiNs })
        odometryName = file.name
    }

    fun onPlayPauseClicked() = if (playJob?.isActive == true) pause() else play()

    fun onSeek(timeS: Double) {
        val f = flight ?: return
        _state.value = render(f, timeS.coerceIn(f.startS, f.endS))
    }

    fun onSpeedSelected(speed: Int) {
        _state.value = _state.value.copy(speed = speed)
    }

    fun onFollowToggled() {
        _state.value = _state.value.copy(follow = !_state.value.follow)
        lastFollowS = Double.NEGATIVE_INFINITY
        flight?.let { _state.value = render(it, _state.value.timeS) }
    }

    fun onFitClicked() {
        val points = flight?.let { f -> f.truth.map { it.position }.ifEmpty { f.published.map { it.position } } } ?: return
        _state.value = _state.value.copy(follow = false, cameraRequest = fit(points, nextCameraId()))
    }

    private fun play() {
        val f = flight ?: return
        if (_state.value.timeS >= f.endS) onSeek(f.startS)
        playJob = viewModelScope.launch {
            _state.value = _state.value.copy(playing = true)
            while (isActive) {
                delay(TICK_MS)
                val t = (_state.value.timeS + TICK_MS / 1000.0 * _state.value.speed).coerceAtMost(f.endS)
                _state.value = render(f, t)
                if (t >= f.endS) break
            }
            _state.value = _state.value.copy(playing = false)
        }
    }

    private fun pause() {
        playJob?.cancel()
        _state.value = _state.value.copy(playing = false)
    }

    /** Reads a picked file with [read], then re-runs the analysis. A file that can't be used leaves the old flight. */
    private fun load(read: (ImportFile) -> Unit) {
        viewModelScope.launch {
            val file = files.open() ?: return@launch
            pause()
            _state.value = _state.value.copy(loading = true, error = null)
            val result = runCatching {
                withContext(compute) {
                    read(file)
                    fcLog?.let { analyse(it, odometry) }
                }
            }
            result.onFailure { _state.value = _state.value.copy(loading = false, error = it.message ?: "Couldn't read ${file.name}.") }
            result.onSuccess { f ->
                flight = f
                if (f == null) {
                    _state.value = _state.value.copy(loading = false, odometryName = odometryName)
                    return@onSuccess
                }
                tracks = trackOverlays(f)
                _state.value = render(f, f.startS).copy(loading = false)
                onFitClicked()
            }
        }
    }

    private fun render(f: Flight, t: Double): ReplayUiState {
        val s = _state.value
        val truth = nearestBefore(f.truth, t) { it.t }
        val published = recent(f.published, t)
        val vio = recent(f.vio, t)
        val odom = nearestBefore(f.odometry, t) { it.first }?.second
        val truthName = f.truthSource?.label ?: "truth"
        val playhead = buildList {
            add(Stat("VISP error vs $truthName", metres(published?.errorM), PUBLISHED_SLOT))
            if (f.odometry.isNotEmpty()) add(Stat("VIO error vs $truthName", metres(vio?.errorM), VIO_SLOT))
            if (odom != null) add(Stat("VIO state", odom.state))
            add(Stat("Ground speed", truth?.speedMS?.let { "${one(it)} m/s" } ?: "–"))
            add(Stat("Altitude above home", metres(truth?.altM)))
            if (f.truthSource == TruthSource.GPS) add(Stat("Satellites", truth?.sats?.toString() ?: "–", TRUTH_SLOT))
        }
        val st = f.stats
        val whole = listOf(
            Stat("Distance flown", distance(st.distanceM)),
            Stat("Flown with vision", distance(st.visionDistanceM)),
            Stat("Mean error", metres(st.meanErrorM)),
            Stat("Peak error", metres(st.peakErrorM)),
            Stat("Error at anchor", metres(st.anchorErrorM)),
            Stat("Drift, % of distance", st.driftPct?.let { "${(it * 100).toLong() / 100.0} %" } ?: "–"),
            Stat("Alignments", st.alignments.toString()),
            Stat("Vision resets", st.visionResets.toString()),
        )
        val markers = listOfNotNull(
            published?.let { MapOverlay.Vehicle(it.position, heading(f.published, it.t) { p -> p.t to p.position }, PUBLISHED_SLOT, "FC", selected = false) },
            vio?.let { MapOverlay.Vehicle(it.position, heading(f.vio, it.t) { p -> p.t to p.position }, VIO_SLOT, "VIO", selected = false) },
            truth?.let { MapOverlay.Vehicle(it.position, heading(f.truth, it.t) { p -> p.t to p.position }, TRUTH_SLOT, truthName) },
        )
        // Follow moves the camera once a second of flight, not every frame: a camera request animates, and ten a second fight each other.
        val follow = s.follow && truth != null && (t - lastFollowS >= 1.0 || t < lastFollowS)
        if (follow) lastFollowS = t
        return s.copy(
            title = logName,
            subtitle = listOfNotNull(
                "${(f.endS - f.startS).toLong()} s",
                "truth: $truthName",
                "${f.published.size} VISP placed",
                odometryName?.let { "Pi: $it" },
            ).joinToString(" · "),
            odometryName = odometryName,
            notes = f.notes,
            timeS = t,
            startS = f.startS,
            endS = f.endS,
            playhead = playhead,
            whole = whole,
            events = f.events.map { EventLine(it.t, "${one(it.t - f.startS)} s", it.text) },
            chart = chart(f),
            overlays = tracks + markers,
            cameraRequest = if (follow) CameraRequest(truth.position, s.cameraRequest?.zoom ?: 17.0, nextCameraId()) else s.cameraRequest,
        )
    }

    private var cameraId = 0L
    private fun nextCameraId() = ++cameraId

    companion object {
        /** Playback steps every 100 ms of wall time; at 8× that is 0.8 s of flight per step. */
        const val TICK_MS = 100L
        val SPEEDS = listOf(1, 2, 4, 8, 16, 32)

        // Colour slots of the map's vehicle palette (VehicleColors): white truth, teal FC, orange VIO, as in the post-flight
        // tools this screen copies, so "green follows white" reads the same way.
        const val TRUTH_SLOT = 2
        const val PUBLISHED_SLOT = 4
        const val VIO_SLOT = 0
    }
}

/** The latest point at or before [t], if it is at most 1 s old: an older one would show an error that no longer holds. */
private fun recent(points: List<AlignedPoint>, t: Double) = nearestBefore(points, t) { it.t }?.takeIf { t - it.t <= 1.0 }

/** Heading of travel at [t], from the point before the one at [t]; null at the start or when not moving. */
private fun <T> heading(points: List<T>, t: Double, at: (T) -> Pair<Double, LatLon>): Double? {
    val i = nearestBeforeIndex(points, t) { at(it).first } ?: return null
    val here = at(points[i]).second
    val before = points.getOrNull(i - 1)?.let { at(it).second } ?: return null
    return Geodesy.initialBearingDeg(before, here).takeIf { before != here }
}

/** The three tracks, thinned to [MAX_TRACK_POINTS] each: the map redraws them on every playback step. */
private fun trackOverlays(f: Flight) = listOf(
    MapOverlay.Track(thin(f.truth.map { it.position }), ReplayViewModel.TRUTH_SLOT),
    MapOverlay.Track(thin(f.published.map { it.position }), ReplayViewModel.PUBLISHED_SLOT),
    MapOverlay.Track(thin(f.vio.map { it.position }), ReplayViewModel.VIO_SLOT),
)

private const val MAX_TRACK_POINTS = 2000
private const val MAX_CHART_POINTS = 1200

private fun <T> thin(list: List<T>, max: Int = MAX_TRACK_POINTS): List<T> =
    if (list.size <= max) list else list.filterIndexed { i, _ -> i % ((list.size + max - 1) / max) == 0 } + list.last()

private fun chart(f: Flight): ErrorChart {
    fun series(points: List<AlignedPoint>) = thin(points.mapNotNull { p -> p.errorM?.let { p.t to it } }, MAX_CHART_POINTS)
    val published = series(f.published)
    val vio = series(f.vio)
    return ErrorChart(published, vio, (published + vio).maxOfOrNull { it.second }?.coerceAtLeast(1.0) ?: 1.0)
}

/**
 * A camera that shows all of [points]. Web Mercator: a 256-px tile at zoom z covers 156 543·cos(lat)/2^z metres per
 * pixel at the equator scale (OSM wiki "Zoom levels"), so z = log2(156 543·cos(lat)·px / extent) puts the extent in
 * about [FIT_PX] pixels, leaving room for the panels.
 */
private fun fit(points: List<LatLon>, id: Long): CameraRequest {
    val lats = points.map { it.latitude }
    val lons = points.map { it.longitude }
    val centre = LatLon((lats.min() + lats.max()) / 2, (lons.min() + lons.max()) / 2)
    val extent = maxOf(
        Geodesy.distanceMeters(LatLon(lats.min(), centre.longitude), LatLon(lats.max(), centre.longitude)),
        Geodesy.distanceMeters(LatLon(centre.latitude, lons.min()), LatLon(centre.latitude, lons.max())),
        50.0,
    )
    val zoom = ln(156_543.0 * cos(centre.latitude * kotlin.math.PI / 180) * FIT_PX / extent) / ln(2.0)
    return CameraRequest(centre, zoom.coerceIn(3.0, 19.0), id)
}

private const val FIT_PX = 500.0

private fun one(v: Double) = (kotlin.math.round(v * 10) / 10).toString()
private fun metres(v: Double?) = v?.let { "${one(it)} m" } ?: "–"
private fun distance(m: Double) = if (m >= 1000) "${(kotlin.math.round(m / 10) / 100)} km" else "${m.toLong()} m"
