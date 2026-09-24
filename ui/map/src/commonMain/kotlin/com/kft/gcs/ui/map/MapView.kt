package com.kft.gcs.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.UiComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import com.kft.gcs.core.geo.LatLon
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.value.IconRotationAlignment
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.MapRuntimeOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.resource.MapRequestInterceptor
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.sources.rememberRasterTileSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/**
 * The one map composable features use. It takes our own types ([TileSourceConfig], [MapOverlay], [CameraRequest]),
 * so the engine behind it (maplibre-compose today, per ADR-001) can change without touching a feature.
 *
 * Rules carried over from the spike (ADR-001):
 * - F1: the street style is the permanent base style; other basemaps are raster layers drawn on top of it.
 *   Swapping the base style dropped circle/symbol layers in maplibre-compose 0.17.0.
 * - F7: the Esri key is added to Esri requests at fetch time and is never part of the style.
 *
 * `@UiComposable` is explicit because the compiler would otherwise infer MapLibre's applier from the layer calls
 * inside, and every caller would get a "MaplibreComposable where a UI Composable was expected" warning.
 */
@Composable
@UiComposable
fun MapView(
    modifier: Modifier = Modifier,
    basemap: TileSourceConfig = TileSources.Street,
    overlays: List<MapOverlay> = emptyList(),
    cameraRequest: CameraRequest? = null,
) {
    remember { configureRuntimeOnce }
    val vehicleArrow = rememberVectorPainter(VehicleArrow)

    val mapState = rememberMapState(
        baseStyle = BaseStyle.Uri((TileSources.Street.source as TileSourceConfig.Source.VectorStyle).styleUrl),
        initialCameraPosition = CameraPosition(zoom = 2.0),
    ) {
        // Declaration order is drawing order: basemap first, then track, then the vehicle on top.
        val raster = basemap.source as? TileSourceConfig.Source.RasterTiles
        if (raster != null) {
            RasterLayer(
                id = "basemap-${basemap.id}",
                source = rememberRasterTileSource(
                    tiles = listOf(raster.urlTemplate),
                    options = TileSetOptions(maxZoom = raster.maxZoom, attributionHtml = basemap.attribution),
                    tileSize = raster.tileSize,
                ),
            )
        }
        overlays.filterIsInstance<MapOverlay.Track>().forEachIndexed { i, track ->
            if (track.points.size >= 2) {
                LineLayer(
                    id = "track-$i",
                    source = rememberGeoJsonSource(GeoJsonData.JsonString(lineGeoJson(track.points))),
                    color = const(Color(0xFFFFB74D)),
                    width = const(3.dp),
                    cap = const(LineCap.Round),
                    join = const(LineJoin.Round),
                )
            }
        }
        overlays.filterIsInstance<MapOverlay.Vehicle>().forEachIndexed { i, vehicle ->
            SymbolLayer(
                id = "vehicle-$i",
                source = rememberGeoJsonSource(GeoJsonData.JsonString(pointGeoJson(vehicle.position))),
                iconImage = image(vehicleArrow),
                iconSize = const(1.6f),
                // Rotate with the map, so heading 90° always points at map-east even when the map is rotated.
                iconRotationAlignment = const(IconRotationAlignment.Map),
                iconRotate = const((vehicle.headingDeg ?: 0.0).toFloat()),
                // The vehicle must never be hidden to make room for a street label.
                iconAllowOverlap = const(true),
                iconIgnorePlacement = const(true),
            )
        }
    }

    LaunchedEffect(cameraRequest) {
        val request = cameraRequest ?: return@LaunchedEffect
        mapState.animateCameraPosition(CameraPosition(target = request.target.toPosition(), zoom = request.zoom))
    }

    MaplibreMap(
        modifier = modifier,
        state = mapState,
        // A 2D planning map: tilting only makes polygons harder to read.
        interactions = MapInteractions { camera { tilt { enabled = false } } },
    )
}

/**
 * Runs once per process, before the first map exists (maplibre-compose fixes request hooks at runtime creation).
 * Adds the Esri token to Esri tile requests only; every other host gets the URL unchanged.
 */
private val configureRuntimeOnce: Unit by lazy {
    val key = esriApiKey() ?: return@lazy
    val esriHost = "https://ibasemaps-api.arcgis.com/"
    DefaultMapRuntime.configure(
        MapRuntimeOptions(
            requestInterceptor = MapRequestInterceptor(rewriteUrl = { request ->
                if (request.url.startsWith(esriHost)) "${request.url}?token=$key" else null
            }),
        ),
    )
}

private fun LatLon.toPosition() = Position(longitude = longitude, latitude = latitude)

// GeoJSON puts longitude first: [lon, lat].
internal fun pointGeoJson(p: LatLon) =
    """{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[${p.longitude},${p.latitude}]}}"""

internal fun lineGeoJson(points: List<LatLon>) =
    """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[${points.joinToString(",") { "[${it.longitude},${it.latitude}]" }}]}}"""

/** A 24 dp arrow pointing up (north at heading 0): red fill with a white edge so it reads on satellite and street. */
private val VehicleArrow = ImageVector.Builder("vehicle-arrow", 24.dp, 24.dp, 24f, 24f).path(
    fill = SolidColor(Color(0xFFFF5252)),
    stroke = SolidColor(Color.White),
    strokeLineWidth = 1.5f,
) {
    moveTo(12f, 2f)
    lineTo(20f, 21f)
    lineTo(12f, 17f)
    lineTo(4f, 21f)
    close()
}.build()
