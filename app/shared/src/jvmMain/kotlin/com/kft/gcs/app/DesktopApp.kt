package com.kft.gcs.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.window.FrameWindowScope
import com.kft.gcs.core.mavlink.SerialPorts
import com.kft.gcs.core.mavlink.di.IoDispatcher
import com.kft.gcs.feature.connections.ProfileStore
import com.kft.gcs.feature.params.MetadataSource
import com.kft.gcs.feature.plan.PlanFiles
import com.kft.gcs.feature.plan.SettingsStore
import com.kft.gcs.ui.design.kftAppIcon
import com.kft.gcs.feature.settings.MbtilesPicker
import com.kft.gcs.ui.map.MapLibreOfflineMaps
import com.kft.gcs.ui.map.OfflineMaps
import com.kft.gcs.ui.map.ProvideDesktopMapHost
import com.kft.gcs.ui.map.TileSources
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
 * Imported MBTiles go in its `maps` folder; downloaded areas live in MapLibre's own cache database.
 */
private val desktopModule = module {
    val dir = System.getenv("APPDATA")?.let { File(it, "KFT-GCS") } ?: File(System.getProperty("user.home"), ".kft-gcs")
    single { SerialPorts() }
    single<ProfileStore> { FileTextStore(File(dir, "connection-profiles.json")) }
    single<SettingsStore> { FileTextStore(File(dir, "plan-settings.json")) }
    single<ThemeStore> { FileTextStore(File(dir, "theme.txt")) }
    single<MetadataSource> { HttpMetadataSource(File(dir, "param-metadata"), get(IoDispatcher)) }
    single { DesktopPlanFiles(get(IoDispatcher)) }
    single<PlanFiles> { get<DesktopPlanFiles>() }
    single<OfflineMaps> { MapLibreOfflineMaps(File(dir, "maps").path, TileSources.available()) }
    single { MbtilesPicker { get<DesktopPlanFiles>().pickAndCopy(File(dir, "maps")) } }
}

/** The window's title-bar and taskbar icon (the placeholder KFT app icon, see `kftAppIcon`). */
@Composable
fun kftWindowIcon(): Painter = kftAppIcon()
