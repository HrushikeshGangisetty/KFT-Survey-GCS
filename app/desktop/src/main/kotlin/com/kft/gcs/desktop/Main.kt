package com.kft.gcs.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kft.gcs.app.DesktopApp
import com.kft.gcs.app.KeyShortcuts
import com.kft.gcs.app.kftWindowIcon

/** Desktop entry point. Opens one window hosting the shared app ([DesktopApp] adds the map's GPU host). */
fun main() = application {
    val shortcuts = remember { KeyShortcuts() }
    Window(
        onCloseRequest = ::exitApplication,
        title = "KFT GCS",
        icon = kftWindowIcon(),
        state = rememberWindowState(width = 1400.dp, height = 900.dp),
        onPreviewKeyEvent = shortcuts::onKey,
    ) {
        DesktopApp(shortcuts)
    }
}
