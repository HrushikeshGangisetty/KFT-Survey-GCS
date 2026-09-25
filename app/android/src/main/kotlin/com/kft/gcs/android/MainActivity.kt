package com.kft.gcs.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kft.gcs.app.AndroidApp

/**
 * Android entry point. It only hosts the shared [AndroidApp] composable.
 * configChanges in the manifest keep the activity alive across rotation/resizing, so a live
 * MAVLink link is never torn down just because the tablet rotated.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AndroidApp(this) }
    }
}
