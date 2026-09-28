package com.kft.gcs.ui.map

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.PathBuilder
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
import org.maplibre.compose.expressions.value.SymbolPlacement
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.MapRuntimeOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.resource.MapRequestInterceptor
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
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
 * [onViewChanged] hears the area on screen each time the camera settles (the Maps tab downloads "what you see").
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
    onMarkerDragEnd: ((id: String) -> Unit)? = null,
    onViewChanged: ((MapViewport) -> Unit)? = null,
) {
    remember { configureRuntimeOnce }
    // The gesture code below is set up once per map, so it reads the latest markers and callbacks through these.
    val markers by rememberUpdatedState(overlays.filterIsInstance<MapOverlay.Marker>())
    // What a press can grab: every marker except the S/E labels and the chosen start corner (tapping it changes nothing).
    val grabbable by rememberUpdatedState(markers.filter { it.style !in NOT_GRABBABLE })
    val lineArrow = rememberVectorPainter(LineArrow)
    val mapClick by rememberUpdatedState(onMapClick)
    val markerClick by rememberUpdatedState(onMarkerClick)
    val markerDrag by rememberUpdatedState(onMarkerDrag)
    val markerDragEnd by rememberUpdatedState(onMarkerDragEnd)
    val viewChanged by rememberUpdatedState(onViewChanged)
    val vehicleArrow = rememberVectorPainter(VehicleArrow)

    val mapState = rememberMapState(
        baseStyle = BaseStyle.Uri((TileSources.Street.source as TileSourceConfig.Source.VectorStyle).styleUrl),
        initialCameraPosition = CameraPosition(zoom = 2.0),
    ) {
        // Declaration order is drawing order: basemap, planned route, flown track, markers, and the vehicle on top.
        when (val source = basemap.source) {
            is TileSourceConfig.Source.RasterTiles -> RasterLayer(
                id = "basemap-${basemap.id}",
                source = rememberRasterTileSource(
                    tiles = listOf(source.urlTemplate),
                    options = TileSetOptions(maxZoom = source.maxZoom, attributionHtml = basemap.attribution),
                    tileSize = source.tileSize,
                ),
            )
            // The mbtiles:// URL is a TileJSON URL: MapLibre reads the file's own zoom range and bounds from it.
            is TileSourceConfig.Source.Mbtiles ->
                RasterLayer(id = "basemap-${basemap.id}", source = rememberRasterTileSource(mbtilesUrl(source.path), tileSize = 256))
            is TileSourceConfig.Source.VectorStyle -> Unit // the street style is always underneath (F1)
        }
        // Always declared, even when empty, so the layer list (and each remembered source) keeps its place. Areas go
        // lowest, so the route and the markers inside them stay readable.
        // The area being edited is drawn stronger than the others: two sources, so each gets plain constant styles.
        val polygons = overlays.filterIsInstance<MapOverlay.Polygon>()
        val areas = listOf(true, false).associateWith { selected ->
            rememberGeoJsonSource(GeoJsonData.JsonString(polygonsGeoJson(polygons.filter { it.selected == selected })))
        }
        areas.forEach { (selected, source) ->
            FillLayer(id = "areas-fill-$selected", source = source, color = const(MapColors.AREA), opacity = const(if (selected) 0.2f else 0.1f))
        }
        // The plan, faintest first, so a photo line is never under a turn. All under the track: once the vehicle flies
        // the plan, the flown path must show on top of the planned one.
        val routes = overlays.filterIsInstance<MapOverlay.Route>()
        fun routeSource(style: RouteStyle) = GeoJsonData.JsonString(routeGeoJson(routes.filter { it.style == style }))
        // Camera-off flight is neutral grey, so the cyan left on the map is exactly where photos are taken. Grey alone
        // disappears on the pale street map, so both keep a dark casing, faded like the line itself.
        CasedLine("route-turn", rememberGeoJsonSource(routeSource(RouteStyle.TURN)), MapColors.NEUTRAL, 1.5.dp, opacity = 0.6f)
        CasedLine("route-transit", rememberGeoJsonSource(routeSource(RouteStyle.TRANSIT)), MapColors.NEUTRAL, 2.dp, opacity = 0.8f, dashed = true)
        CasedLine("route-plan", rememberGeoJsonSource(routeSource(RouteStyle.PLAN)), MapColors.ROUTE, 3.dp)
        val photoLines = rememberGeoJsonSource(routeSource(RouteStyle.PHOTO))
        CasedLine("route-photo", photoLines, MapColors.ROUTE, 4.dp)
        // Small arrows along each photo line, in the direction it's flown (GeoJSON lines keep the flight order).
        SymbolLayer(
            id = "route-photo-arrows",
            source = photoLines,
            placement = const(SymbolPlacement.Line),
            spacing = const(140.dp),
            iconSize = const(1.5f),
            iconImage = image(lineArrow),
            iconRotationAlignment = const(IconRotationAlignment.Map),
            iconAllowOverlap = const(true),
            iconIgnorePlacement = const(true),
        )
        // The area outlines on top of the plan, so the boundary the operator is editing always stays readable.
        areas.forEach { (selected, source) -> CasedLine("areas-outline-$selected", source, MapColors.AREA, if (selected) 3.dp else 1.5.dp) }
        overlays.filterIsInstance<MapOverlay.Track>().forEachIndexed { i, track ->
            if (track.points.size >= 2) {
                CasedLine("track-$i", rememberGeoJsonSource(GeoJsonData.JsonString(lineGeoJson(track.points))), MapColors.TRACK, 3.dp)
            }
        }
        // Photo dots under the markers: there can be hundreds, and a waypoint must never hide behind them.
        CircleLayer(
            id = "photos",
            source = rememberGeoJsonSource(GeoJsonData.JsonString(pointsGeoJson(overlays.filterIsInstance<MapOverlay.Photos>().flatMap { it.points }))),
            radius = const(4.dp),
            color = const(MapColors.PHOTO),
            strokeColor = const(MapColors.OUTLINE),
            strokeWidth = const(1.dp),
        )
        MarkerStyle.entries.forEach { style ->
            val source = rememberGeoJsonSource(GeoJsonData.JsonString(markersGeoJson(markers.filter { it.style == style })))
            CircleLayer(
                id = "markers-$style",
                source = source,
                // Corners are handles, not numbered stops: smaller, so a dense polygon doesn't hide its own outline.
                radius = const(
                    when (style) {
                        MarkerStyle.CORNER, MarkerStyle.START_OPTION -> CORNER_RADIUS
                        MarkerStyle.START, MarkerStyle.END -> LABEL_RADIUS
                        MarkerStyle.START_CORNER -> MARKER_RADIUS
                        else -> MARKER_RADIUS
                    },
                ),
                color = const(style.color),
                strokeColor = const(MapColors.OUTLINE),
                strokeWidth = const(1.5.dp),
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

    // Waits for the camera to rest for a moment, so a pan reports once, not on every frame of it.
    LaunchedEffect(mapState) {
        snapshotFlow { mapState.cameraPosition }.collectLatest {
            delay(VIEW_SETTLE_MS)
            val handler = viewChanged ?: return@collectLatest
            // Null until the map has a size on screen.
            val box = runCatching { mapState.getVisibleBounds() }.getOrNull() ?: return@collectLatest
            handler(MapViewport(GeoBounds(south = box.south, west = box.west, north = box.north, east = box.east), it.zoom))
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
                val candidates = grabbable
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
                if (dragging) markerDragEnd?.invoke(marker.id) else click?.invoke(marker.id)
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
private const val VIEW_SETTLE_MS = 300L
private val CORNER_RADIUS = 7.dp
private val LABEL_RADIUS = 9.dp
private val NOT_GRABBABLE = setOf(MarkerStyle.START, MarkerStyle.END, MarkerStyle.START_CORNER)

/**
 * Overlay colours. The same in every app theme, because the map underneath doesn't change with the theme, and never
 * the KFT brand navy: a dark blue line vanishes on satellite imagery (dark fields, water) and reads as a road on the
 * street map. Instead: saturated, light colours that basemaps use little (cyan, magenta, yellow, lime, orange), each
 * with a thin near-black outline. The fill carries the colour on the pale street map; the outline separates it from
 * bright or busy satellite tiles. Every colour here is at least 5.4:1 against [OUTLINE].
 */
private object MapColors {
    val OUTLINE = Color(0xE6101010)
    val ROUTE = Color(0xFF00E5FF) // cyan: the planned path, and the survey lines with the camera on
    val NEUTRAL = Color(0xFFD0D0D0) // light grey: survey flight with the camera off (lead-ins, turns) and transit
    val TRACK = Color(0xFFFF40C8) // magenta: the path already flown, never mistaken for the plan
    val AREA = Color(0xFFFF9100) // orange: survey areas and their corner handles
    val PHOTO = Color(0xFF76FF03) // lime: photo positions
    val HOME = Color(0xFF00E676)
    val SELECTED = Color(0xFFFFD600)
    val VEHICLE = Color(0xFFFF3D00)
}

private val MarkerStyle.color
    get() = when (this) {
        MarkerStyle.HOME -> MapColors.HOME
        MarkerStyle.WAYPOINT -> MapColors.ROUTE
        MarkerStyle.SELECTED -> MapColors.SELECTED
        MarkerStyle.CURRENT -> MapColors.TRACK
        MarkerStyle.CORNER -> MapColors.AREA
        MarkerStyle.START, MarkerStyle.END -> Color.White
        MarkerStyle.START_OPTION -> MapColors.NEUTRAL
        // White like "S": it's the survey's start. Not yellow, which already means "the selected corner handle".
        MarkerStyle.START_CORNER -> Color.White
    }

/**
 * A line with a thin dark outline. MapLibre lines have no stroke, so the outline is a second line 2 dp wider, drawn
 * first ("casing", as road maps do it). Both read the same source. [dashed] dashes the line but not the casing, so a
 * dashed line reads as grey dashes on a dark track, on any basemap.
 */
@Composable
@MaplibreComposable
private fun CasedLine(id: String, source: GeoJsonSource, color: Color, width: Dp, opacity: Float = 1f, dashed: Boolean = false) {
    LineLayer(
        id = "$id-casing", source = source, color = const(MapColors.OUTLINE), width = const(width + 2.dp), opacity = const(opacity),
        cap = const(LineCap.Round), join = const(LineJoin.Round),
    )
    if (dashed) {
        // A dash array is in line widths: 2 on, 1.5 off.
        LineLayer(id = id, source = source, color = const(color), width = const(width), opacity = const(opacity), dasharray = const(listOf(2, 1.5)))
    } else {
        LineLayer(id = id, source = source, color = const(color), width = const(width), opacity = const(opacity), cap = const(LineCap.Round), join = const(LineJoin.Round))
    }
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
internal val configureRuntimeOnce: Unit by lazy {
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

/** Plain points (photo positions) as a FeatureCollection. */
internal fun pointsGeoJson(points: List<LatLon>) = featureCollection(points.map { pointGeoJson(it) })

/**
 * Areas as a FeatureCollection. GeoJSON polygons repeat the first corner at the end ("closed ring"); fewer than 3
 * corners isn't an area yet, so 2 are sent as a line and 1 as nothing (its corner marker already shows it).
 */
internal fun polygonsGeoJson(polygons: List<MapOverlay.Polygon>) = featureCollection(
    polygons.mapNotNull { p ->
        val ring = (p.corners + p.corners.take(1)).joinToString(",") { "[${it.longitude},${it.latitude}]" }
        val geometry = when {
            p.corners.size >= 3 -> """{"type":"Polygon","coordinates":[[$ring]]}"""
            p.corners.size == 2 -> """{"type":"LineString","coordinates":[${p.corners.joinToString(",") { "[${it.longitude},${it.latitude}]" }}]}"""
            else -> return@mapNotNull null
        }
        """{"type":"Feature","properties":{},"geometry":$geometry}"""
    },
)

private fun featureCollection(features: List<String>) = """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

internal fun lineGeoJson(points: List<LatLon>) =
    """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[${points.joinToString(",") { "[${it.longitude},${it.latitude}]" }}]}}"""

/**
 * A 24 dp arrow pointing up (north at heading 0). Red-orange with a white edge inside a dark outline: the dark edge
 * separates it from the pale street map, the white one from dark satellite imagery.
 */
private val VehicleArrow = ImageVector.Builder("vehicle-arrow", 24.dp, 24.dp, 24f, 24f)
    .path(stroke = SolidColor(MapColors.OUTLINE), strokeLineWidth = 3f, strokeLineJoin = StrokeJoin.Round) { arrow() }
    .path(fill = SolidColor(MapColors.VEHICLE), stroke = SolidColor(Color.White), strokeLineWidth = 1f, strokeLineJoin = StrokeJoin.Round) { arrow() }
    .build()

/**
 * The direction chevron repeated along photo lines, 12 dp. Drawn pointing right: MapLibre lays a line-placed icon's
 * x-axis along the line in its drawing direction, so "right" in the image is "forward" on the line. Dark-edged white,
 * like the vehicle arrow, to read on the cyan line and any basemap.
 */
private val LineArrow = ImageVector.Builder("line-arrow", 12.dp, 12.dp, 12f, 12f)
    .path(fill = SolidColor(Color.White), stroke = SolidColor(MapColors.OUTLINE), strokeLineWidth = 1f, strokeLineJoin = StrokeJoin.Round) {
        moveTo(3f, 2f)
        lineTo(10f, 6f)
        lineTo(3f, 10f)
        lineTo(5f, 6f)
        close()
    }
    .build()

private fun PathBuilder.arrow() {
    moveTo(12f, 2f)
    lineTo(20f, 21f)
    lineTo(12f, 17f)
    lineTo(4f, 21f)
    close()
}
