package com.kft.gcs.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The three themes the operator can pick. [label] is what the theme menu shows. */
enum class ThemeMode(val label: String) {
    DARK("Dark"),
    LIGHT("Light"),
    HIGH_CONTRAST("High contrast (sunlight)"),
}

/**
 * The app theme: M3 colours for [mode], our shapes and type, and the [StatusColors] that go with that scheme. Every
 * screen sits inside it, so a component reads `MaterialTheme.colorScheme` / [KftTheme.status] and never a literal
 * colour.
 */
@Composable
fun KftTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val (scheme, status) = when (mode) {
        ThemeMode.DARK -> KftColors.dark to StatusColors.dark
        ThemeMode.LIGHT -> KftColors.light to StatusColors.light
        ThemeMode.HIGH_CONTRAST -> KftColors.highContrast to StatusColors.highContrast
    }
    CompositionLocalProvider(LocalStatusColors provides status) {
        MaterialTheme(colorScheme = scheme, shapes = KftShapes, typography = KftTypography, content = content)
    }
}

/** Theme values M3 has no slot for. */
object KftTheme {
    val status: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current
}

private val LocalStatusColors = staticCompositionLocalOf { StatusColors.dark }

/**
 * The spacing scale: multiples of 4 dp, so everything lines up on one grid. Use these instead of raw dp in
 * paddings and gaps; a raw number is a sign something is off-grid.
 */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/**
 * The smallest thing a finger can hit reliably: 48 dp, the M3 / Android accessibility minimum. M3's own buttons
 * and icon buttons already reserve it; our custom clickables must too.
 */
val MinTouchTarget = 48.dp

/**
 * Corner radii. Floating cards on the map use [Shapes.large] (16 dp): rounder than the M3 default card, so a card
 * reads as "floating over the map" rather than as part of the map.
 */
val KftShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * M3's type scale with one change: numbers use **tabular figures** (OpenType `tnum`), where every digit has the same
 * width. Without them "11.1 m" is narrower than "18.8 m", so a HUD value jitters sideways as it changes. The platform
 * fonts (Roboto on Android, Segoe UI on Windows) both have `tnum`.
 */
val KftTypography: Typography = Typography().run {
    copy(
        titleLarge = titleLarge.tabular(), titleMedium = titleMedium.tabular(), titleSmall = titleSmall.tabular(),
        bodyLarge = bodyLarge.tabular(), bodyMedium = bodyMedium.tabular(), bodySmall = bodySmall.tabular(),
        labelLarge = labelLarge.tabular(), labelMedium = labelMedium.tabular(), labelSmall = labelSmall.tabular(),
    )
}

/** The big number in a HUD [StatTile]: title size, medium weight, tabular figures. */
val Typography.hudValue: TextStyle get() = titleLarge.copy(fontWeight = FontWeight.Medium)

private fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")
