package com.kft.gcs.ui.map

import com.kft.gcs.core.geo.LatLon

/**
 * Where map tiles come from. Every source is data, so swapping providers (or adding an offline one) is a new
 * entry here, not a code change in a feature (spec S8, `docs/implementation/00-maps-decision.md`).
 */
data class TileSourceConfig(val id: String, val name: String, val source: Source, val attribution: String) {
    sealed interface Source {
        /** A complete MapLibre style (vector tiles, labels, sprites). */
        data class VectorStyle(val styleUrl: String) : Source

        /** Plain image tiles drawn over the base style. [urlTemplate] uses {z}/{x}/{y}. */
        data class RasterTiles(val urlTemplate: String, val tileSize: Int, val maxZoom: Int) : Source
    }
}

object TileSources {
    val Street = TileSourceConfig(
        id = "openfreemap-liberty",
        name = "Street",
        source = TileSourceConfig.Source.VectorStyle("https://tiles.openfreemap.org/styles/liberty"),
        attribution = "OpenFreeMap © OpenMapTiles Data from OpenStreetMap",
    )

    /**
     * Esri World Imagery. Static Basemap Tiles has no imagery style, and this endpoint only accepts the key as
     * `?token=`, which [MapView] adds at request time so the key never sits in the style (ADR-001 F7, F9).
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
    )

    /** What this build can show. Satellite needs an Esri key ([esriApiKey]); street needs nothing. */
    fun available(): List<TileSourceConfig> = if (esriApiKey() != null) listOf(Street, Satellite) else listOf(Street)
}

/** Things features ask the map to draw. Our own types, so no feature ever touches a map library. */
sealed interface MapOverlay {
    /** The aircraft, drawn as an arrow along [headingDeg]. When the heading is unknown the arrow points north. */
    data class Vehicle(val position: LatLon, val headingDeg: Double?) : MapOverlay

    /** The path flown so far. */
    data class Track(val points: List<LatLon>) : MapOverlay
}

/** "Move the camera here." A new [id] means a new request, even to the same place (e.g. "centre" pressed twice). */
data class CameraRequest(val target: LatLon, val zoom: Double, val id: Long)

/** The Esri key for this platform, or null if none is configured. Never logged, never put in a style. */
expect fun esriApiKey(): String?
