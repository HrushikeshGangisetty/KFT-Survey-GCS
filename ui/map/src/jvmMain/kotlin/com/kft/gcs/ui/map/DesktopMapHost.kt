package com.kft.gcs.ui.map

import androidx.compose.runtime.Composable
import java.awt.Window
import org.maplibre.compose.desktop.ProvideMapPresentationHost
import org.maplibre.compose.desktop.rememberAwtComposeMapPresentationHost

/**
 * Desktop only: gives MapLibre the GPU context of [window]. Wrap the app content in it once, in the desktop
 * shell. It lives here so the shell doesn't import a map library (CLAUDE.md §2).
 */
@Composable
fun ProvideDesktopMapHost(window: Window, content: @Composable () -> Unit) {
    ProvideMapPresentationHost(host = rememberAwtComposeMapPresentationHost(window), content = content)
}
