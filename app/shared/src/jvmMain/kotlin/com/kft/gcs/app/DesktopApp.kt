package com.kft.gcs.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.FrameWindowScope
import com.kft.gcs.core.mavlink.SerialPorts
import com.kft.gcs.feature.connections.ProfileStore
import com.kft.gcs.ui.map.ProvideDesktopMapHost
import java.io.File
import org.koin.dsl.module

/** [App] for the desktop shell: the map needs this window's GPU context, which only a window can provide. */
@Composable
fun FrameWindowScope.DesktopApp() = ProvideDesktopMapHost(window) { App(desktopModule) }

/**
 * Desktop-only bindings: serial ports through jSerialComm, and the profile file in `%APPDATA%\KFT-GCS` on Windows
 * (the per-user roaming folder Windows apps use), `~/.kft-gcs` elsewhere.
 */
private val desktopModule = module {
    single { SerialPorts() }
    single<ProfileStore> {
        val dir = System.getenv("APPDATA")?.let { File(it, "KFT-GCS") } ?: File(System.getProperty("user.home"), ".kft-gcs")
        FileProfileStore(File(dir, "connection-profiles.json"))
    }
}
