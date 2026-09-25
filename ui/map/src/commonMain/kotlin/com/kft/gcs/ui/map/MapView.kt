package com.kft.gcs.ui.map

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.UiComposable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import com.kft.gcs.core.geo.LatLon
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.value.IconRotationAlignment
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.CircleLayer
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
import kotlin.math.hypot

/**
 * The one map composable features use. It takes our own types ([TileSourceConfig], [MapOverlay], [CameraRequest]),
 * so the engine behind it (maplibre-compose today, per ADR-001) can change without touching a feature.
 *
 * Rules carried over from the spike (ADR-001):
 * - F1: the street style is the permanent base style; other basemaps are raster layers drawn on top of it.
 *   Swapping the base style dropped circle/symbol layers in maplibre-compose 0.17.0.
 * - F7: the Esri key is added to Esri requests at fetch time and is never part of the style.
 * - F4: marker dragging listens on the map's own modifier in the Initial pass. A press on a marker consumes the
 *   whole gesture, so MapLibre (Main pass, ignores consumed events) doesn't pan underneath. Mouse and touch alike.
 * - F10: there is one MapView per window, hoisted in `App()`. Screens pass overlays and callbacks to it; a second
 *   MapView would bring back the dispose/recreate crash.
 *
 * The callbacks are null when the current screen doesn't edit: then clicks and drags only move the camera.
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
    onMapClick: ((LatLon) -> Unit)? = null,
    onMarkerClick: ((String) -> Unit)? = null,
    onMarkerDrag: ((id: String, to: LatLon) -> Unit)? = null,
) {
    remember { configureRuntimeOnce }
    // The gesture code below is set up once per map, so it reads the latest markers and callbacks through these.
    val markers by rememberUpdatedState(overlays.filterIsInstance<MapOverlay.Marker>())
    val mapClick by rememberUpdatedState(onMapClick)
    val markerClick by rememberUpdatedState(onMarkerClick)
    val markerDrag by rememberUpdatedState(onMarkerDrag)
    val vehicleArrow = rememberVectorPainter(VehicleArrow)

    val mapState = rememberMapState(
        baseStyle = BaseStyle.Uri((TileSources.Street.source as TileSourceConfig.Source.VectorStyle).styleUrl),
        initialCameraPosition = CameraPosition(zoom = 2.0),
    ) {
        // Declaration order is drawing order: basemap, track, planned route, markers, and the vehicle on top.
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
        // Always declared, even when empty, so the layer list (and each remembered source) keeps its place.
        LineLayer(
            id = "route",
            source = rememberGeoJsonSource(GeoJsonData.JsonString(routeGeoJson(overlays.filterIsInstance<MapOverlay.Route>()))),
            color = const(Color(0xFF4FC3F7)),
            width = const(3.dp),
        )
        MarkerStyle.entries.forEach { style ->
            val source = rememberGeoJsonSource(GeoJsonData.JsonString(markersGeoJson(markers.filter { it.style == style })))
            CircleLayer(
                id = "markers-$style",
                source = source,
                radius = const(MARKER_RADIUS),
                color = const(style.color),
                strokeColor = const(Color.White),
                strokeWidth = const(2.dp),
            )
            SymbolLayer(
                id = "marker-labels-$style",
                source = source,
                textField = format(span(feature["label"].asString())),
                // OpenFreeMap's glyph server has the Noto families; MapLibre's default font isn't there.
                textFont = const(listOf("Noto Sans Bold")),
                textSize = const(12.sp),
                textColor = const(Color.Black),
                textAllowOverlap = const(true),
                textIgnorePlacement = const(true),
            )
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
        modifier = modifier.pointerInput(mapState) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val click = markerClick
                val drag = markerDrag
                if (click == null && drag == null) return@awaitEachGesture
                val candidates = markers
                val onScreen = candidates.map { mapState.screenLocationFromPosition(it.position.toPosition()) }
                val marker = hitMarker(toDp(down.position), onScreen, MARKER_HIT_RADIUS)?.let(candidates::get)
                    ?: return@awaitEachGesture // not on a marker: the map pans or reports a map click
                down.consume()
                var dragging = false
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    // Below the touch slop it's still a tap: a finger always wobbles a little.
                    if (!dragging && (change.position - down.position).getDistance() < viewConfiguration.touchSlop) continue
                    if (!marker.draggable || drag == null) continue
                    dragging = true
                    val to = mapState.positionFromScreenLocation(toDp(change.position)) ?: continue
                    drag(marker.id, LatLon(to.latitude, to.longitude))
                }
                if (!dragging) click?.invoke(marker.id)
            }
        },
        state = mapState,
        interactions = MapInteractions {
            // A 2D planning map: tilting only makes polygons harder to read.
            camera { tilt { enabled = false } }
            callbacks {
                click {
                    onEvent { event ->
                        val at = event.position
                        val handler = mapClick
                        if (at == null || handler == null) ClickResult.Pass
                        else ClickResult.Consume.also { handler(LatLon(at.latitude, at.longitude)) }
                    }
                }
            }
        },
    )
}

/** How close (on screen) a press must be to a marker to grab it. 24 dp is a fingertip, per the spike (ADR-001 M3). */
private val MARKER_HIT_RADIUS = 24.dp
private val MARKER_RADIUS = 11.dp

private val MarkerStyle.color
    get() = when (this) {
        MarkerStyle.HOME -> Color(0xFF66BB6A)
        MarkerStyle.WAYPOINT -> Color(0xFF4FC3F7)
        MarkerStyle.SELECTED -> Color(0xFFFFC107)
        MarkerStyle.CURRENT -> Color(0xFFE040FB)
    }

/**
 * Index of the marker nearest to [touch] within [radius], or null. A null screen position is a marker that is off
 * screen, so it can't be hit. Works in dp because "did the finger land on it?" is about what the user sees.
 */
internal fun hitMarker(touch: DpOffset, markers: List<DpOffset?>, radius: Dp): Int? =
    markers.withIndex()
        .mapNotNull { (i, m) -> m?.let { i to hypot(touch.x.value - it.x.value, touch.y.value - it.y.value) } }
        .filter { (_, d) -> d <= radius.value }
        .minByOrNull { (_, d) -> d }
        ?.first

/** Pointer events arrive in pixels; the map's projection API works in dp (ADR-001 F5). */
private fun Density.toDp(offset: Offset) = DpOffset(offset.x.toDp(), offset.y.toDp())

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

/** Markers as a FeatureCollection; each point carries its label for the text layer. Labels are ours (numbers, "H"). */
internal fun markersGeoJson(markers: List<MapOverlay.Marker>) = featureCollection(
    markers.map {
        """{"type":"Feature","properties":{"label":"${it.label}"},""" +
            """"geometry":{"type":"Point","coordinates":[${it.position.longitude},${it.position.latitude}]}}"""
    },
)

/** Routes with fewer than two points aren't lines, so they're left out rather than sent as invalid GeoJSON. */
internal fun routeGeoJson(routes: List<MapOverlay.Route>) =
    featureCollection(routes.filter { it.points.size >= 2 }.map { lineGeoJson(it.points) })

private fun featureCollection(features: List<String>) = """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

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
