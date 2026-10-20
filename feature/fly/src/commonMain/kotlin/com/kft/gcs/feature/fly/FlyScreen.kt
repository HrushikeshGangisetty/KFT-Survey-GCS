package com.kft.gcs.feature.fly

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kft.gcs.core.vehicle.Severity
import com.kft.gcs.ui.design.KftIcons
import com.kft.gcs.ui.design.MapCard
import com.kft.gcs.ui.design.MinTouchTarget
import com.kft.gcs.ui.design.Spacing
import com.kft.gcs.ui.design.StatTile
import com.kft.gcs.ui.design.Status
import com.kft.gcs.ui.design.StatusChip
import com.kft.gcs.ui.design.TooltipIconButton
import org.koin.compose.viewmodel.koinViewModel

/**
 * Collects the ViewModel's state and hands it to [FlyScreen]. `App()` passes the ViewModel in, because it also feeds
 * the same state (overlays, basemap, camera) to the one shared map (ADR-001 F10).
 */
@Composable
fun FlyRoute(
    viewModel: FlyViewModel = koinViewModel(),
    video: VideoViewModel = koinViewModel(),
    companion: CompanionViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val videoState by video.state.collectAsStateWithLifecycle()
    var videoDialog by remember { mutableStateOf(false) }
    var companionOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        FlyScreen(
            state, viewModel::onBasemapSelected, viewModel::onCenterClicked, viewModel::onClearTrackClicked,
            onVideo = { videoDialog = true }, onCompanion = { companionOpen = !companionOpen },
        )
        if (companionOpen) {
            // Collected only while open, so the 10 Hz age ticker stops with the panel.
            val companionState by companion.state.collectAsStateWithLifecycle()
            CompanionPanel(
                companionState, companion::onConnectClicked, companion::onDisconnectClicked, companion::onSettingsSaved,
                onClose = { companionOpen = false },
                // Left of the map buttons, clear of the attribution line.
                modifier = Modifier.align(Alignment.TopEnd).padding(top = Spacing.m, end = MinTouchTarget + Spacing.m * 2, bottom = Spacing.xl),
            )
        }
    }
    if (videoDialog) {
        VideoDialog(videoState, video::onSourceSelected, video::onRtspAdded, video::onProfileDeleted, onDismiss = { videoDialog = false })
    }
}

/**
 * Map-first layout, as in every GCS: the map (drawn underneath by `App()`) fills the screen. Top left: status chips
 * (link, KFT login, GPS, "mission ≠ plan"), and under them the HUD card of stat tiles. Top right: the map buttons as
 * a small vertical stack. Bottom left: the latest vehicle message. Everything else is left empty so the map shows and
 * takes the clicks. The video, when on, floats in a picture-in-picture window drawn by `App()` (it swaps places with the
 * map, which only `App()` owns). Monitoring only: no flight-action buttons (spec S9).
 */
@Composable
fun FlyScreen(
    state: FlyUiState,
    onBasemapSelected: (String) -> Unit,
    onCenter: () -> Unit,
    onClearTrack: () -> Unit,
    onVideo: () -> Unit = {},
    onCompanion: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize().padding(Spacing.m)) {
        Column(Modifier.align(Alignment.TopStart), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                if (state.connected) {
                    StatusChip(state.firmware ?: "Connected", Status.OK, icon = KftIcons.Link)
                } else {
                    StatusChip("No vehicle", Status.WARN, icon = KftIcons.LinkOff)
                }
                state.login?.let { StatusChip(it.text, if (it.warning) Status.WARN else Status.OK, icon = KftIcons.Login) }
                if (state.connected) StatusChip(state.gps.value, state.gps.status(), icon = KftIcons.Gps)
                state.missionWarning?.let { StatusChip(it, Status.WARN) }
                state.vehicleProblem?.let { StatusChip(it, Status.CRITICAL) }
            }
            MapCard {
                // Four tiles a row: Mode, State, Alt, Speed / Heading, Battery, Mission, Photos.
                FlowRow(
                    maxItemsInEachRow = 4,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.l),
                    verticalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    state.hud.forEach { StatTile(it.label, it.value, status = it.status()) }
                }
            }
        }

        MapButtons(state, onBasemapSelected, onCenter, onClearTrack, onVideo, onCompanion, Modifier.align(Alignment.TopEnd))

        state.message?.let { message ->
            val status = when (message.severity) {
                Severity.ERROR -> Status.CRITICAL
                Severity.WARNING -> Status.WARN
                Severity.INFO -> Status.NEUTRAL
            }
            // Clear of the map's attribution line along the bottom edge.
            StatusChip(message.text, status, Modifier.align(Alignment.BottomStart).padding(bottom = Spacing.xl))
        }
    }
}

/**
 * Basemap (only when there's a choice), centre on the vehicle, clear the flown track, video, companion panel: icon
 * buttons with tooltips.
 */
@Composable
private fun MapButtons(
    state: FlyUiState,
    onBasemapSelected: (String) -> Unit,
    onCenter: () -> Unit,
    onClearTrack: () -> Unit,
    onVideo: () -> Unit,
    onCompanion: () -> Unit,
    modifier: Modifier,
) {
    MapCard(modifier, padding = false) {
        if (state.basemaps.size > 1) {
            var open by remember { mutableStateOf(false) }
            Box {
                TooltipIconButton(KftIcons.Layers, "Basemap: ${state.selectedBasemap.name}", { open = true })
                DropdownMenu(open, onDismissRequest = { open = false }) {
                    state.basemaps.forEach { b ->
                        DropdownMenuItem(
                            text = { Text(b.name) },
                            leadingIcon = { if (b == state.selectedBasemap) Icon(KftIcons.Check, contentDescription = "Selected") },
                            onClick = { open = false; onBasemapSelected(b.id) },
                        )
                    }
                }
            }
        }
        TooltipIconButton(KftIcons.Center, "Centre on vehicle", onCenter, enabled = state.canCenter)
        TooltipIconButton(KftIcons.ClearTrack, "Clear track and photo count", onClearTrack)
        TooltipIconButton(KftIcons.Video, "Video", onVideo)
        TooltipIconButton(KftIcons.Stats, "Companion (Pi) diagnostics", onCompanion)
    }
}

private fun HudItem.status() = if (warning) Status.WARN else Status.NEUTRAL
