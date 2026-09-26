package com.kft.gcs.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.FrameWindowScope
import com.kft.gcs.core.mavlink.SerialPorts
import com.kft.gcs.core.mavlink.di.IoDispatcher
import com.kft.gcs.feature.connections.ProfileStore
import com.kft.gcs.feature.plan.PlanFiles
import com.kft.gcs.feature.plan.SettingsStore
import com.kft.gcs.ui.map.ProvideDesktopMapHost
import java.io.File
import org.koin.dsl.module

/**
 * [App] for the desktop shell: the map needs this window's GPU context, which only a window can provide. The window
 * passes its key events to [shortcuts].
 */
@Composable
fun FrameWindowScope.DesktopApp(shortcuts: KeyShortcuts) = ProvideDesktopMapHost(window) { App(desktopModule, shortcuts) }

/**
 * Desktop-only bindings: serial ports through jSerialComm, the profile and plan-settings files in `%APPDATA%\KFT-GCS`
 * on Windows (the per-user roaming folder Windows apps use), `~/.kft-gcs` elsewhere, and AWT's file dialogs.
 */
private val desktopModule = module {
    val dir = System.getenv("APPDATA")?.let { File(it, "KFT-GCS") } ?: File(System.getProperty("user.home"), ".kft-gcs")
    single { SerialPorts() }
    single<ProfileStore> { FileTextStore(File(dir, "connection-profiles.json")) }
    single<SettingsStore> { FileTextStore(File(dir, "plan-settings.json")) }
    single<PlanFiles> { DesktopPlanFiles(get(IoDispatcher)) }
}
