package com.kft.gcs.feature.replay

import com.kft.gcs.ui.map.CameraRequest
import com.kft.gcs.ui.map.MapOverlay

/**
 * Everything the Replay tab draws. [overlays] and [cameraRequest] go to the app's one map (ADR-001 F10), like Fly's.
 * Times are FC seconds since boot; the screen shows them from [startS].
 */
data class ReplayUiState(
    val title: String? = null,
    val subtitle: String = "",
    val odometryName: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val notes: List<String> = emptyList(),
    val timeS: Double = 0.0,
    val startS: Double = 0.0,
    val endS: Double = 0.0,
    val playing: Boolean = false,
    val speed: Int = 8,
    val follow: Boolean = false,
    val playhead: List<Stat> = emptyList(),
    val whole: List<Stat> = emptyList(),
    val events: List<EventLine> = emptyList(),
    val chart: ErrorChart = ErrorChart(emptyList(), emptyList(), 1.0),
    val overlays: List<MapOverlay> = emptyList(),
    val cameraRequest: CameraRequest? = null,
)

/** One number tile. [slot] is the map colour it belongs to (a legend swatch), or null for none. */
data class Stat(val label: String, val value: String, val slot: Int? = null)

/** One line of the event list; [t] is where a click seeks to. */
data class EventLine(val t: Double, val time: String, val text: String)

/** Error against truth over the flight, as (time, metres) pairs, and the top of the y axis. */
data class ErrorChart(val published: List<Pair<Double, Double>>, val vio: List<Pair<Double, Double>>, val maxM: Double)
