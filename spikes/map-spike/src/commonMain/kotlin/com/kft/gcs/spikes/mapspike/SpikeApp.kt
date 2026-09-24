package com.kft.gcs.spikes.mapspike

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.MapRuntimeOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.resource.MapRequestInterceptor
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.sources.rememberMbtilesUrl
import org.maplibre.compose.sources.rememberRasterTileSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position
import com.kft.gcs.spikes.mapspike.res.Res

/** Used-heap reading for the M2 memory check (JVM Runtime on both platforms). */
expect fun usedHeapMb(): Long

// ArduPilot SITL's default home (CMAC, Canberra), so the spike lines up with SITL later.
private val HOME = Position(longitude = 149.165230, latitude = -35.363261)
private const val LIBERTY_STYLE = "https://tiles.openfreemap.org/styles/liberty"
// Esri's Static Basemap Tiles service has no imagery style (only imagery/labels), so satellite comes from the
// keyed World_Imagery tile service. It accepts the key only as `?token=`, not as a header (checked 2026-09-24).
// ponytail: Mapbox alternate not wired (no key).
private const val ESRI_HOST = "https://ibasemaps-api.arcgis.com/"
private const val ESRI_TILES = ESRI_HOST + "arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
private const val ESRI_ATTRIBUTION = "Powered by Esri | Esri, Maxar, Earthstar Geographics"
private val HANDLE_HIT_RADIUS = 24.dp // finger-sized; a mouse is more precise but gains nothing from less

enum class Basemap { Street, Satellite, Offline }

/**
 * Adds the Esri token to Esri tile requests at fetch time. The style and source keep a key-free URL; only the
 * outgoing request carries it. A header would be nicer, but World_Imagery rejects the header form. Run once,
 * before the first map is created.
 */
fun configureEsriAuth(key: String) {
    DefaultMapRuntime.configure(
        MapRuntimeOptions(
            requestInterceptor = MapRequestInterceptor(rewriteUrl = { request ->
                if (request.url.startsWith(ESRI_HOST)) "${request.url}?token=$key" else null
            }),
        ),
    )
}

/**
 * The whole spike screen, shared by the desktop and Android entry points: a basemap switcher, a status
 * line, and one map with an editable polygon (M3), a fake vehicle (M2) and the chosen basemap (M1/M4/M6).
 */
@Composable
fun SpikeApp(satelliteEnabled: Boolean) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            var basemap by remember { mutableStateOf(Basemap.Street) }
            // Held as a State object (not `by`) so the pointer-input coroutine below always reads the latest ring.
            val ring = remember {
                mutableStateOf(
                    listOf(
                        Position(149.1640, -35.3625), Position(149.1665, -35.3622),
                        Position(149.1668, -35.3642), Position(149.1642, -35.3645),
                    ),
                )
            }
            var status by remember { mutableStateOf("Click the map for lat/lon · drag a white handle · long-press / right-click an edge to add a vertex") }
            var syncUpdates by remember { mutableStateOf(false) }

            val vehicle = remember { FakeVehicle(HOME) }
            LaunchedEffect(vehicle) { vehicle.run() }
            val vehiclePosition by vehicle.position.collectAsState()
            val heapMb by produceState(0L) { while (true) { value = usedHeapMb(); delay(1000) } }
            val offlineUrl by rememberMbtilesUrl(Res.getUri("files/offline.mbtiles"))

            val mapState = rememberMapState(
                // Liberty is the permanent base style; Satellite/Offline are raster layers drawn over it.
                // Why not swap the base style per basemap: on 0.17.0 desktop, swapping Empty -> Liberty drops our
                // CircleLayers (handles, vehicle) until restart. See ADR-001, finding F1.
                baseStyle = BaseStyle.Uri(LIBERTY_STYLE),
                initialCameraPosition = CameraPosition(target = HOME, zoom = 16.0),
            ) {
                // Basemap first: layers are drawn in declaration order, so overlays below end up on top.
                when (basemap) {
                    Basemap.Street -> Unit // nothing to add: Liberty is showing
                    Basemap.Satellite -> RasterLayer(
                        id = "satellite",
                        source = rememberRasterTileSource(
                            tiles = listOf(ESRI_TILES),
                            options = TileSetOptions(maxZoom = 19, attributionHtml = ESRI_ATTRIBUTION),
                            tileSize = 256, // World_Imagery MapServer tiles are 256 px JPEG
                        ),
                    )
                    Basemap.Offline -> offlineUrl?.let { url ->
                        RasterLayer(
                            id = "offline",
                            source = rememberRasterTileSource(tiles = listOf(url), options = TileSetOptions(maxZoom = 4), tileSize = 256),
                        )
                    }
                }

                // synchronousUpdate trades frame rate for "the polygon is on screen in this frame". Toggle to compare drag feel.
                val options = GeoJsonOptions(synchronousUpdate = syncUpdates)
                val polygon = rememberGeoJsonSource(GeoJsonData.JsonString(polygonGeoJson(ring.value)), options)
                FillLayer(id = "poly-fill", source = polygon, color = const(Color(0x4400A0FF)))
                LineLayer(id = "poly-line", source = polygon, color = const(Color(0xFF00A0FF)), width = const(2.dp))
                val handles = rememberGeoJsonSource(GeoJsonData.JsonString(pointsGeoJson(ring.value)), options)
                CircleLayer(
                    id = "handles", source = handles, radius = const(8.dp), color = const(Color.White),
                    strokeColor = const(Color(0xFF00A0FF)), strokeWidth = const(2.dp),
                )
                val vehicleSource = rememberGeoJsonSource(GeoJsonData.JsonString(pointsGeoJson(listOf(vehiclePosition))))
                CircleLayer(
                    id = "vehicle", source = vehicleSource, radius = const(7.dp), color = const(Color.Red),
                    strokeColor = const(Color.White), strokeWidth = const(2.dp),
                )
            }

            // safeDrawing: Android 15+ draws edge-to-edge, so without this the chips sit under the status bar.
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Basemap.entries.forEach { b ->
                        FilterChip(
                            selected = basemap == b,
                            onClick = { basemap = b },
                            enabled = b != Basemap.Satellite || satelliteEnabled,
                            label = { Text(if (b == Basemap.Satellite && !satelliteEnabled) "Satellite (no key)" else b.name) },
                        )
                    }
                    FilterChip(selected = syncUpdates, onClick = { syncUpdates = !syncUpdates }, label = { Text("Sync GeoJSON") })
                    Text("heap ${heapMb} MB", style = MaterialTheme.typography.labelMedium)
                }
                Text(status, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)

                MaplibreMap(
                    state = mapState,
                    // M3 vertex drag. This runs on the parent of the map in the Initial pass, i.e. before the map's
                    // own gesture handling (which uses the Main pass and ignores consumed events). If the press
                    // lands on a handle we consume every event of that gesture, so the map doesn't pan underneath.
                    modifier = Modifier.pointerInput(mapState) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val handleScreen = ring.value.map { mapState.screenLocationFromPosition(it) }
                            val index = hitVertex(toDp(down.position), handleScreen, HANDLE_HIT_RADIUS)
                                ?: return@awaitEachGesture
                            down.consume()
                            while (true) {
                                val change = awaitPointerEvent(PointerEventPass.Initial).changes
                                    .firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                                val geo = mapState.positionFromScreenLocation(toDp(change.position)) ?: continue
                                ring.value = ring.value.toMutableList().also { it[index] = geo }
                                status = "Dragging vertex $index → ${geo.latitude.fmt()}, ${geo.longitude.fmt()}"
                            }
                        }
                    },
                    interactions = MapInteractions {
                        callbacks {
                            click {
                                onEvent { event ->
                                    event.position?.let { status = "Clicked ${it.latitude.fmt()}, ${it.longitude.fmt()}" }
                                    ClickResult.Consume
                                }
                            }
                            longClick {
                                onEvent { event ->
                                    val geo = event.position ?: return@onEvent ClickResult.Pass
                                    val screen = ring.value.map { mapState.screenLocationFromPosition(it) }
                                    val at = if (screen.any { it == null }) ring.value.size
                                    else insertionIndex(event.screenOffset, screen.filterNotNull())
                                    ring.value = ring.value.toMutableList().also { it.add(at, geo) }
                                    status = "Added vertex $at (${ring.value.size} total)"
                                    ClickResult.Consume
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

private fun Double.fmt(): String = ((this * 1e6).toLong() / 1e6).toString()

/** Pointer events arrive in pixels; the map's projection API works in dp. */
private fun Density.toDp(offset: Offset) = DpOffset(offset.x.toDp(), offset.y.toDp())
