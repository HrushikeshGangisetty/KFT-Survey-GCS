package com.kft.gcs.core.geoio

import com.kft.gcs.core.planning.Camera
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The camera list that ships with the app, as JSON so a camera can be added or corrected without touching code.
 *
 * Every entry is `"unverified": true`: the numbers are from memory and common references, not yet checked against
 * the maker's spec sheet. Checking one means comparing all seven numbers with the datasheet and flipping the flag.
 * None of these is KFT's survey payload (open item GS-5); that gets added when it's known.
 */
internal const val BUNDLED_CAMERAS_JSON = """
[
  { "name": "DJI Phantom 4 Pro (FC6310)", "sensorWidthMm": 13.2, "sensorHeightMm": 8.8, "imageWidthPx": 5472, "imageHeightPx": 3648,
    "focalLengthMm": 8.8, "minTriggerIntervalS": 2.0, "mbPerPhoto": 8.0, "unverified": true },
  { "name": "Sony a6000 + 16 mm", "sensorWidthMm": 23.5, "sensorHeightMm": 15.6, "imageWidthPx": 6000, "imageHeightPx": 4000,
    "focalLengthMm": 16.0, "minTriggerIntervalS": 1.0, "mbPerPhoto": 10.0, "unverified": true },
  { "name": "Sony RX1R II (35 mm)", "sensorWidthMm": 35.9, "sensorHeightMm": 24.0, "imageWidthPx": 7952, "imageHeightPx": 5304,
    "focalLengthMm": 35.0, "minTriggerIntervalS": 1.0, "mbPerPhoto": 20.0, "unverified": true },
  { "name": "MicaSense RedEdge-MX (5 bands)", "sensorWidthMm": 4.8, "sensorHeightMm": 3.6, "imageWidthPx": 1280, "imageHeightPx": 960,
    "focalLengthMm": 5.5, "minTriggerIntervalS": 1.0, "mbPerPhoto": 12.0, "unverified": true }
]
"""

/** The bundled presets. Parsed once; a broken bundled file is a bug, so it fails loudly in tests rather than hiding. */
val bundledCameras: List<Camera> by lazy { requireNotNull(decodeCameras(BUNDLED_CAMERAS_JSON)) { "bundled camera list is invalid" } }

/**
 * On-disk shape of one camera. Separate from [Camera] so renaming a Kotlin field never breaks saved files. Public so
 * other saved files (the plan settings) can hold cameras in the same shape.
 */
@Serializable
data class CameraEntry(
    val name: String,
    val sensorWidthMm: Double,
    val sensorHeightMm: Double,
    val imageWidthPx: Int,
    val imageHeightPx: Int,
    val focalLengthMm: Double,
    val minTriggerIntervalS: Double = 0.0,
    val mbPerPhoto: Double = 0.0,
    val unverified: Boolean = false,
) {
    fun toCamera() = Camera(sensorWidthMm, sensorHeightMm, imageWidthPx, imageHeightPx, focalLengthMm, minTriggerIntervalS, mbPerPhoto, name, unverified)
}

fun Camera.toEntry() =
    CameraEntry(name, sensorWidthMm, sensorHeightMm, imageWidthPx, imageHeightPx, focalLengthMm, minTriggerIntervalS, mbPerPhoto, unverified)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Null when [text] isn't a valid camera list (bad JSON, or a value the [Camera] checks reject, such as a zero focal
 * length). Unknown keys are ignored, so a file from a newer version still loads.
 */
fun decodeCameras(text: String): List<Camera>? = try {
    json.decodeFromString<List<CameraEntry>>(text).map { it.toCamera() }
} catch (e: IllegalArgumentException) {
    // SerializationException is an IllegalArgumentException, and so is a failed Camera init check.
    null
}
