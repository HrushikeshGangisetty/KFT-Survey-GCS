package com.kft.gcs.feature.plan

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.VehicleId
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.mission.Home
import com.kft.gcs.core.vehicle.FleetVehicle
import com.kft.gcs.core.vehicle.MissionSync
import com.kft.gcs.core.vehicle.VehicleState
import com.kft.gcs.ui.design.KftTheme
import com.kft.gcs.ui.design.ThemeMode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * The Plan panel driven like an operator would: real screen and ViewModel, fake vehicle link and files. The map
 * isn't part of this screen (`App()` owns it and forwards its clicks), so corners are added the way `App()` does it,
 * through [PlanViewModel.onMapClick]. Headless, part of `./gradlew check`.
 */
@OptIn(ExperimentalTestApi::class)
class PlanUiTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val vehicle = MutableStateFlow(
        VehicleState(connected = true, vehicleKind = VehicleKind.COPTER, home = Home(LatLon(-35.363261, 149.165230), 584.0)),
    )
    private val area = listOf(LatLon(-35.3620, 149.1650), LatLon(-35.3620, 149.1680), LatLon(-35.3640, 149.1680), LatLon(-35.3640, 149.1650))

    private fun ComposeUiTest.planWithASurvey(): PlanViewModel {
        val vm = PlanViewModel(vehicle, FakeMissions(), MissionSync(), PlanSettingsRepository(MemoryStore()), FakeFiles())
        setContent { KftTheme(ThemeMode.DARK) { PlanRoute(vm) } }
        onNodeWithContentDescription("Add survey").performClick()
        area.forEach(vm::onMapClick)
        waitForIdle()
        return vm
    }

    private fun PlanViewModel.survey() = state.value.survey!!.settings

    /**
     * Pass 30, on the screen: a survey split into 3 from its panel shows three missions; one is given to vehicle 2
     * from its menu, and the upload preview is a job's ("Upload 1 of 3 missions?").
     */
    @Test
    fun splitASurveyAndGiveAMissionToAVehicle() = runDesktopComposeUiTest(width = 1400, height = 1000) {
        val drone2 = VehicleId(1, 2u)
        val fleet = MutableStateFlow(listOf(FleetVehicle(VehicleId(1, 1u), 0, vehicle.value), FleetVehicle(drone2, 1, vehicle.value)))
        val toDrone2 = FakeMissions()
        val vm = PlanViewModel(vehicle, FakeMissions(), MissionSync(), PlanSettingsRepository(MemoryStore()), FakeFiles(), fleet) { toDrone2 }
        setContent { KftTheme(ThemeMode.DARK) { PlanRoute(vm) } }
        onNodeWithContentDescription("Add survey").performClick()
        area.forEach(vm::onMapClick)
        waitForIdle()

        onNodeWithText("Split for several drones").performScrollTo().performClick()
        onNodeWithText("Split into parts").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Missions").assertExists()
        onNodeWithText("M2  Survey 1 2/3").assertExists()
        assertEquals(3, vm.state.value.missions.size)

        onAllNodesWithText("Not assigned")[1].performScrollTo().performClick() // mission 2's vehicle menu
        onNodeWithText("Vehicle 2").performClick()
        waitForIdle()
        assertEquals(listOf("Not assigned", "Vehicle 2", "Not assigned"), vm.state.value.missions.map { it.vehicle })

        onNodeWithContentDescription("Upload mission to vehicle").performClick()
        onNodeWithText("Upload 1 of 3 missions?").assertExists()
        onNodeWithText("Upload").performClick()
        waitForIdle()
        assertTrue(toDrone2.uploaded.isNotEmpty(), "mission 2 went to vehicle 2")
    }

    /**
     * Altitude and overlap typed into the panel reach the plan, and the screen shows the result: 80 m gives the GSD
     * the camera maths gives (the readout under the field), and the line spacing follows the side overlap.
     */
    @Test
    fun addASurveyAndEditAltitudeAndOverlap() = runDesktopComposeUiTest(width = 1400, height = 1000) {
        val vm = planWithASurvey()
        onNodeWithText("4 corners").assertExists()
        val spacingBefore = vm.state.value.survey!!.stats.toMap().getValue("Line spacing")

        onNodeWithText("Altitude above home").performScrollTo().performTextReplacement("80")
        onNodeWithText("Side overlap").performScrollTo().performTextReplacement("60")
        waitForIdle()

        assertEquals(80.0, vm.survey().altitudeM)
        assertEquals(60.0, vm.survey().sideOverlapPct)
        onNodeWithText("${oneDecimalText(vm.state.value.survey!!.gsdCm!!)} cm/px").assertExists()
        val spacingAfter = vm.state.value.survey!!.stats.toMap().getValue("Line spacing")
        assertNotEquals(spacingBefore, spacingAfter, "the line spacing follows the side overlap")
        onNodeWithText(spacingAfter).assertExists() // the pinned stats footer shows the new value
    }

    /** Crosshatch: ticking it shows the altitude offset, and the Lines stat becomes "first + crossing". */
    @Test
    fun crosshatchFromThePanel() = runDesktopComposeUiTest(width = 1400, height = 1000) {
        val vm = planWithASurvey()
        onNodeWithText("Crosshatch: fly it again at 90°").performScrollTo().performClick()
        onNodeWithText("Second pass higher by").performScrollTo().performTextReplacement("10")
        waitForIdle()
        assertEquals(true to 10.0, vm.survey().crosshatch to vm.survey().crosshatchOffsetM)
        val lines = vm.state.value.survey!!.stats.toMap().getValue("Lines")
        assertTrue(" + " in lines, lines)
        onNodeWithText(lines).assertExists()
    }

    /**
     * Corridor: drawn with two map clicks; its own section (widths, lines, centre line) replaces overlap/angle and the
     * start corner, and the side overlap it gives is in the stats, following the number of lines.
     */
    @Test
    fun corridorFromThePanel() = runDesktopComposeUiTest(width = 1400, height = 1000) {
        val vm = PlanViewModel(vehicle, FakeMissions(), MissionSync(), PlanSettingsRepository(MemoryStore()), FakeFiles())
        setContent { KftTheme(ThemeMode.DARK) { PlanRoute(vm) } }
        onNodeWithContentDescription("Add corridor scan").performClick()
        vm.onMapClick(area[0])
        vm.onMapClick(area[1])
        waitForIdle()
        onNodeWithText("2 points").assertExists()
        onNodeWithText("Start corner").assertDoesNotExist()
        onNodeWithText("Grid angle").assertDoesNotExist()
        val overlapBefore = vm.state.value.survey!!.stats.toMap().getValue("Side overlap")

        onNode(hasText("Lines") and hasSetTextAction()).performScrollTo().performTextReplacement("5") // the field, not the stats label
        onNodeWithText("A line on the centre line itself").performScrollTo().performClick()
        waitForIdle()
        assertEquals(5 to true, vm.survey().corridorLines to vm.survey().includeCentreLine)
        val overlapAfter = vm.state.value.survey!!.stats.toMap().getValue("Side overlap")
        assertNotEquals(overlapBefore, overlapAfter, "more lines, more side overlap")
        onNodeWithText(overlapAfter).assertExists()
    }

    /** Two edits, two undos back to the start, one redo forward: the fields follow the plan each time. */
    @Test
    fun undoAndRedoFromTheToolbar() = runDesktopComposeUiTest(width = 1400, height = 1000) {
        val vm = planWithASurvey()
        onNodeWithText("Altitude above home").performScrollTo().performTextReplacement("80")
        onNodeWithText("Front overlap").performScrollTo().performTextReplacement("70")
        waitForIdle()

        onNodeWithContentDescription("Undo (Ctrl+Z)").performClick()
        waitForIdle()
        assertEquals(80.0 to 80.0, vm.survey().altitudeM to vm.survey().frontOverlapPct, "front overlap back to its default 80 %")
        onNodeWithContentDescription("Undo (Ctrl+Z)").performClick()
        onNodeWithText("Altitude above home").assert(hasText("50")) // the copter default
        onNodeWithContentDescription("Redo (Ctrl+Shift+Z)").performClick()
        onNodeWithText("Altitude above home").assert(hasText("80"))
        assertEquals(80.0, vm.survey().altitudeM)
    }
}
