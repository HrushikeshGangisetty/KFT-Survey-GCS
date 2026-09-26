package com.kft.gcs.ui.design

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every text and control colour meets WCAG 2 contrast on the surfaces it's drawn on: 4.5:1 for text, 3:1 for UI
 * parts such as outlines (WCAG 2.1 SC 1.4.3 and 1.4.11).
 */
class KftColorsTest {

    /** WCAG 2 contrast ratio: (L1 + 0.05) / (L2 + 0.05), L = relative luminance, lighter over darker. */
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance().toDouble(), b.luminance().toDouble()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun contrastFormulaMatchesPublishedValues() {
        // 21:1 is the definition's maximum (WCAG 2 "contrast ratio" note); #777777 on white is 4.48:1 in the WebAIM
        // contrast checker, the classic "just fails AA" grey.
        assertEquals(21.0, contrast(Color.Black, Color.White), 0.01)
        assertEquals(4.48, contrast(Color(0xFF777777), Color.White), 0.01)
    }

    @Test
    fun brandNavyIsThirteenToOneOnWhite() {
        // The figure in the Pass 19 brief (13:1), and what WebAIM gives for #1F2E5D on #FFFFFF: 13.07:1.
        assertEquals(13.07, contrast(KftColors.Navy, Color.White), 0.01)
        assertEquals(KftColors.Navy, KftColors.light.primary)
        assertEquals(KftColors.Navy, KftColors.highContrast.primary)
    }

    @Test
    fun textAndControlsMeetWcagInEveryTheme() {
        for ((name, s) in listOf("light" to KftColors.light, "dark" to KftColors.dark, "high contrast" to KftColors.highContrast)) {
            for ((surfaceName, surface) in surfaces(s)) {
                assertAtLeast(4.5, s.onSurface, surface, "$name onSurface on $surfaceName")
                assertAtLeast(4.5, s.onSurfaceVariant, surface, "$name onSurfaceVariant on $surfaceName")
                assertAtLeast(4.5, s.primary, surface, "$name primary (text, selected tab) on $surfaceName")
                assertAtLeast(3.0, s.outline, surface, "$name outline (field borders) on $surfaceName")
            }
            assertAtLeast(4.5, s.onPrimary, s.primary, "$name onPrimary on primary")
            assertAtLeast(4.5, s.onSecondaryContainer, s.secondaryContainer, "$name selected rail item")
        }
    }

    @Test
    fun darkPrimaryIsALightTintOfTheBrandHue() {
        val p = KftColors.dark.primary
        assertTrue(p.blue > p.red && p.blue > p.green, "still blue")
        assertTrue(p.luminance() > 0.5f, "light enough to read on navy-dark surfaces")
    }

    @Test
    fun statusColoursAreReadableAsText() {
        for ((name, pair) in listOf(
            "light" to (KftColors.light to StatusColors.light),
            "dark" to (KftColors.dark to StatusColors.dark),
            "high contrast" to (KftColors.highContrast to StatusColors.highContrast),
        )) {
            val (s, status) = pair
            for ((surfaceName, surface) in surfaces(s)) {
                assertAtLeast(4.5, status.ok, surface, "$name ok on $surfaceName")
                assertAtLeast(4.5, status.warn, surface, "$name warn on $surfaceName")
                assertAtLeast(4.5, status.critical, surface, "$name critical on $surfaceName")
            }
            assertAtLeast(4.5, status.onCritical, status.critical, "$name text on a critical chip")
            assertEquals(status.critical, s.error, "$name M3 error is our critical")
        }
    }

    private fun surfaces(s: ColorScheme) =
        listOf("surface" to s.surface, "surfaceContainer" to s.surfaceContainer, "surfaceContainerHigh" to s.surfaceContainerHigh)

    private fun assertAtLeast(min: Double, fg: Color, bg: Color, what: String) {
        val c = contrast(fg, bg)
        assertTrue(c >= min, "$what: ${(c * 100).toInt() / 100.0}:1, needs $min:1")
    }
}
