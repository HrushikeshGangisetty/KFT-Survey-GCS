package com.kft.gcs.app

import androidx.compose.foundation.layout.Row
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
                Row {
                    AppRail(nav)
                    NavHost(nav, startDestination = Destination.CONNECTIONS.route) {
                        composable(Destination.CONNECTIONS.route) { ConnectionsRoute() }
                    }
                }
            }
        }
    }
}

/** Top-level destinations. A navigation rail suits tablets and desktop, the P0 screens (spec §2.5). */
internal enum class Destination(val route: String, val label: String) {
    CONNECTIONS("connections", "Links"),
}

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
                        popUpTo(Destination.CONNECTIONS.route) { saveState = true }
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
