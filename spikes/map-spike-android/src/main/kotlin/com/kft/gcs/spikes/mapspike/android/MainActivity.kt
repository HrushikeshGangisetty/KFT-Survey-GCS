package com.kft.gcs.spikes.mapspike.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.kft.gcs.spikes.mapspike.SpikeApp

/**
 * Android spike entry point (M5). No Esri key on Android yet: satellite is desktop-only in this spike,
 * because shipping a key inside an APK needs its own decision (see ADR-001).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SpikeApp(satelliteEnabled = false) }
    }
}
