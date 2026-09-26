package com.kft.gcs.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kft.gcs.core.mavlink.SerialPorts
import com.kft.gcs.feature.connections.ProfileStore
import com.kft.gcs.feature.plan.PlanFiles
import com.kft.gcs.feature.plan.SettingsStore
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
    val platform = remember {
        module {
            single { SerialPorts(app) }
            single<ProfileStore> { FileTextStore(File(app.filesDir, "connection-profiles.json")) }
            single<SettingsStore> { FileTextStore(File(app.filesDir, "plan-settings.json")) }
            single<PlanFiles> { planFiles }
        }
    }
    RegisterPlanFiles(planFiles)
    App(platform)
}
