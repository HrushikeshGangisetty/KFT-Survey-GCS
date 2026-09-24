package com.kft.gcs.core.geo

import kotlin.math.round

/**
 * A WGS-84 position in decimal degrees.
 *
 * Immutable value type used everywhere (vehicle position, polygon vertices, waypoints).
 * The init block rejects impossible values at construction time, so no other code needs to re-check.
 */
data class LatLon(val latitude: Double, val longitude: Double) {
    init {
        require(latitude in -90.0..90.0) { "latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "longitude out of range: $longitude" }
    }

    /** MAVLink `*_INT` messages carry degrees × 1e7 as Int32. */
    val latE7: Int get() = round(latitude * 1e7).toInt()
    val lonE7: Int get() = round(longitude * 1e7).toInt()

    companion object {
        fun fromE7(latE7: Int, lonE7: Int) = LatLon(latE7 / 1e7, lonE7 / 1e7)
    }
}
