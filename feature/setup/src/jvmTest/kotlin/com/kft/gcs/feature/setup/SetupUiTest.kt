package com.kft.gcs.feature.setup

import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.vehicle.AccelPosition
import com.kft.gcs.core.vehicle.Severity
import com.kft.gcs.core.vehicle.StatusMessage
import com.kft.gcs.ui.design.KftTheme
import com.kft.gcs.ui.design.ThemeMode
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Setup tab's pages as an operator sees them, headless. Each writes `build/reports/45-setup-*.png`; the ones worth
 * keeping are copied to `docs/design`.
 */
@OptIn(ExperimentalTestApi::class)
class SetupUiTest {
    private val ready = CalibrationUiState(canStart = true, hint = null, vehicleKind = VehicleKind.COPTER, canReboot = true)

    /** The topmost window: the screen, or the dialog when one is open (a dialog is a root of its own). */
    private fun androidx.compose.ui.test.ComposeUiTest.snapshot(name: String) {
        val out = File("build/reports/45-setup-$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", out)
    }

    private fun failsafeState(): SetupUiState {
        val values = mapOf("FS_THR_ENABLE" to 1f, "FS_THR_VALUE" to 975f, "FS_GCS_ENABLE" to 0f, "FS_GCS_TIMEOUT" to 5f, "BATT_FS_LOW_ACT" to 2f,
            "BATT_LOW_VOLT" to 13.2f, "BATT_LOW_MAH" to 0f, "BATT_FS_CRT_ACT" to 1f, "BATT_CRT_VOLT" to 13.5f, "BATT_CRT_MAH" to 0f, "FENCE_ENABLE" to 1f, "FENCE_TYPE" to 3f)
        return SetupUiState(
            page = SetupPage.FAILSAFES, vehicle = "Copter · 4.6.3 · disarmed · Loiter", notice = null, params = "1 312 parameters read",
            canDownload = true, canWrite = true,
            sections = failsafeSections(VehicleKind.COPTER).map { s ->
                FieldSection(s.title, s.note, s.fields.map { f -> FieldRow(f.param, f.label, values[f.param]?.let { displayValue(f.kind, it) } ?: "—", f.help, f.param in values) })
            },
            problems = armingProblems(VehicleKind.COPTER, values),
        )
    }

    @Test
    fun failsafesPageListsValuesAndOpensTheEditor() {
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) runDesktopComposeUiTest(width = 1100, height = 900) {
            var clicked: String? = null
            setContent { KftTheme(mode) { Surface { SetupScreen(failsafeState(), ready, SetupActions(onField = { clicked = it }), CalibrationActions()) } } }
            onNodeWithText("Enabled, always RTL").assertExists()
            onNodeWithText("The FC will refuse to arm: Battery: the low voltage must be above the critical voltage.").assertExists()
            snapshot("failsafes-${mode.name.lowercase()}")
            onNodeWithText("Low battery action").performClick()
            assertEquals("BATT_FS_LOW_ACT", clicked)
        }
    }

    @Test
    fun theEditDialogShowsTheChoicesThenTheQuestion() = runDesktopComposeUiTest(width = 1100, height = 900) {
        val kind = failsafeSections(VehicleKind.COPTER)[2].fields[0].kind
        val edit = FieldEdit("BATT_FS_LOW_ACT", "Low battery action", null, "RTL", kind, "2")
        setContent { KftTheme(ThemeMode.DARK) { Surface { SetupScreen(failsafeState().copy(edit = edit), ready, SetupActions(), CalibrationActions()) } } }
        onNodeWithText("SmartRTL or Land").assertExists()
        snapshot("edit-choice")
    }

    @Test
    fun accelerometerAsksForThePosition() = runDesktopComposeUiTest(width = 1100, height = 900) {
        var placed = false
        val cal = ready.copy(
            running = Calibration.ACCEL, canStart = false, hint = "Accelerometer calibration is running.", accel = AccelUi(waitingFor = AccelPosition.NOSE_DOWN),
            log = listOf(StatusMessage("Place vehicle nose DOWN and press any key.", Severity.ERROR)),
        )
        setContent { KftTheme(ThemeMode.DARK) { Surface { SetupScreen(SetupUiState(page = SetupPage.ACCEL, vehicle = "Copter · 4.6.3 · disarmed", notice = null), cal, SetupActions(), CalibrationActions(onAccelPlaced = { placed = true })) } } }
        onNodeWithText("Position 4 of 6").assertExists()
        onNodeWithText("Calibrate level").assertIsNotEnabled()
        snapshot("accel")
        onNodeWithText("Vehicle is nose down").performClick()
        assertEquals(true, placed)
    }

    @Test
    fun compassShowsEachCompassProgress() = runDesktopComposeUiTest(width = 1100, height = 760) {
        val cal = ready.copy(
            running = Calibration.COMPASS, canStart = false,
            compass = CompassUi(listOf(CompassRow(1, 100, "Done", "7.5 mGauss fit", true, true), CompassRow(2, 64, "Collecting, step 2", null, false, false))),
        )
        setContent { KftTheme(ThemeMode.DARK) { Surface { SetupScreen(SetupUiState(page = SetupPage.COMPASS, notice = null), cal, SetupActions(), CalibrationActions()) } } }
        onNodeWithText("64 % · Collecting, step 2").assertExists()
        snapshot("compass")
    }

    @Test
    fun radioReviewBlocksSaveOnAProblem() = runDesktopComposeUiTest(width = 1100, height = 900) {
        val ranges = listOf(RcRange(1000, 2000), RcRange(1010, 1990), RcRange(990, 2010), RcRange(1490, 1510), RcRange(1100, 1900))
        val values = (1..5).flatMap { ch -> listOf("RC${ch}_MIN" to 1100f, "RC${ch}_MAX" to 1900f, "RC${ch}_TRIM" to 1500f) }.toMap()
        val review = rcCalibrationResult(ranges, listOf(1500, 1500, 995, 1500, 1100), values, VehicleKind.COPTER)
        val roles = listOf("Roll", "Pitch", "Throttle", "Yaw", null)
        val cal = ready.copy(
            running = Calibration.RADIO, canStart = false,
            radio = RadioUi(RadioStep.REVIEW, ranges.mapIndexed { i, r -> RadioChannel(i + 1, roles[i], listOf(1500, 1500, 995, 1500, 1100)[i], r.min, r.max) }, review),
        )
        setContent { KftTheme(ThemeMode.DARK) { Surface { SetupScreen(SetupUiState(page = SetupPage.RADIO, notice = null), cal, SetupActions(), CalibrationActions()) } } }
        onNodeWithText("Save to vehicle").assertIsNotEnabled()
        onNodeWithText("Yaw (channel 4) didn't move. Move every stick to both ends.").assertExists()
        snapshot("radio-review")
    }

    @Test
    fun hardwareIdListsBoardAndSensors() = runDesktopComposeUiTest(width = 1100, height = 760) {
        val state = SetupUiState(
            page = SetupPage.HARDWARE, notice = null, canDownload = true,
            board = listOf("Firmware" to "4.6.3 (d91a40ef)", "Board type" to "140", "USB id" to "2dae:1016", "Unique id" to "3a001f000d51333034383600"),
            sensors = sensorRows(mapOf("INS_ACC_ID" to 3_407_882f, "INS_GYR_ID" to 3_407_882f, "COMPASS_DEV_ID" to 97_539f, "COMPASS_DEV_ID2" to 658_953f, "BARO1_DEVID" to 721_666f)),
        )
        setContent { KftTheme(ThemeMode.DARK) { Surface { SetupScreen(state, ready, SetupActions(), CalibrationActions()) } } }
        onNodeWithText("Compass 1 · DroneCAN sensor").assertExists()
        onNodeWithText("Reboot FC").assertIsEnabled()
        snapshot("hardware")
    }
}
