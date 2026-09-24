package com.kft.gcs.spikes.mapspike

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.spatialk.geojson.Position

/**
 * Check M2: a pretend vehicle circling [center] at 10 Hz, published through a StateFlow exactly like
 * real telemetry will be. The map must follow it smoothly without leaking memory.
 */
class FakeVehicle(private val center: Position) {
    private val radiusDeg = 0.0008 // ~90 m circle
    private val _position = MutableStateFlow(center)
    val position: StateFlow<Position> = _position.asStateFlow()

    /** Runs until the caller's coroutine is cancelled. One lap every 20 s (200 updates). */
    suspend fun run() {
        var step = 0
        while (true) {
            val angle = 2 * PI * (step % 200) / 200
            // Longitude degrees shrink by cos(latitude); scale so the circle isn't an ellipse on the map.
            _position.value = Position(
                longitude = center.longitude + radiusDeg * cos(angle) / cos(center.latitude * PI / 180),
                latitude = center.latitude + radiusDeg * sin(angle),
            )
            step++
            delay(100)
        }
    }
}
