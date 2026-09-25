package com.kft.gcs.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kft.gcs.app.di.allModules
import com.kft.gcs.feature.connections.ConnectionsRoute
import com.kft.gcs.feature.fly.FlyRoute
import org.koin.compose.KoinApplication
import org.koin.dsl.koinConfiguration

/**
 * Root composable shared by the Android and desktop shells: starts Koin, applies the theme, and hosts navigation.
 * Routes live here, not in features, because features must never import each other (CLAUDE.md §2).
 */
@Composable
fun App() {
    KoinApplication(configuration = koinConfiguration { modules(allModules) }) {
        MaterialTheme(colorScheme = KftColors.dark) {
            Surface(Modifier.fillMaxSize()) {
                val nav = rememberNavController()
                // safeDrawing: Android 15 draws edge-to-edge, so without this the UI sits under the status bar (ADR-001 F3).
                Row(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    AppRail(nav)
                    Box(Modifier.weight(1f)) {
                        // The Fly screen (and its map) is composed once, below the NavHost, and never leaves
                        // composition. Other tabs cover it with an opaque Surface instead of replacing it.
                        // Why: tearing down a MapLibre desktop session and creating a new one corrupts the native
                        // heap in maplibre-compose 0.17.0 (0xC0000374 on the second Fly→Links). See ADR-001, GS-2.
                        FlyRoute()
                        NavHost(nav, startDestination = START.route) {
                            // Empty on purpose: it draws nothing and handles no input, so the Fly layer shows through.
                            composable(Destination.FLY.route) {}
                            // Opaque and full size, so it hides the map; M3 Surface also stops clicks reaching the map.
                            composable(Destination.CONNECTIONS.route) { Surface(Modifier.fillMaxSize()) { ConnectionsRoute() } }
                        }
                    }
                }
            }
        }
    }
}

/** Top-level destinations. A navigation rail suits tablets and desktop, the P0 screens (spec §2.5). */
internal enum class Destination(val route: String, val label: String) {
    FLY("fly", "Fly"),
    CONNECTIONS("connections", "Links"),
}

/** The Fly view first, like every GCS: the map is what you want to see when the app opens. */
private val START = Destination.FLY

@Composable
private fun AppRail(nav: NavHostController) {
    val current by nav.currentBackStackEntryAsState()
    NavigationRail(header = { Text("KFT", style = MaterialTheme.typography.titleMedium) }) {
        Destination.entries.forEach { destination ->
            NavigationRailItem(
                selected = current?.destination?.route == destination.route,
                onClick = {
                    nav.navigate(destination.route) {
                        // Tabs, not a stack: re-selecting a tab returns to it instead of piling up copies.
                        launchSingleTop = true
                        popUpTo(START.route) { saveState = true }
                        restoreState = true
                    }
                },
                icon = { Text(destination.label.take(1), style = MaterialTheme.typography.titleMedium) },
                label = { Text(destination.label) },
            )
        }
        Text("v${AppInfo.VERSION}\n${Platform.name}", Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
    }
}
