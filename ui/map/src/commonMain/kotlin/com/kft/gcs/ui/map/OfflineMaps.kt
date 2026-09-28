package com.kft.gcs.ui.map

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.offline.DownloadProgress
import org.maplibre.compose.offline.DownloadStatus
import org.maplibre.compose.offline.OfflinePack
import org.maplibre.compose.offline.OfflinePackDefinition
import org.maplibre.spatialk.geojson.BoundingBox

/**
 * Maps kept on this device: downloaded areas and imported MBTiles files. The one place features manage them, in our
 * own types, so no feature touches MapLibre (CLAUDE.md §2). An interface so the Maps screen's ViewModel can be
 * tested against a fake.
 *
 * Nothing here switches the map to "offline mode". A downloaded area's tiles sit in MapLibre's tile database, and
 * MapLibre answers every request from that database when the network doesn't, so the ordinary Street map keeps
 * working in the field with no network.
 */
interface OfflineMaps {
    /** Downloaded areas with their progress, oldest first. */
    val regions: Flow<List<OfflineRegion>>

    /** Every basemap this device can show: the built-in ones this build supports, then imported MBTiles files. */
    val basemaps: StateFlow<List<TileSourceConfig>>

    /** Starts downloading [bounds] of [source] at [zooms]. Only for sources whose [OfflinePolicy.allowed] is true. */
    suspend fun download(name: String, source: TileSourceConfig, bounds: GeoBounds, zooms: IntRange)

    fun pause(id: Long)

    fun resume(id: Long)

    /** Deletes the area's tiles, except ones another area or the ordinary cache still uses. */
    suspend fun delete(id: Long)

    /** Empties MapLibre's ordinary cache (tiles seen while browsing). Downloaded areas stay. */
    suspend fun clearCache()

    /**
     * Adds an MBTiles file already copied to [path] (in [MapLibreOfflineMaps]'s import folder). Returns null when it
     * was added, or why it wasn't, in which case the copy is deleted.
     */
    suspend fun importMbtiles(path: String): String?

    /** Removes an imported MBTiles basemap (id from [basemaps]) and deletes its file. */
    suspend fun deleteImported(id: String)
}

/** A downloaded area. [id] is ours (kept in the pack's metadata), not MapLibre's. */
data class OfflineRegion(
    val id: Long,
    val name: String,
    val bounds: GeoBounds,
    val zooms: IntRange,
    val progress: RegionProgress,
)

/**
 * Where a download is. [requiredResources] is null while MapLibre is still working out the total (it reads the
 * style first). Resources are tiles plus the style's fonts and icons, which is why they outnumber tiles.
 */
data class RegionProgress(
    val state: RegionState,
    val tiles: Long = 0,
    val bytes: Long = 0,
    val completedResources: Long = 0,
    val requiredResources: Long? = null,
    val error: String? = null,
)

enum class RegionState { DOWNLOADING, PAUSED, COMPLETE, FAILED }

/**
 * [OfflineMaps] on MapLibre's own offline packs (the same engine on Android and desktop) plus a folder of MBTiles
 * files. [importDir] is that folder, chosen by the app shell (`%APPDATA%\KFT-GCS\maps`, the app's files on Android).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapLibreOfflineMaps(private val importDir: String, private val builtIn: List<TileSourceConfig>) : OfflineMaps {

    // The runtime's request hook (the Esri key) must be set before the runtime exists, and this may be the first
    // thing to touch it (the Maps tab opened before any map).
    private val manager by lazy { configureRuntimeOnce.let { DefaultMapRuntime.instance.offlineManager } }

    private val all = MutableStateFlow(builtIn + scanImports())
    override val basemaps: StateFlow<List<TileSourceConfig>> = all.asStateFlow()

    override val regions: Flow<List<OfflineRegion>> by lazy {
        manager.packs.flatMapLatest { packs ->
            if (packs.isEmpty()) flowOf(emptyList())
            else combine(packs.map { pack -> pack.downloadProgress.map { pack.toRegion(it) } }) { list -> list.filterNotNull().sortedBy { it.id } }
        }
    }

    override suspend fun download(name: String, source: TileSourceConfig, bounds: GeoBounds, zooms: IntRange) {
        require(source.offline.allowed) { "${source.name} may not be downloaded: ${source.offline.reason}" }
        // ponytail: vector styles only (the one downloadable source today). A raster source would need a one-layer
        // style written for it, since a MapLibre pack is always "a style over an area".
        val style = source.source as? TileSourceConfig.Source.VectorStyle ?: error("Only style-based sources can be downloaded")
        val id = (manager.packs.value.mapNotNull { Meta.parse(it.metadata.value)?.id }.maxOrNull() ?: 0) + 1
        val definition = OfflinePackDefinition.TilePyramid(
            styleUrl = style.styleUrl,
            pixelRatio = 1f, // vector tiles don't depend on screen density; 1 keeps icons and fonts to one set
            bounds = BoundingBox(bounds.west, bounds.south, bounds.east, bounds.north),
            minZoom = zooms.first,
            maxZoom = zooms.last,
        )
        // Packs are created paused; resume starts the download.
        manager.resume(manager.create(definition, Meta(id, name).bytes()))
    }

    override fun pause(id: Long) = pack(id)?.let(manager::pause) ?: Unit

    override fun resume(id: Long) = pack(id)?.let(manager::resume) ?: Unit

    override suspend fun delete(id: Long) {
        pack(id)?.let { manager.delete(it) }
    }

    override suspend fun clearCache() = manager.clearAmbientCache()

    override suspend fun importMbtiles(path: String): String? {
        val file = Path(path)
        val problem = when (mbtilesKind(readHead(file))) {
            MbtilesKind.RASTER -> null
            MbtilesKind.VECTOR -> "This MBTiles file holds vector tiles. Only image (raster) MBTiles can be shown for now."
            MbtilesKind.UNKNOWN -> "This isn't an MBTiles file the map can read (no tile format found)."
        }
        if (problem != null) SystemFileSystem.delete(file, mustExist = false)
        refreshImports()
        return problem
    }

    override suspend fun deleteImported(id: String) {
        val file = all.value.firstNotNullOfOrNull { b -> (b.source as? TileSourceConfig.Source.Mbtiles)?.takeIf { b.id == id } } ?: return
        SystemFileSystem.delete(Path(file.path), mustExist = false)
        refreshImports()
    }

    private fun pack(id: Long): OfflinePack? = manager.packs.value.find { Meta.parse(it.metadata.value)?.id == id }

    private fun refreshImports() {
        all.value = builtIn + scanImports()
    }

    private fun scanImports(): List<TileSourceConfig> {
        val dir = Path(importDir)
        if (!SystemFileSystem.exists(dir)) return emptyList()
        return SystemFileSystem.list(dir).filter { it.name.endsWith(".mbtiles", ignoreCase = true) }.sortedBy { it.name }.map { file ->
            TileSourceConfig(
                id = "mbtiles:${file.name}",
                name = file.name.substringBeforeLast('.'),
                source = TileSourceConfig.Source.Mbtiles(SystemFileSystem.resolve(file).toString(), SystemFileSystem.metadataOrNull(file)?.size ?: 0),
                attribution = "Imported: ${file.name}",
                offline = OfflinePolicy.notAllowed("Already on this device."),
            )
        }
    }

    private fun readHead(file: Path): ByteArray = SystemFileSystem.source(file).buffered().use { source ->
        val buffer = Buffer()
        while (buffer.size < MBTILES_HEAD_BYTES) {
            if (source.readAtMostTo(buffer, MBTILES_HEAD_BYTES - buffer.size) == -1L) break // a file shorter than the head
        }
        buffer.readByteArray()
    }

    private fun OfflinePack.toRegion(progress: DownloadProgress): OfflineRegion? {
        val meta = Meta.parse(metadata.value) ?: return null // not one of ours
        val box = (definition as? OfflinePackDefinition.TilePyramid)?.bounds ?: return null
        return OfflineRegion(
            id = meta.id,
            name = meta.name,
            bounds = GeoBounds(south = box.south, west = box.west, north = box.north, east = box.east),
            zooms = definition.minZoom..(definition.maxZoom ?: definition.minZoom),
            progress = progress.toOurs(),
        )
    }
}

internal fun DownloadProgress.toOurs(): RegionProgress = when (this) {
    is DownloadProgress.Healthy -> RegionProgress(
        state = when (status) {
            DownloadStatus.Complete -> RegionState.COMPLETE
            DownloadStatus.Downloading -> RegionState.DOWNLOADING
            DownloadStatus.Paused -> RegionState.PAUSED
        },
        tiles = completedTileCount,
        bytes = completedResourceBytes,
        completedResources = completedResourceCount,
        requiredResources = requiredResourceCount.takeIf { isRequiredResourceCountPrecise },
    )
    is DownloadProgress.Error -> RegionProgress(RegionState.FAILED, error = "$reason: $message")
    is DownloadProgress.TileLimitExceeded -> RegionProgress(RegionState.FAILED, error = "More than $limit tiles")
    else -> RegionProgress(RegionState.PAUSED) // Unknown: MapLibre hasn't reported yet
}

/**
 * What we keep in a pack's metadata: our id and the operator-facing name, as two text lines. MapLibre stores the
 * bytes with the pack and hands them back, so the list survives restarts without a file of our own.
 */
internal data class Meta(val id: Long, val name: String) {
    fun bytes() = "kft1\n$id\n$name".encodeToByteArray()

    companion object {
        fun parse(bytes: ByteArray?): Meta? {
            val lines = bytes?.decodeToString()?.split('\n', limit = 3) ?: return null
            if (lines.size != 3 || lines[0] != "kft1") return null
            return lines[1].toLongOrNull()?.let { Meta(it, lines[2]) }
        }
    }
}
