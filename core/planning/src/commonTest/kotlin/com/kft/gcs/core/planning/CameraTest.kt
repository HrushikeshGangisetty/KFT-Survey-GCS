package com.kft.gcs.core.planning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Example camera: DJI Phantom 4 Pro, 1" sensor 13.2 × 8.8 mm, 5472 × 3648 px, 8.8 mm lens (the values in QGC's
 * camera list and DJI's spec sheet). Chosen because the numbers divide nicely by hand.
 */
class CameraTest {
    private val p4p = Camera(sensorWidthMm = 13.2, sensorHeightMm = 8.8, imageWidthPx = 5472, imageHeightPx = 3648, focalLengthMm = 8.8)

    /** GSD at 100 m = 100 × 13.2 / (8.8 × 5472) = 1320 / 48 153.6 = 0.027412 m = 2.74 cm per pixel. */
    @Test
    fun gsdFromAltitude() {
        assertEquals(0.027412, p4p.gsdM(100.0), 1e-6)
        assertEquals(0.0, p4p.gsdM(0.0))
    }

    /** For 2 cm/px: h = 0.02 × 8.8 × 5472 / 13.2 = 963.072 / 13.2 = 72.96 m. */
    @Test
    fun altitudeFromGsd() {
        assertEquals(72.96, p4p.altitudeForGsdM(0.02), 1e-9)
        assertEquals(0.02, p4p.gsdM(p4p.altitudeForGsdM(0.02)), 1e-12, "the two are inverses")
    }

    /** At 100 m: 13.2 × 100 / 8.8 = 150 m wide, 8.8 × 100 / 8.8 = 100 m tall. Portrait swaps them. */
    @Test
    fun footprint() {
        assertEquals(Footprint(acrossM = 150.0, alongM = 100.0), p4p.footprint(100.0))
        assertEquals(Footprint(acrossM = 100.0, alongM = 150.0), p4p.footprint(100.0, CameraOrientation.PORTRAIT))
        // Same result as GSD × pixels: 0.027412 × 5472 = 150.0 m (square pixels on this camera).
        assertEquals(p4p.gsdM(100.0) * 5472, p4p.footprint(100.0).acrossM, 1e-9)
    }

    /** 70 % side overlap: 150 × 0.3 = 45 m between lines. 80 % front overlap: 100 × 0.2 = 20 m between photos. */
    @Test
    fun spacingAndTriggerDistance() {
        val fp = p4p.footprint(100.0)
        assertEquals(45.0, lineSpacingM(fp, 0.70), 1e-9)
        assertEquals(20.0, triggerDistanceM(fp, 0.80), 1e-9)
        assertEquals(150.0, lineSpacingM(fp, 0.0), "no overlap: lines one footprint apart")
    }

    @Test
    fun impossibleInputsAreRejected() {
        val fp = p4p.footprint(100.0)
        assertFailsWith<IllegalArgumentException> { lineSpacingM(fp, 1.0) }
        assertFailsWith<IllegalArgumentException> { triggerDistanceM(fp, -0.1) }
        assertFailsWith<IllegalArgumentException> { Camera(13.2, 8.8, 5472, 3648, focalLengthMm = 0.0) }
    }
}
