package com.kft.gcs.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Root composable shared by the Android and desktop shells.
 *
 * Pass 1 (skeleton) only proves both shells render the same shared UI. Navigation, Koin and the
 * first real screen (Connections) arrive in the next pass.
 */
@Composable
fun App() {
    MaterialTheme(colorScheme = KftColors.dark) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("KFT GCS", style = MaterialTheme.typography.headlineMedium)
                Text("Survey ground control station · v${AppInfo.VERSION}")
                Text("Running on ${Platform.name}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
