package com.kft.gcs.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kft.gcs.core.geo.Geodesy
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.ui.map.GeoBounds
import com.kft.gcs.ui.map.MapOverlay
import com.kft.gcs.ui.map.MapViewport
import com.kft.gcs.ui.map.OfflineMaps
import com.kft.gcs.ui.map.OfflinePolicy
import com.kft.gcs.ui.map.OfflineRegion
import com.kft.gcs.ui.map.RegionState
import com.kft.gcs.ui.map.TileMath
import com.kft.gcs.ui.map.TileSourceConfig
import com.kft.gcs.ui.map.TileSources
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/**
 * The platform's "Open…" dialog for MBTiles files. The file is copied into the app's maps folder (MBTiles files
 * are large and binary, and an Android document URI can't be handed to MapLibre), and the copy's path is returned.
 * Null when the operator cancels.
 */
fun interface MbtilesPicker {
    suspend fun pickAndCopy(): String?
}

sealed interface OfflineMapsEffect {
    data class ShowMessage(val text: String) : OfflineMapsEffect
}

/**
 * The Maps tab: download the area on screen for offline use, and manage what's on the device (downloaded areas,
 * imported MBTiles, the browsing cache). The area is simply what the map shows, as in QGC: the operator pans and
 * zooms to it, so there's nothing to draw.
 */
class OfflineMapsViewModel(private val maps: OfflineMaps, private val picker: MbtilesPicker) : ViewModel() {

    /** What the operator has chosen, and the latest area on screen (null until the map reports one). */
    private data class Form(val sourceId: String, val minZoom: Int = 10, val maxZoom: Int = 16, val view: MapViewport? = null)

    private val form = MutableStateFlow(Form(sourceId = TileSources.Street.id))

    private val _effects = Channel<OfflineMapsEffect>(Channel.BUFFERED)
    val effects: Flow<OfflineMapsEffect> = _effects.receiveAsFlow()

    val state: StateFlow<OfflineMapsUiState> = combine(form, maps.regions, maps.basemaps, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), build(form.value, emptyList(), maps.basemaps.value))

    fun onViewChanged(view: MapViewport) = form.update { it.copy(view = view) }

    fun onSourceSelected(id: String) = form.update { it.copy(sourceId = id) }

    /** The zoom range slider: kept in 0..[MAX_ZOOM], and never empty. */
    fun onZoomRangeChanged(min: Int, max: Int) = form.update {
        val lo = min.coerceIn(0, MAX_ZOOM)
        it.copy(minZoom = lo, maxZoom = max.coerceIn(lo, MAX_ZOOM))
    }

    fun onDownloadClicked() {
        val f = form.value
        // Built from the form, not read from [state], which only updates while the screen collects it.
        val s = build(f, emptyList(), maps.basemaps.value)
        val source = builtIns(maps.basemaps.value).find { it.id == s.sourceId }
        val view = f.view
        if (!s.canDownload || source == null || view == null) return
        viewModelScope.launch {
            runCatching { maps.download("${source.name} · ${s.area} · z${f.minZoom}–${f.maxZoom}", source, view.bounds, f.minZoom..f.maxZoom) }
                .onSuccess { say("Downloading ${source.name}. It carries on while you use the other tabs.") }
                .onFailure { say("Couldn't start the download: ${it.message}") }
        }
    }

    fun onPauseClicked(id: Long) = maps.pause(id)

    fun onResumeClicked(id: Long) = maps.resume(id)

    fun onDeleteRegionConfirmed(id: Long) {
        viewModelScope.launch { maps.delete(id) }
    }

    fun onClearCacheClicked() {
        viewModelScope.launch {
            maps.clearCache()
            say("Browsing cache cleared. Downloaded areas are kept.")
        }
    }

    fun onImportClicked() {
        viewModelScope.launch {
            val path = runCatching { picker.pickAndCopy() }.getOrElse { say("Couldn't copy the file: ${it.message}"); return@launch } ?: return@launch
            val problem = maps.importMbtiles(path)
            say(problem ?: "Imported. Choose it with the basemap button on the Fly tab.")
        }
    }

    fun onDeleteImportedConfirmed(id: String) {
        viewModelScope.launch { maps.deleteImported(id) }
    }

    private fun say(text: String) {
        _effects.trySend(OfflineMapsEffect.ShowMessage(text))
    }

    private fun build(f: Form, regions: List<OfflineRegion>, basemaps: List<TileSourceConfig>): OfflineMapsUiState {
        val sources = builtIns(basemaps)
        val source = sources.find { it.id == f.sourceId } ?: sources.first()
        val tiles = f.view?.let { TileMath.downloadTiles(source.source, it.bounds, f.minZoom..f.maxZoom) }
        val blockReason = when {
            !source.offline.allowed -> source.offline.reason
            f.view == null -> "Move the map to the area you need."
            tiles!! > TileSources.MAX_DOWNLOAD_TILES ->
                "Too big: $tiles tiles (the limit is ${TileSources.MAX_DOWNLOAD_TILES}). Zoom the map in, or lower the top zoom."
            else -> null
        }
        val vector = source.source as? TileSourceConfig.Source.VectorStyle
        return OfflineMapsUiState(
            sources = sources.map { SourceOption(it.id, it.name) },
            sourceId = source.id,
            zoomNote = vector?.let { "${source.name} tiles stop at zoom ${it.tileMaxZoom}; closer zooms reuse them at no cost." },
            minZoom = f.minZoom,
            maxZoom = f.maxZoom,
            area = f.view?.let { areaText(it.bounds) },
            estimate = tiles?.takeIf { source.offline.allowed }?.let { estimateText(it, source.offline) },
            blockReason = blockReason,
            canDownload = blockReason == null,
            regions = regions.map(::regionRow),
            imported = basemaps.mapNotNull { b ->
                (b.source as? TileSourceConfig.Source.Mbtiles)?.let { ImportedRow(b.id, b.name, bytesText(it.sizeBytes)) }
            },
            overlays = regions.map { MapOverlay.Polygon(it.bounds.corners, selected = false) },
        )
    }

    private fun builtIns(basemaps: List<TileSourceConfig>) = basemaps.filter { it.source !is TileSourceConfig.Source.Mbtiles }

    companion object {
        /** The deepest zoom offered: Esri imagery's limit, and closer than any survey needs. */
        const val MAX_ZOOM = 19
    }
}

/** "83 tiles, about 2 MB, plus 60 MB of map fonts with the first download". */
internal fun estimateText(tiles: Long, policy: OfflinePolicy): String {
    val base = "$tiles tiles, about ${bytesText(tiles * policy.bytesPerTile)}"
    return if (policy.styleBytes > 0) "$base, plus ${bytesText(policy.styleBytes)} of map fonts with the first download" else base
}

/** "Downloading · 120 tiles · 3.1 MB", with a progress fraction once MapLibre knows the total. */
internal fun regionRow(r: OfflineRegion): RegionRow {
    val p = r.progress
    val amount = "${p.tiles} tiles · ${bytesText(p.bytes)}"
    val detail = when (p.state) {
        RegionState.COMPLETE -> "Done · $amount"
        RegionState.DOWNLOADING -> "Downloading · $amount"
        RegionState.PAUSED -> "Paused · $amount"
        RegionState.FAILED -> "Failed: ${p.error}"
    }
    val fraction = p.requiredResources?.takeIf { it > 0 && p.state != RegionState.COMPLETE }?.let { (p.completedResources.toFloat() / it).coerceIn(0f, 1f) }
    return RegionRow(
        id = r.id,
        name = r.name,
        detail = detail,
        fraction = fraction,
        canPause = p.state == RegionState.DOWNLOADING,
        canResume = p.state == RegionState.PAUSED || p.state == RegionState.FAILED,
    )
}

/** "3.2 × 2.1 km" (width × height through the middle of the box); both in metres when the box is under 1 km. */
internal fun areaText(b: GeoBounds): String {
    val midLat = (b.south + b.north) / 2
    val width = Geodesy.distanceMeters(LatLon(midLat, b.west), LatLon(midLat, b.east))
    val height = Geodesy.distanceMeters(LatLon(b.south, b.west), LatLon(b.north, b.west))
    return if (maxOf(width, height) >= 1000) "${km(width)} × ${km(height)} km" else "${width.roundToLong()} × ${height.roundToLong()} m"
}

// One decimal: "0.8", "12.4".
private fun km(m: Double) = "${(m / 100).roundToLong() / 10.0}"

/** Sizes as people read them: "850 KB", "12 MB", "1.4 GB" (1 MB = 10⁶ bytes, as download sizes are quoted). */
internal fun bytesText(bytes: Long): String = when {
    bytes < 1_000_000 -> "${(bytes / 1000.0).roundToLong()} KB"
    bytes < 1_000_000_000 -> "${(bytes / 1_000_000.0).roundToLong()} MB"
    else -> "${(bytes / 100_000_000.0).roundToLong() / 10.0} GB"
}
