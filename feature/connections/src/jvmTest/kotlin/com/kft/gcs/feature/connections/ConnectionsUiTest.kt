package com.kft.gcs.feature.connections

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.ui.design.KftTheme
import com.kft.gcs.ui.design.ThemeMode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * The Links screen driven like an operator would: real screen, real ViewModel, fake repository. Headless (the
 * desktop test renders off screen), part of `./gradlew check`.
 */
@OptIn(ExperimentalTestApi::class)
class ConnectionsUiTest {
    // viewModelScope runs on Dispatchers.Main, which a test JVM doesn't have. Unconfined runs each event at once.
    // (A paused test dispatcher can't stand in: the desktop test harness then never goes idle.)
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /**
     * New TCP profile: name, host typed one key at a time, port replaced, Save. The profile is listed with its
     * summary, the repository got the exact config, and the form is empty again.
     *
     * While the host is typed, the screen is held on the state from before the first key: the ViewModel's state is
     * behind the typing, as on a slow frame in SITL. A field that shows the ViewModel's text (the old form) ends with
     * "1", the last key only; checked with the old field put back. The fields keep their own text now.
     */
    @Test
    fun createAndSaveAConnectionProfile() = runDesktopComposeUiTest(width = 1280, height = 900) {
        val repository = FakeConnectionsRepository()
        val vm = ConnectionsViewModel(repository)
        var frozen by mutableStateOf<ConnectionsUiState?>(null)
        setContent {
            val live by vm.state.collectAsState()
            KftTheme(ThemeMode.DARK) {
                ConnectionsScreen(
                    frozen ?: live, vm::onConnectClicked, vm::onDisconnectClicked, vm::onDeleteClicked, vm::onFormKindChanged,
                    vm::onFormNameChanged, vm::onFormHostChanged, vm::onFormPortChanged, vm::onSaveProfileClicked,
                    vm::onFormSerialPortChanged, vm::onFormBaudChanged, vm::onRefreshSerialPortsClicked,
                )
            }
        }

        onNodeWithText("TCP client").performClick()
        onNodeWithText("Name (optional)").performTextInput("Bench radio")
        frozen = vm.state.value
        "192.168.4.1".forEach { onNodeWithText("Host").performTextInput(it.toString()) }
        frozen = null
        onNodeWithText("Port").performTextReplacement("5762")
        onNodeWithText("Save profile").performClick()

        assertEquals(LinkConfig.TcpClient("192.168.4.1", 5762), repository.profiles.value.last().config)
        onNodeWithText("Bench radio").assertExists()
        onNodeWithText("TCP 192.168.4.1:5762").assertExists()
        onNodeWithText("Name (optional)").assert(editableText(""))
    }

    private fun editableText(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text))
}
