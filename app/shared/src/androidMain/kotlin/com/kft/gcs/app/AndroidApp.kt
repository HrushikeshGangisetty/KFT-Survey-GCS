package com.kft.gcs.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kft.gcs.core.mavlink.SerialPorts
import org.koin.dsl.module

/** [App] for the Android shell, mirroring `DesktopApp`: adds what needs a Context (USB-OTG serial ports). */
@Composable
fun AndroidApp(context: Context) {
    val platform = remember { module { single { SerialPorts(context.applicationContext) } } }
    App(platform)
}
