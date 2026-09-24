package com.kft.gcs.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.FrameWindowScope
import com.kft.gcs.ui.map.ProvideDesktopMapHost

/** [App] for the desktop shell: the map needs this window's GPU context, which only a window can provide. */
@Composable
fun FrameWindowScope.DesktopApp() = ProvideDesktopMapHost(window) { App() }
