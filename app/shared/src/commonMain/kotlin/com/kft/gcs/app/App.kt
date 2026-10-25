package com.kft.gcs.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kft.gcs.app.di.allModules
import com.kft.gcs.feature.connections.ConnectionsRoute
import com.kft.gcs.feature.fly.FleetRoute
import com.kft.gcs.feature.fly.FleetViewModel
import com.kft.gcs.feature.fly.FlyRoute
import com.kft.gcs.feature.fly.FlyViewModel
import com.kft.gcs.feature.fly.VideoViewModel
import com.kft.gcs.feature.params.ParamsRoute
import com.kft.gcs.feature.params.ParamsViewModel
import com.kft.gcs.feature.plan.PlanRoute
import com.kft.gcs.feature.plan.PlanViewModel
import com.kft.gcs.feature.settings.OfflineMapsRoute
import com.kft.gcs.feature.settings.OfflineMapsViewModel
import com.kft.gcs.feature.settings.SigningKeysEffect
import com.kft.gcs.feature.settings.SigningKeysRoute
import com.kft.gcs.feature.settings.SigningKeysViewModel
import com.kft.gcs.feature.settings.SigningUnlockPrompt
import com.kft.gcs.feature.setup.CalibrationViewModel
import com.kft.gcs.feature.setup.SetupRoute
import com.kft.gcs.feature.setup.SetupViewModel
import com.kft.gcs.ui.design.KftIcons
import com.kft.gcs.ui.design.KftLogo
import com.kft.gcs.ui.design.KftNavigationRail
import com.kft.gcs.ui.design.KftTheme
import com.kft.gcs.ui.design.RailItem
import com.kft.gcs.ui.design.Spacing
import com.kft.gcs.ui.design.ThemeMode
import com.kft.gcs.ui.design.TooltipIconButton
import com.kft.gcs.ui.design.VehicleChip
import com.kft.gcs.ui.design.VehicleSwitcher
import com.kft.gcs.ui.map.CameraRequest
import com.kft.gcs.ui.map.MapView
import com.kft.gcs.ui.map.VehicleColors
import com.kft.gcs.ui.video.PipControls
import com.kft.gcs.ui.video.PipState
import com.kft.gcs.ui.video.VideoView
import com.kft.gcs.ui.video.pip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.zIndex
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject
import org.koin.core.module.Module
import org.koin.dsl.koinConfiguration

/**
 * Root composable shared by the Android and desktop shells: starts Koin, applies the theme, and hosts navigation.
 * [platformModule] carries what only a shell can build, today the platform's serial ports (Android's need a Context).
 * [lockKeysInBackground] (Android) locks the signing keys when the app leaves the screen (PRD "Key management").
 * Routes live here, not in features, because features must never import each other (CLAUDE.md §2).
 */
@Composable
fun App(platformModule: Module, shortcuts: KeyShortcuts = KeyShortcuts(), lockKeysInBackground: Boolean = false) {
    KoinApplication(configuration = koinConfiguration { modules(allModules + platformModule) }) {
        val prefs: ThemeStore = koinInject()
        // Dark unless the operator picked another theme: a dark chrome keeps the map the brightest thing on screen.
        // Read once, written on every change; a missing or unknown value falls back to dark.
        var theme by remember { mutableStateOf(ThemeMode.entries.firstOrNull { it.name == prefs.read()?.trim() } ?: ThemeMode.DARK) }
        KftTheme(theme) {
            Surface(Modifier.fillMaxSize()) {
                val nav = rememberNavController()
                if (lockKeysInBackground) {
                    val keys: SigningKeysViewModel = koinViewModel()
                    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { keys.onAppBackgrounded() }
                }
                // safeDrawing: Android 15 draws edge-to-edge, so without this the UI sits under the status bar (ADR-001 F3).
                Row(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    AppRail(nav, theme, onThemeSelected = { theme = it; prefs.write(it.name) })
                    MapAndScreens(nav, shortcuts, Modifier.weight(1f))
                }
            }
        }
    }
}

/** Where the chosen [ThemeMode] is kept between runs: one word in a text file, like the other stores. */
interface ThemeStore {
    fun read(): String?
    fun write(text: String)
}

/**
 * The one map, and the screens on top of it.
 *
 * The map is composed once, below the NavHost, and never leaves composition (ADR-001 F10). Tearing down a MapLibre
 * desktop session and creating a new one corrupts the native heap in maplibre-compose 0.17.0 (0xC0000374 on the
 * second Fly→Links), so screens never own a map: Fly and Plan hand their overlays and callbacks to this one, and
 * Links covers it with an opaque Surface.
 *
 * The map ViewModels (Fly, Plan, Maps, and Video, which trades places with the map) are created here, at window scope, and passed to their routes, so the map and
 * the panels read the same instance. The plan's route and markers show on the Fly view too, but only the Plan tab
 * can edit them. The Maps tab adds its downloaded areas' outlines and hears where the map is looking.
 */
@Composable
private fun MapAndScreens(nav: NavHostController, shortcuts: KeyShortcuts, modifier: Modifier) {
    val fly: FlyViewModel = koinViewModel()
    val video: VideoViewModel = koinViewModel()
    val plan: PlanViewModel = koinViewModel()
    val maps: OfflineMapsViewModel = koinViewModel()
    val params: ParamsViewModel = koinViewModel() // window scope so it downloads on connect, before the Params tab is opened
    val fleet: FleetViewModel = koinViewModel() // window scope so alerts are raised whichever tab is showing
    val keys: SigningKeysViewModel = koinViewModel() // window scope so the unlock prompt shows at start, whichever tab is open
    // Window scope so a calibration keeps running (and can be cancelled) when the operator looks at another tab.
    val setup: SetupViewModel = koinViewModel()
    val calibration: CalibrationViewModel = koinViewModel()
    // A fleet alert (link lost, battery low, RTL, mission done) is a toast on every tab, and a line on the Fleet tab.
    val alerts = remember { SnackbarHostState() }
    LaunchedEffect(fleet) { fleet.effects.collect { alerts.showSnackbar("${it.vehicle}: ${it.text}") } }
    // A send refused because a signing vehicle has no key here (or the keys are locked) says so, with a way to fix it.
    LaunchedEffect(keys) {
        keys.effects.collect { effect ->
            when (effect) {
                is SigningKeysEffect.ShowMessage -> alerts.showSnackbar(effect.text)
                is SigningKeysEffect.Refused ->
                    if (alerts.showSnackbar(effect.text, actionLabel = "Open signing keys", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                        nav.openTab(Destination.KEYS)
                    }
            }
        }
    }
    val mapsState by maps.state.collectAsStateWithLifecycle()
    val flyState by fly.state.collectAsStateWithLifecycle()
    val planState by plan.state.collectAsStateWithLifecycle()
    val videoState by video.state.collectAsStateWithLifecycle()
    val current by nav.currentBackStackEntryAsState()
    val planning = current?.destination?.route == Destination.PLAN.route
    val mapsTab = current?.destination?.route == Destination.MAPS.route
    val keysTab = current?.destination?.route == Destination.KEYS.route

    // One camera for the map, moved by whichever screen asked last: Fly (follow the vehicle) or Plan (show an import).
    var camera by remember { mutableStateOf<CameraRequest?>(null) }
    LaunchedEffect(flyState.cameraRequest) { flyState.cameraRequest?.let { camera = it } }
    LaunchedEffect(planState.cameraRequest) { planState.cameraRequest?.let { camera = it } }

    // The Plan tab's undo/redo keys; the desktop window calls this for every key press (see KeyShortcuts).
    SideEffect { shortcuts.handler = { planning && planShortcut(it, plan) } }

    // Video on the Fly tab only, in a picture-in-picture window. Swapping it with the map changes only each one's size
    // and z-order (zIndex): both stay composed where they are, so the map is never torn down (F10) and the stream
    // isn't reopened. Z-order, bottom to top: the big one (0), the screen's panels (1), the small one (2), its controls (3).
    val videoSource = videoState.source.takeIf { current?.destination?.route == Destination.FLY.route && videoState.visible }
    val videoBig = videoSource != null && videoState.swapped
    val pip = remember { PipState() }
    val density = LocalDensity.current
    // Any pointer event counts as operator activity for the signing keys' idle timer. It only watches (Initial pass,
    // nothing consumed), so it cannot steal a click from anything below.
    val watchActivity = Modifier.pointerInput(keys) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial)
                keys.onUserActive()
            }
        }
    }
    Box(modifier.then(watchActivity).onSizeChanged { pip.area = with(density) { DpSize(it.width.toDp(), it.height.toDp()) } }) {
        MapView(
            if (videoBig) Modifier.pip(pip).zIndex(2f) else Modifier.fillMaxSize(),
            basemap = flyState.selectedBasemap,
            overlays = (if (mapsTab) mapsState.overlays else emptyList()) + planState.overlays + flyState.overlays,
            cameraRequest = camera,
            onMapClick = if (planning) plan::onMapClick else null,
            onMarkerClick = if (planning) plan::onMarkerClick else null,
            onMarkerDrag = if (planning) plan::onMarkerDragged else null,
            onMarkerDragEnd = if (planning) { _ -> plan.onMarkerDragFinished() } else null,
            onViewChanged = maps::onViewChanged,
        )
        videoSource?.let { VideoView(it, if (videoBig) Modifier.fillMaxSize() else Modifier.pip(pip).zIndex(2f), showStats = videoState.showStats) }
        NavHost(nav, startDestination = START.route, modifier = Modifier.zIndex(1f)) {
            // Fly and Plan draw only their panels; where they draw nothing, input falls through to the map.
            composable(Destination.FLY.route) { FlyRoute(fly, video) }
            composable(Destination.PLAN.route) { PlanRoute(plan) }
            composable(Destination.MAPS.route) { OfflineMapsRoute(maps) }
            // Opaque and full size, so it hides the map; M3 Surface also stops clicks reaching the map.
            composable(Destination.CONNECTIONS.route) { Surface(Modifier.fillMaxSize()) { ConnectionsRoute() } }
            composable(Destination.KEYS.route) { Surface(Modifier.fillMaxSize()) { SigningKeysRoute(keys) } }
            composable(Destination.PARAMS.route) { Surface(Modifier.fillMaxSize()) { ParamsRoute(params) } }
            composable(Destination.SETUP.route) { Surface(Modifier.fillMaxSize()) { SetupRoute(setup, calibration) } }
            composable(Destination.FLEET.route) { Surface(Modifier.fillMaxSize()) { FleetRoute(fleet) { slot -> VehicleColors[slot.mod(VehicleColors.size)] } } }
        }
        SnackbarHost(alerts, Modifier.align(Alignment.TopCenter).zIndex(4f))
        // The Keys tab has the PIN field itself, so the prompt would only duplicate it there.
        if (!keysTab) SigningUnlockPrompt(keys)
        if (videoSource != null) PipControls(pip, video::onSwapClicked, video::onStatsToggled, video::onHideClicked, Modifier.zIndex(3f))
    }
}

/**
 * Keyboard shortcuts, handed from `App()` to the desktop window. The window's `onPreviewKeyEvent` sees every key
 * before any composable, whatever has focus. A handler inside the UI only hears keys while something in it is
 * focused, and a button that gets disabled (Read, during the transfer) drops the focus to the window: the first SITL
 * run lost Ctrl+Z that way. On the tablet the Undo/Redo buttons do the same job, so Android doesn't wire this up.
 */
class KeyShortcuts {
    internal var handler: (KeyEvent) -> Boolean = { false }

    /** True when the key was a shortcut and has been handled. */
    fun onKey(event: KeyEvent): Boolean = handler(event)
}

/** Ctrl+Z undo, Ctrl+Shift+Z or Ctrl+Y redo (Cmd on a Mac keyboard). True = handled. */
private fun planShortcut(event: KeyEvent, plan: PlanViewModel): Boolean {
    if (event.type != KeyEventType.KeyDown || !(event.isCtrlPressed || event.isMetaPressed)) return false
    when {
        event.key == Key.Z && !event.isShiftPressed -> plan.onUndoClicked()
        event.key == Key.Z || event.key == Key.Y -> plan.onRedoClicked()
        else -> return false
    }
    return true
}

/** Top-level destinations, in rail order. A navigation rail suits tablets and desktop, the P0 screens (spec §2.5). */
internal enum class Destination(val route: String, val label: String) {
    FLY("fly", "Fly"),
    FLEET("fleet", "Fleet"),
    PLAN("plan", "Plan"),
    PARAMS("params", "Params"),
    SETUP("setup", "Setup"),
    MAPS("maps", "Maps"),
    CONNECTIONS("connections", "Links"),
    KEYS("keys", "Keys"),
}

/** Goes to a tab. Tabs, not a stack: re-selecting a tab returns to it instead of piling up copies. */
private fun NavHostController.openTab(destination: Destination) = navigate(destination.route) {
    launchSingleTop = true
    popUpTo(START.route) { saveState = true }
    restoreState = true
}

/** The Fly view first, like every GCS: the map is what you want to see when the app opens. */
private val START = Destination.FLY

/** The rail: logo, the tabs, and at the bottom the theme menu and About (which holds the version). */
@Composable
private fun AppRail(nav: NavHostController, theme: ThemeMode, onThemeSelected: (ThemeMode) -> Unit) {
    val current by nav.currentBackStackEntryAsState()
    var themeMenu by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    // The same window-scoped FlyViewModel the map uses: it knows every vehicle and which one is selected.
    val fly: FlyViewModel = koinViewModel()
    val vehicles = fly.state.collectAsStateWithLifecycle().value.vehicles
    KftNavigationRail(
        items = Destination.entries.map { destination ->
            RailItem(
                label = destination.label,
                icon = when (destination) {
                    Destination.FLY -> KftIcons.Fly
                    Destination.FLEET -> KftIcons.Stats
                    Destination.PLAN -> KftIcons.Plan
                    Destination.PARAMS -> KftIcons.Params
                    Destination.SETUP -> KftIcons.Setup
                    Destination.MAPS -> KftIcons.Maps
                    Destination.CONNECTIONS -> KftIcons.Link
                    Destination.KEYS -> KftIcons.Lock
                },
                selected = current?.destination?.route == destination.route,
                onClick = { nav.openTab(destination) },
            )
        },
        extra = {
            // Shown only when there is a choice. The colours are the map's, so a drone is the same colour in both places.
            if (vehicles.size > 1) {
                VehicleSwitcher(
                    vehicles.map { v ->
                        VehicleChip(v.label, VehicleColors[v.slot.mod(VehicleColors.size)], v.selected, v.connected) { fly.onVehicleSelected(v.id) }
                    },
                )
            }
        },
        footer = {
            Box {
                TooltipIconButton(KftIcons.Theme, "Theme", { themeMenu = true })
                DropdownMenu(themeMenu, onDismissRequest = { themeMenu = false }) {
                    ThemeMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            leadingIcon = { if (mode == theme) Icon(KftIcons.Check, contentDescription = "Current") },
                            onClick = { themeMenu = false; onThemeSelected(mode) },
                        )
                    }
                }
            }
            TooltipIconButton(KftIcons.About, "About", { about = true }, Modifier.padding(bottom = Spacing.s))
        },
    )
    if (about) AboutDialog(onDismiss = { about = false })
}

/** Logo, version and platform, and the notice the icon licence asks for (Apache-2.0 §4, see `NOTICE`). */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { KftLogo(Modifier.width(180.dp), full = true) },
        title = { Text("KFT Survey GCS") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text("Version ${AppInfo.VERSION} · ${Platform.name}")
                Text(
                    "Icons: Material Symbols by Google, Apache License 2.0.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
