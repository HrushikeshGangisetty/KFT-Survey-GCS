package com.kft.gcs.app

import com.kft.gcs.core.companion.CompanionConfigStore
import com.kft.gcs.core.keys.AndroidKeystoreWrap
import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kft.gcs.core.mavlink.SerialPorts
import com.kft.gcs.core.mavlink.TlogFiles
import com.kft.gcs.core.mavlink.VehicleRecorder
import com.kft.gcs.core.vehicle.RecordStore
import com.kft.gcs.core.vehicle.di.WallClock
import com.kft.gcs.feature.connections.ProfileStore
import com.kft.gcs.feature.fly.VideoStore
import com.kft.gcs.feature.params.MetadataSource
import com.kft.gcs.feature.plan.PlanFiles
import com.kft.gcs.feature.plan.SettingsStore
import com.kft.gcs.feature.settings.MbtilesPicker
import com.kft.gcs.ui.map.MapLibreOfflineMaps
import com.kft.gcs.ui.map.OfflineMaps
import com.kft.gcs.ui.map.TileSources
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.koin.dsl.module

/**
 * [App] for the Android shell, mirroring `DesktopApp`: adds what needs a Context (USB-OTG serial ports, the app's
 * private files directory for saved profiles and plan settings, which survives restarts and updates but not
 * uninstall, and the document picker for plan files).
 */
@Composable
fun AndroidApp(context: Context) {
    val app = context.applicationContext
    // The shell is a composition root, like appModule, so it may name Dispatchers.IO; tests never build this.
    val planFiles = remember { AndroidPlanFiles(app, Dispatchers.IO) }
    val serialPorts = remember { SerialPorts(app) }
    val platform = remember {
        module {
            // The vault file is wrapped by an Android Keystore key as well as the PIN (PRD "Where each key lives").
            includes(signingModule(File(app.filesDir, "signing.vnks"), File(app.filesDir, "signing_ts.json"), AndroidKeystoreWrap()))
            single { bundlePicker { planFiles.pickAndCopy(File(app.cacheDir, "bundle-import")) } }
            single { serialPorts }
            // The app's own folder on shared storage (Android/data/<package>/files/tlogs): no permission needed, and
            // the logs can be copied off over USB. Falls back to private storage if shared storage isn't mounted.
            single<VehicleRecorder> { TlogFiles(File(app.getExternalFilesDir(null) ?: app.filesDir, "tlogs"), Dispatchers.IO) }
            single<RecordStore> { FileRecordStore(File(app.filesDir, "missions")) }
            single<WallClock> { SystemWallClock }
            single<ProfileStore> { FileTextStore(File(app.filesDir, "connection-profiles.json")) }
            single<SettingsStore> { FileTextStore(File(app.filesDir, "plan-settings.json")) }
            single<ThemeStore> { FileTextStore(File(app.filesDir, "theme.txt")) }
            single<VideoStore> { FileTextStore(File(app.filesDir, "video-profiles.json")) }
            single<CompanionConfigStore> { FileTextStore(File(app.filesDir, "companion.json")) }
            single<MetadataSource> { HttpMetadataSource(File(app.filesDir, "param-metadata"), Dispatchers.IO) }
            single<PlanFiles> { planFiles }
            single<OfflineMaps> { MapLibreOfflineMaps(File(app.filesDir, "maps").path, TileSources.available()) }
            single { MbtilesPicker { planFiles.pickAndCopy(File(app.filesDir, "maps")) } }
        }
    }
    RegisterPlanFiles(planFiles)
    RegisterBluetoothPermission(serialPorts)
    App(platform, lockKeysInBackground = true)
}

/**
 * Lets [ports] show Android's "Nearby devices" prompt (BLUETOOTH_CONNECT, Android 12+) while this UI is up. Asked the
 * first time the Links tab lists serial ports without it, never at app start. After Allow, Refresh lists the paired
 * devices; after two refusals Android stops showing the prompt, and the permission is in Android's app settings.
 */
@Composable
private fun RegisterBluetoothPermission(ports: SerialPorts) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    DisposableEffect(ports) {
        ports.requestBluetoothPermission = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) launcher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
        onDispose { ports.requestBluetoothPermission = null }
    }
}
