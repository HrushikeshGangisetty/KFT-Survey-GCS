package com.kft.gcs.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kft.gcs.app.DesktopApp

/** Desktop entry point. Opens one window hosting the shared app ([DesktopApp] adds the map's GPU host). */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "KFT GCS",
        state = rememberWindowState(width = 1400.dp, height = 900.dp),
    ) {
        DesktopApp()
    }
}
