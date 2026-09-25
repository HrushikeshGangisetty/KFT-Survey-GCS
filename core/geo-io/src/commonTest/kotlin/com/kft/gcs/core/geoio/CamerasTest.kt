package com.kft.gcs.core.geoio

import com.kft.gcs.core.planning.gsdM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CamerasTest {
    /** The bundled list parses, every entry is still marked unverified (GS-5), and P4P matches the worked example. */
    @Test
    fun bundledPresetsParseAndAreMarkedUnverified() {
        assertTrue(bundledCameras.size >= 4)
        assertTrue(bundledCameras.all { it.unverified }, "nothing is checked against a spec sheet yet")
        val p4p = bundledCameras.first { it.name.startsWith("DJI Phantom 4 Pro") }
        // Same camera as CameraTest: GSD at 100 m = 100 × 13.2 / (8.8 × 5472) = 0.027412 m.
        assertEquals(0.027412, p4p.gsdM(100.0), 1e-6)
        assertEquals(2.0, p4p.minTriggerIntervalS)
    }

    @Test
    fun brokenFilesGiveNull() {
        assertNull(decodeCameras("not json"))
        assertNull(decodeCameras("""[{"name":"x","sensorWidthMm":1,"sensorHeightMm":1,"imageWidthPx":1,"imageHeightPx":1,"focalLengthMm":0}]"""), "zero focal length")
        assertEquals(emptyList(), decodeCameras("[]"))
    }
}
