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
import androidx.compose.ui.unit.sp

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
 * Corner radii: small and crisp, like an instrument panel (Pass 52). Floating cards use [Shapes.large] (8 dp) and a
 * hairline outline ([MapCard]) to read as "over the map"; big round corners made them look like a phone app.
 */
val KftShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

/**
 * M3's type scale with two changes. Numbers use **tabular figures** (OpenType `tnum`), where every digit has the same
 * width: without them "11.1 m" is narrower than "18.8 m", so a HUD value jitters sideways as it changes. The platform
 * fonts (Roboto on Android, Segoe UI on Windows) both have `tnum`. And the small labels are tracked out (letter
 * spacing), for [instrumentLabel]'s uppercase captions.
 */
val KftTypography: Typography = Typography().run {
    copy(
        titleLarge = titleLarge.tabular(), titleMedium = titleMedium.tabular(), titleSmall = titleSmall.tabular(),
        bodyLarge = bodyLarge.tabular(), bodyMedium = bodyMedium.tabular(), bodySmall = bodySmall.tabular(),
        labelLarge = labelLarge.tabular(), labelMedium = labelMedium.tabular(),
        labelSmall = labelSmall.tabular().copy(letterSpacing = 0.8.sp),
    )
}

/** The big number in a HUD [StatTile]: title size, semi-bold, tabular figures. */
val Typography.hudValue: TextStyle get() = titleLarge.copy(fontWeight = FontWeight.SemiBold)

/**
 * The caption over an instrument value or a section ("ALT", "MISSION"): small, tracked-out capitals, the convention of
 * avionics displays. Write the text in capitals at the call site (`label.uppercase()`): Compose has no text-transform.
 */
val Typography.instrumentLabel: TextStyle get() = labelSmall.copy(fontWeight = FontWeight.Medium)

private fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")
