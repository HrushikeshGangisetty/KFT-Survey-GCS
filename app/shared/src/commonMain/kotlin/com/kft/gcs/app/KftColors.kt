package com.kft.gcs.app

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * GCS colours. Dark by default: field tablets are used in sunlight with brightness up, and a dark
 * chrome keeps the satellite map as the brightest thing on screen.
 */
object KftColors {
    val dark = darkColorScheme(
        primary = Color(0xFF4FC3F7),
        secondary = Color(0xFFFFB74D),
        error = Color(0xFFEF5350),
    )
}
