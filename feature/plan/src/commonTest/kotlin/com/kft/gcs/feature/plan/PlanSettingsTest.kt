package com.kft.gcs.feature.plan

import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.planning.Camera
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanSettingsTest {
    private class MemoryStore(var text: String? = null) : SettingsStore {
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    private val mine = Camera(6.17, 4.55, 4000, 3000, 4.5, minTriggerIntervalS = 1.5, mbPerPhoto = 6.0, name = "My cam")

    /** Everything set survives a restart: a new repository on the same store reads it back. */
    @Test
    fun settingsSurviveARestart() {
        val store = MemoryStore()
        PlanSettingsRepository(store).apply {
            saveCamera(mine)
            setBatteryMinutes(VehicleKind.COPTER, 18.0)
            setMaxGsdCm(3.0)
        }
        val reloaded = PlanSettingsRepository(store).settings.value
        assertEquals(listOf(mine), reloaded.customCameras)
        assertEquals(mapOf(VehicleKind.COPTER to 18.0), reloaded.batteryMinutes)
        assertEquals(3.0, reloaded.maxGsdCm)
        assertTrue(reloaded.cameras.first().unverified && reloaded.cameras.last() == mine, "presets first, then custom")
    }

    @Test
    fun savingACameraUnderTheSameNameReplacesIt() {
        val repo = PlanSettingsRepository(MemoryStore())
        repo.saveCamera(mine)
        repo.saveCamera(mine.copy(focalLengthMm = 5.0))
        assertEquals(listOf(5.0), repo.settings.value.customCameras.map { it.focalLengthMm })
        repo.deleteCamera("My cam")
        assertEquals(emptyList(), repo.settings.value.customCameras)
    }

    @Test
    fun aBrokenFileStartsFromDefaults() {
        assertEquals(PlanSettings(), PlanSettingsRepository(MemoryStore("{ broken")).settings.value)
    }
}
