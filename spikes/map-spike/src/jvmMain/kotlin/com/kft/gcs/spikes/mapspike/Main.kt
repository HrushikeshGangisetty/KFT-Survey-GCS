package com.kft.gcs.spikes.mapspike

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.maplibre.compose.desktop.ProvideMapPresentationHost
import org.maplibre.compose.desktop.rememberAwtComposeMapPresentationHost

/**
 * Desktop spike entry point. The presentation host hands MapLibre Native this window's GPU context,
 * which is why it needs the AWT `window` and can't be created inside common code.
 */
fun main() {
    val esriKey = System.getenv("ESRI_API_KEY")?.takeIf { it.isNotBlank() }
    esriKey?.let(::configureEsriAuth)
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "KFT map spike (desktop)",
            state = rememberWindowState(width = 1280.dp, height = 800.dp),
        ) {
            ProvideMapPresentationHost(host = rememberAwtComposeMapPresentationHost(window)) {
                SpikeApp(satelliteEnabled = esriKey != null)
            }
        }
    }
}
