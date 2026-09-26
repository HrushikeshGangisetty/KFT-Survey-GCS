package com.kft.gcs.ui.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The KFT colour schemes, generated from the brand navy **#1F2E5D** (the logo colour) as the seed.
 *
 * Tool: Google's `material-color-utilities` 0.4.0 (npm `@material/material-color-utilities`, Apache-2.0), the
 * library behind Material Theme Builder. Scheme variant TonalSpot, the M3 default. `tools/brand/generate_scheme.mjs`
 * reproduces every value here. What was changed by hand after generating, and why:
 * - **Light / high contrast: primary is the brand navy itself** (13.1:1 on white). TonalSpot would have used a
 *   lighter blue (#4C5C92), which isn't the logo colour.
 * - **Dark: navy-tinted neutrals.** The surfaces use neutral chroma 12 (neutral variant 16) at the seed's hue,
 *   instead of TonalSpot's 6 / 8, so the dark theme reads as KFT navy rather than grey. The primary is the tone-80
 *   tint of the same hue (#B5C4FF, 10.8:1 on the dark surface), so it stays visible.
 * - **One accent.** TonalSpot's tertiary is a pink. We use no tertiary accent, so the tertiary roles copy the
 *   secondary ones: then no component can pick up a second accent colour by default.
 * - **error = our critical colour** ([StatusColors]), so M3's own error states (a text field's red outline) look
 *   the same as our "critical" chips.
 *
 * Contrast is checked in `KftColorsTest` (WCAG 2 formula).
 */
object KftColors {
    /** The brand navy from the logo. Chrome only: map overlays never use it (see `ui:map`, MapView colours). */
    val Navy = Color(0xFF1F2E5D)

    val light: ColorScheme = lightColorScheme(
        primary = Navy,
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFDCE1FF),
        onPrimaryContainer = Color(0xFF344479),
        inversePrimary = Color(0xFFB5C4FF),
        secondary = Color(0xFF595E72),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFDEE1F9),
        onSecondaryContainer = Color(0xFF414659),
        tertiary = Color(0xFF595E72),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFDEE1F9),
        onTertiaryContainer = Color(0xFF414659),
        background = Color(0xFFFAF8FF),
        onBackground = Color(0xFF1A1B21),
        surface = Color(0xFFFAF8FF),
        onSurface = Color(0xFF1A1B21),
        surfaceVariant = Color(0xFFE2E1EC),
        onSurfaceVariant = Color(0xFF45464F),
        surfaceTint = Navy,
        inverseSurface = Color(0xFF2F3036),
        inverseOnSurface = Color(0xFFF1F0F7),
        error = Color(0xFFB0105A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF93000A),
        outline = Color(0xFF767680),
        outlineVariant = Color(0xFFC6C6D0),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFFAF8FF),
        surfaceDim = Color(0xFFDAD9E0),
        surfaceContainer = Color(0xFFEEEDF4),
        surfaceContainerHigh = Color(0xFFE9E7EF),
        surfaceContainerHighest = Color(0xFFE3E1E9),
        surfaceContainerLow = Color(0xFFF4F3FA),
        surfaceContainerLowest = Color(0xFFFFFFFF),
    )

    val dark: ColorScheme = darkColorScheme(
        primary = Color(0xFFB5C4FF),
        onPrimary = Color(0xFF1C2D61),
        primaryContainer = Color(0xFF344479),
        onPrimaryContainer = Color(0xFFDCE1FF),
        inversePrimary = Color(0xFF4C5C92),
        secondary = Color(0xFFC1C5DD),
        onSecondary = Color(0xFF2B3042),
        secondaryContainer = Color(0xFF414659),
        onSecondaryContainer = Color(0xFFDEE1F9),
        tertiary = Color(0xFFC1C5DD),
        onTertiary = Color(0xFF2B3042),
        tertiaryContainer = Color(0xFF414659),
        onTertiaryContainer = Color(0xFFDEE1F9),
        background = Color(0xFF10131F),
        onBackground = Color(0xFFE0E1F3),
        surface = Color(0xFF10131F),
        onSurface = Color(0xFFE0E1F3),
        surfaceVariant = Color(0xFF414659),
        onSurfaceVariant = Color(0xFFC1C5DD),
        surfaceTint = Color(0xFFB5C4FF),
        inverseSurface = Color(0xFFE0E1F3),
        inverseOnSurface = Color(0xFF2D303D),
        error = Color(0xFFFF8A80),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF8B90A5),
        outlineVariant = Color(0xFF414659),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF363946),
        surfaceDim = Color(0xFF10131F),
        surfaceContainer = Color(0xFF1C1F2B),
        surfaceContainerHigh = Color(0xFF262936),
        surfaceContainerHighest = Color(0xFF313441),
        surfaceContainerLow = Color(0xFF181B27),
        surfaceContainerLowest = Color(0xFF0A0E19),
    )

    /**
     * For sunlight on the tablet: TonalSpot at contrast level 1.0 (the "high contrast" option of Theme Builder), light,
     * with pure white surfaces and black text. Light rather than dark, because in direct sun a bright screen with dark
     * text is what stays readable; a dark theme turns into a mirror.
     */
    val highContrast: ColorScheme = lightColorScheme(
        primary = Navy,
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF37467B),
        onPrimaryContainer = Color(0xFFFFFFFF),
        inversePrimary = Color(0xFFB5C4FF),
        secondary = Color(0xFF272B3D),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFF44485C),
        onSecondaryContainer = Color(0xFFFFFFFF),
        tertiary = Color(0xFF272B3D),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFF44485C),
        onTertiaryContainer = Color(0xFFFFFFFF),
        background = Color(0xFFFFFFFF),
        onBackground = Color(0xFF000000),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF000000),
        surfaceVariant = Color(0xFFE2E1EC),
        onSurfaceVariant = Color(0xFF000000),
        surfaceTint = Navy,
        inverseSurface = Color(0xFF2F3036),
        inverseOnSurface = Color(0xFFFFFFFF),
        error = Color(0xFF90004F),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFF98000A),
        onErrorContainer = Color(0xFFFFFFFF),
        outline = Color(0xFF2A2C34),
        outlineVariant = Color(0xFF474951),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFB9B8BF),
        surfaceContainer = Color(0xFFE3E1E9),
        surfaceContainerHigh = Color(0xFFD5D3DB),
        surfaceContainerHighest = Color(0xFFC7C6CD),
        surfaceContainerLow = Color(0xFFF1F0F7),
        surfaceContainerLowest = Color(0xFFFFFFFF),
    )
}

/**
 * Status colours for text and icons on the theme's surfaces, kept apart from the brand colour so "navy" never
 * means "OK" or "bad". Every one is at least 5.2:1 on the surfaces it's drawn on (`KftColorsTest`).
 *
 * Colour-blind check: the three were run through the Machado et al. (2009) protanopia, deuteranopia and tritanopia
 * matrices, and every pair stays at least 15 CIELAB ΔE apart in every simulation (23 in dark). Red and green were
 * avoided on purpose: ok is teal, critical is crimson (it keeps some blue, which red-green colour-blind users still
 * see). ΔE 15 is visible but not large, and the light themes can't go further while keeping text readable, so hue is
 * never the only signal: every status has its own icon shape (check circle, triangle, octagon) and a critical chip
 * is filled solid, while ok and warn chips are only outlined or tinted.
 */
@Immutable
data class StatusColors(val ok: Color, val warn: Color, val critical: Color, val onCritical: Color) {
    companion object {
        val light = StatusColors(ok = Color(0xFF006A6A), warn = Color(0xFF8F4E00), critical = Color(0xFFB0105A), onCritical = Color.White)
        val dark = StatusColors(ok = Color(0xFF4DD9D0), warn = Color(0xFFFFB95C), critical = Color(0xFFFF8A80), onCritical = Color(0xFF3B0A0A))
        val highContrast = StatusColors(ok = Color(0xFF004F51), warn = Color(0xFF6E3B00), critical = Color(0xFF90004F), onCritical = Color.White)
    }
}
