package com.kft.gcs.ui.map

import com.kft.gcs.core.geo.LatLon

/**
 * Where map tiles come from. Every source is data, so swapping providers (or adding an offline one) is a new
 * entry here, not a code change in a feature (spec S8, `docs/implementation/00-maps-decision.md`).
 * [offline] says whether the provider's terms let us download an area in advance (GS-1).
 */
data class TileSourceConfig(
    val id: String,
    val name: String,
    val source: Source,
    val attribution: String,
    val offline: OfflinePolicy = OfflinePolicy.notAllowed("No offline terms recorded for this source."),
) {
    sealed interface Source {
        /**
         * A complete MapLibre style (vector tiles, labels, sprites). [tileMaxZoom] is the deepest zoom the tiles
         * exist at; closer zooms stretch those tiles, so a download needs nothing beyond it.
         */
        data class VectorStyle(val styleUrl: String, val tileMaxZoom: Int) : Source

        /** Plain image tiles drawn over the base style. [urlTemplate] uses {z}/{x}/{y}. */
        data class RasterTiles(val urlTemplate: String, val tileSize: Int, val maxZoom: Int) : Source

        /**
         * An imported raster MBTiles file on this device, [sizeBytes] long. Its zoom range and bounds come from the
         * file itself.
         */
        data class Mbtiles(val path: String, val sizeBytes: Long) : Source
    }
}

/**
 * Whether an area of this source may be downloaded for offline use, and the reason in words (shown to the operator
 * when it's not allowed). [bytesPerTile] is the average tile size used for the estimate before a download;
 * [styleBytes] is what the style itself adds (fonts, icons), fetched with the first download.
 */
data class OfflinePolicy(val allowed: Boolean, val reason: String, val bytesPerTile: Int = 0, val styleBytes: Long = 0) {
    companion object {
        fun notAllowed(reason: String) = OfflinePolicy(allowed = false, reason = reason)
    }
}

object TileSources {
    const val LIBERTY_STYLE = "https://tiles.openfreemap.org/styles/liberty"

    /**
     * OpenFreeMap Liberty. Offline: allowed, checked 2026-09-28 (docs/decisions/GS-1-offline-tiles.md). Commercial use is
     * allowed and they publish planet MBTiles for offline use; their terms forbid automated collection "without
     * permission", so downloads are started by the operator, one area at a time, and capped ([MAX_DOWNLOAD_TILES]).
     * 25 KB per tile is a rough average for vector tiles at z10–14 (dense cities run higher, farmland lower); the
     * estimate is a guide, and the download's own progress shows the real size. [styleBytes] is measured: the Pass 23
     * run downloaded 83 tiles and 62 MB in all, so the style's fonts and icons are about 60 MB.
     */
    val Street = TileSourceConfig(
        id = "openfreemap-liberty",
        name = "Street",
        source = TileSourceConfig.Source.VectorStyle(LIBERTY_STYLE, tileMaxZoom = 14),
        attribution = "OpenFreeMap © OpenMapTiles Data from OpenStreetMap",
        offline = OfflinePolicy(allowed = true, reason = "OpenFreeMap allows offline use.", bytesPerTile = 25_000, styleBytes = 60_000_000),
    )

    /**
     * Esri World Imagery. Static Basemap Tiles has no imagery style, and this endpoint only accepts the key as
     * `?token=`, which [MapView] adds at request time so the key never sits in the style (ADR-001 F7, F9).
     * Offline download is **not allowed**: Esri's documentation says ArcGIS tiles may be taken offline only with Esri
     * software, and requesting them systematically for offline use through other apps is prohibited (GS-1). Tiles
     * seen while online still stay in MapLibre's ordinary cache.
     */
    val Satellite = TileSourceConfig(
        id = "esri-world-imagery",
        name = "Satellite",
        source = TileSourceConfig.Source.RasterTiles(
            urlTemplate = "https://ibasemaps-api.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
            tileSize = 256,
            maxZoom = 19,
        ),
        attribution = "Powered by Esri | Esri, Maxar, Earthstar Geographics",
        offline = OfflinePolicy.notAllowed(
            "Esri allows offline imagery only in Esri's own software. Import an MBTiles file of your own imagery instead.",
        ),
    )

    /** What this build can show. Satellite needs an Esri key ([esriApiKey]); street needs nothing. */
    fun available(): List<TileSourceConfig> = if (esriApiKey() != null) listOf(Street, Satellite) else listOf(Street)

    /**
     * The most tiles one download may ask for. OpenFreeMap is a free service with no SLA; a field-sized area at
     * z10–14 is a few hundred tiles, so this only stops a mistake (a whole country at z14), not real work.
     */
    const val MAX_DOWNLOAD_TILES = 10_000L
}

/** Things features ask the map to draw. Our own types, so no feature ever touches a map library. */
sealed interface MapOverlay {
    /** The aircraft, drawn as an arrow along [headingDeg]. When the heading is unknown the arrow points north. */
    data class Vehicle(val position: LatLon, val headingDeg: Double?) : MapOverlay

    /** The path flown so far. */
    data class Track(val points: List<LatLon>) : MapOverlay

    /**
     * A piece of the planned path, drawn in [style]. A plan is several of these: the transit from home, waypoint
     * legs, a survey's photo lines and the turns between them. Drawn in a different colour from [Track], the path
     * already flown.
     */
    data class Route(val points: List<LatLon>, val style: RouteStyle = RouteStyle.PLAN) : MapOverlay

    /**
     * An area, such as a survey polygon: outline plus a light fill. With fewer than 3 corners it's drawn as a line
     * (still being drawn). [selected] draws it brighter, for the one being edited.
     */
    data class Polygon(val corners: List<LatLon>, val selected: Boolean) : MapOverlay

    /** Where photos were taken: small dots, one per photo. */
    data class Photos(val points: List<LatLon>) : MapOverlay

    /**
     * A labelled point (home, a waypoint). [id] is what click and drag callbacks report back. Only [draggable]
     * markers can be dragged; any marker can be clicked when the map has a click callback.
     */
    data class Marker(
        val id: String,
        val position: LatLon,
        val label: String,
        val style: MarkerStyle,
        val draggable: Boolean = false,
    ) : MapOverlay
}

/**
 * How a [MapOverlay.Route] looks, by what the vehicle does on it:
 * - [PLAN]: a leg between hand-placed waypoints. Normal line.
 * - [PHOTO]: a survey line with the camera on. The part that matters: thick and bright, with direction arrows.
 * - [TURN]: survey flight with the camera off (run-in, run-out, lead-in, the turn to the next line). Thin, faded grey.
 * - [TRANSIT]: to or from home. Dashed grey.
 */
enum class RouteStyle { PLAN, PHOTO, TURN, TRANSIT }

/**
 * How a [MapOverlay.Marker] looks. The map decides the colours, so every screen shows the same meaning the same way.
 * [START] and [END] ("S", "E") mark where a survey begins and ends; they're labels only, so the map never lets them
 * be clicked or dragged (a corner or a map click next to them must still work).
 * [START_OPTION] is a corner of the survey area the operator can tap to start there (small, grey); [START_CORNER] is
 * the chosen one (large, white like "S"), not clickable, like the labels. Declaration order is drawing order, so "S" stays on top.
 */
enum class MarkerStyle { HOME, WAYPOINT, SELECTED, CURRENT, CORNER, START_OPTION, START_CORNER, START, END }

/** "Move the camera here." A new [id] means a new request, even to the same place (e.g. "centre" pressed twice). */
data class CameraRequest(val target: LatLon, val zoom: Double, val id: Long)

/** The Esri key for this platform, or null if none is configured. Never logged, never put in a style. */
expect fun esriApiKey(): String?

/**
 * The `mbtiles://<absolute path>` URL MapLibre reads a local file through. MapLibre checks that the text after
 * `://` is an absolute path (`std::filesystem::path::is_absolute`, `mbtiles_file_source.cpp`), so it's `/data/…` on
 * Android and `C:/…` on Windows: forward slashes, no slash before the drive letter, spaces escaped (MapLibre
 * percent-decodes it). maplibre-compose's own helper left the backslashes in, which MapLibre refused (Pass 23 run).
 */
internal expect fun mbtilesUrl(path: String): String
