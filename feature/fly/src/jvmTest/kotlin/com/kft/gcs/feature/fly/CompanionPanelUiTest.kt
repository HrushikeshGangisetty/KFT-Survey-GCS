package com.kft.gcs.feature.fly

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.kft.gcs.core.companion.TrackPoint
import com.kft.gcs.ui.design.KftTheme
import com.kft.gcs.ui.design.Status
import com.kft.gcs.ui.design.ThemeMode
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The panel as drawn, after the `lost_reset` scenario (hand-made state). Headless, part of `./gradlew check`. It also
 * writes `build/reports/companion-panel.png`, the picture to look at when changing the layout.
 */
@OptIn(ExperimentalTestApi::class)
class CompanionPanelUiTest {
    private val state = CompanionUiState(
        url = "ws://127.0.0.1:8765",
        systemId = 1,
        connection = Line("Link", "Connected", Status.OK),
        on = true,
        hiddenReason = null,
        source = Line("Source", "SYNTHETIC · run synthetic-lost_reset-seed0", Status.WARN),
        vio = Line("VIO", "TRACKING", Status.OK),
        rows = listOf(
            Line("Position age", "0.0 s"),
            Line("Resets", "counter 1 · 1 this run", Status.WARN),
            Line("Map fix", "Rejected: too few inliers · 0.7 s old · 7 accepted, 1 rejected", Status.WARN),
        ),
        health = listOf(Line("vio", "OK · cpu 40% · queue 0 · input 0 ms · 0.2 s since received", Status.OK)),
        // 1 m/s north for 10 s, then a reset and 1 m north from the new origin.
        track = (0..200).map { TrackPoint(it * 0.05, 0.0, 0) } + (0..20).map { TrackPoint(it * 0.05, 0.0, 1) },
        currentSegment = 1,
        rejected = Line("Rejected", "2 records · last: schema_version 0.1, expected 0.2", Status.WARN),
        fcEkf = Line("FC EKF", "attitude · horiz vel · vert vel · pos rel", Status.OK),
        fcMessages = listOf("EKF3 IMU0 is using external nav data"),
    )

    @Test
    fun thePanelShowsEachPartAndHasNoVehicleCommands() = runDesktopComposeUiTest(width = 420, height = 1400) {
        setContent { KftTheme(ThemeMode.DARK) { CompanionPanel(state, {}, {}, { _, _ -> null }, {}) } }
        onNodeWithText("VIO: TRACKING").assertExists()
        onNodeWithText("counter 1 · 1 this run").assertExists()
        onNodeWithText("Rejected: too few inliers", substring = true).assertExists()
        onNodeWithText("FC (MAVLink, from the flight controller)").assertExists()
        // Track span 10 m (x 0 to 10); a quarter is 2.5 m, and the next 1-2-5 step up is 5 m.
        onNodeWithText("Grid 5 m", substring = true).assertExists()

        // Everything clickable on the panel: closing it, this GCS's own WebSocket, and its settings. Nothing that
        // reaches the vehicle (P7: no new buttons that command the vehicle).
        val clickable = onAllNodes(hasClickAction()).fetchSemanticsNodes().map { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                ?: node.children.firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString() }
        }
        // "Expand" is the settings section's chevron (SectionHeader's toggle).
        assertEquals(listOf("Close companion panel", "Disconnect", "Expand"), clickable)

        val out = File("build/reports/companion-panel.png").apply { parentFile.mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out)
    }
}
