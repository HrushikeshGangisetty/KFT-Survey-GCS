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
import com.kft.gcs.feature.fly.FlyViewModel
import com.kft.gcs.feature.plan.PlanRoute
import com.kft.gcs.feature.plan.PlanViewModel
import com.kft.gcs.ui.map.MapView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.compose.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.koinConfiguration

/**
 * Root composable shared by the Android and desktop shells: starts Koin, applies the theme, and hosts navigation.
 * [platformModule] carries what only a shell can build, today the platform's serial ports (Android's need a Context).
 * Routes live here, not in features, because features must never import each other (CLAUDE.md §2).
 */
@Composable
fun App(platformModule: Module) {
    KoinApplication(configuration = koinConfiguration { modules(allModules + platformModule) }) {
        MaterialTheme(colorScheme = KftColors.dark) {
            Surface(Modifier.fillMaxSize()) {
                val nav = rememberNavController()
                // safeDrawing: Android 15 draws edge-to-edge, so without this the UI sits under the status bar (ADR-001 F3).
                Row(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    AppRail(nav)
                    Box(Modifier.weight(1f)) { MapAndScreens(nav) }
                }
            }
        }
    }
}

/**
 * The one map, and the screens on top of it.
 *
 * The map is composed once, below the NavHost, and never leaves composition (ADR-001 F10). Tearing down a MapLibre
 * desktop session and creating a new one corrupts the native heap in maplibre-compose 0.17.0 (0xC0000374 on the
 * second Fly→Links), so screens never own a map: Fly and Plan hand their overlays and callbacks to this one, and
 * Links covers it with an opaque Surface.
 *
 * Both map ViewModels are created here, at window scope, and passed to their routes, so the map and the panels read
 * the same instance. The plan's route and markers show on the Fly view too, but only the Plan tab can edit them.
 */
@Composable
private fun MapAndScreens(nav: NavHostController) {
    val fly: FlyViewModel = koinViewModel()
    val plan: PlanViewModel = koinViewModel()
    val flyState by fly.state.collectAsStateWithLifecycle()
    val planState by plan.state.collectAsStateWithLifecycle()
    val current by nav.currentBackStackEntryAsState()
    val planning = current?.destination?.route == Destination.PLAN.route

    MapView(
        Modifier.fillMaxSize(),
        basemap = flyState.selectedBasemap,
        overlays = planState.overlays + flyState.overlays,
        cameraRequest = flyState.cameraRequest,
        onMapClick = if (planning) plan::onMapClick else null,
        onMarkerClick = if (planning) plan::onMarkerClick else null,
        onMarkerDrag = if (planning) plan::onMarkerDragged else null,
    )
    NavHost(nav, startDestination = START.route) {
        // Fly and Plan draw only their panels; where they draw nothing, input falls through to the map.
        composable(Destination.FLY.route) { FlyRoute(fly) }
        composable(Destination.PLAN.route) { PlanRoute(plan) }
        // Opaque and full size, so it hides the map; M3 Surface also stops clicks reaching the map.
        composable(Destination.CONNECTIONS.route) { Surface(Modifier.fillMaxSize()) { ConnectionsRoute() } }
    }
}

/** Top-level destinations. A navigation rail suits tablets and desktop, the P0 screens (spec §2.5). */
internal enum class Destination(val route: String, val label: String) {
    FLY("fly", "Fly"),
    PLAN("plan", "Plan"),
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
