package com.kft.gcs.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kft.gcs.core.mavlink.SerialPorts
import com.kft.gcs.feature.connections.ProfileStore
import java.io.File
import org.koin.dsl.module

/**
 * [App] for the Android shell, mirroring `DesktopApp`: adds what needs a Context (USB-OTG serial ports, and the
 * app's private files directory for saved profiles, which survives restarts and updates but not uninstall).
 */
@Composable
fun AndroidApp(context: Context) {
    val platform = remember {
        val app = context.applicationContext
        module {
            single { SerialPorts(app) }
            single<ProfileStore> { FileProfileStore(File(app.filesDir, "connection-profiles.json")) }
        }
    }
    App(platform)
}
