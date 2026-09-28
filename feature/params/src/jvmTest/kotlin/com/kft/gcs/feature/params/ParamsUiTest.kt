package com.kft.gcs.feature.params

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.vehicle.KftLoginStatus
import com.kft.gcs.core.vehicle.VehicleState
import com.kft.gcs.ui.design.KftTheme
import com.kft.gcs.ui.design.ThemeMode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/** The Params screen driven like an operator would: real screen and ViewModel, fake vehicle and metadata. Headless. */
@OptIn(ExperimentalTestApi::class)
class ParamsUiTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /**
     * Download, open CAM1_TYPE, pick "1 · Servo" from its documented values, Set, read the question (it names the
     * restart), Confirm. Exactly one write reaches the vehicle, with the picked value, and the list shows it.
     */
    @Test
    fun editAParameterThroughADropdown() = runDesktopComposeUiTest(width = 1280, height = 900) {
        val repo = FakeParamRepository()
        val metadata = FakeMetadataSource().apply { files["https://autotest.ardupilot.org/Parameters/ArduCopter/apm.pdef.json"] = META_JSON }
        val vehicle = MutableStateFlow(
            VehicleState(connected = true, login = KftLoginStatus.AUTHENTICATED, vehicleKind = VehicleKind.COPTER, firmwareVersion = "4.8.0-dev"),
        )
        setContent { KftTheme(ThemeMode.DARK) { ParamsRoute(ParamsViewModel(vehicle, repo, FakeFiles(), metadata)) } }

        onNodeWithContentDescription("Download from vehicle").performClick()
        onNodeWithText("CAM1_TYPE").performClick()
        onNodeWithContentDescription("Choose a value").performClick()
        onNodeWithText("1 · Servo").performClick()
        onNodeWithText("Set").performClick()
        onNodeWithText("Change CAM1_TYPE from 0 to 1 on the vehicle? It takes effect after the flight controller restarts.").assertExists()
        onNodeWithText("Confirm").performClick()
        waitForIdle()

        assertEquals(listOf("CAM1_TYPE" to 1f), repo.sets)
        onNodeWithText("1  ·  Servo").assertExists() // the list row: value and its documented name
    }
}
