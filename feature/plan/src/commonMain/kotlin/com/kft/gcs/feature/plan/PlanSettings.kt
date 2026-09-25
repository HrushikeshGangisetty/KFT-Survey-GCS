package com.kft.gcs.feature.plan

import com.kft.gcs.core.geoio.CameraEntry
import com.kft.gcs.core.geoio.bundledCameras
import com.kft.gcs.core.geoio.toEntry
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.planning.Camera
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The operator's planning settings, kept between runs.
 * @property customCameras cameras the operator added (the bundled presets aren't stored here; they ship with the app).
 * @property batteryMinutes usable flight time per battery, per vehicle kind, with the landing reserve already taken
 *   off. Missing = not set, and the survey panel asks for it instead of guessing.
 * @property maxGsdCm warn when a survey's GSD is coarser than this; null = no warning.
 */
data class PlanSettings(
    val customCameras: List<Camera> = emptyList(),
    val batteryMinutes: Map<VehicleKind, Double> = emptyMap(),
    val maxGsdCm: Double? = null,
) {
    /** What the camera list offers: the bundled presets first, then the operator's own. */
    val cameras: List<Camera> get() = bundledCameras + customCameras
}

/**
 * Where the settings live, as one text blob (the same idea as the connection profiles' store). The app shells back
 * it with a file in the app's data folder; tests with a string. [read] is null on first run.
 */
interface SettingsStore {
    fun read(): String?
    fun write(text: String)
}

/**
 * The settings, loaded from [store] at start and written back on every change. Settings change rarely, and a crash
 * must not lose a camera the operator just typed in, so there's no batching.
 */
class PlanSettingsRepository(private val store: SettingsStore) {
    private val _settings = MutableStateFlow(store.read()?.let(::decodeSettings) ?: PlanSettings())
    val settings: StateFlow<PlanSettings> = _settings.asStateFlow()

    /** Adds [camera], replacing a custom camera of the same name (so "save" after an edit doesn't make a duplicate). */
    fun saveCamera(camera: Camera) = save { s -> s.copy(customCameras = s.customCameras.filterNot { it.name == camera.name } + camera) }

    fun deleteCamera(name: String) = save { s -> s.copy(customCameras = s.customCameras.filterNot { it.name == name }) }

    fun setBatteryMinutes(kind: VehicleKind, minutes: Double?) = save { s ->
        s.copy(batteryMinutes = if (minutes == null) s.batteryMinutes - kind else s.batteryMinutes + (kind to minutes))
    }

    fun setMaxGsdCm(cm: Double?) = save { it.copy(maxGsdCm = cm) }

    private fun save(change: (PlanSettings) -> PlanSettings) {
        _settings.update(change)
        store.write(encodeSettings(_settings.value))
    }
}

/** On-disk shape. Vehicle kinds are stored by name, so reordering the enum can't change a saved file's meaning. */
@Serializable
private data class SettingsFile(
    val customCameras: List<CameraEntry> = emptyList(),
    val batteryMinutes: Map<String, Double> = emptyMap(),
    val maxGsdCm: Double? = null,
)

private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

internal fun encodeSettings(s: PlanSettings): String = json.encodeToString(
    SettingsFile(s.customCameras.map { it.toEntry() }, s.batteryMinutes.mapKeys { it.key.name }, s.maxGsdCm),
)

/** Null for a broken file (bad JSON, a camera the checks reject); the caller then starts from defaults. */
internal fun decodeSettings(text: String): PlanSettings? = try {
    val file = json.decodeFromString<SettingsFile>(text)
    PlanSettings(
        customCameras = file.customCameras.map { it.toCamera() },
        batteryMinutes = file.batteryMinutes.mapNotNull { (k, v) -> VehicleKind.entries.firstOrNull { it.name == k }?.let { it to v } }.toMap(),
        maxGsdCm = file.maxGsdCm,
    )
} catch (e: IllegalArgumentException) {
    null
}
