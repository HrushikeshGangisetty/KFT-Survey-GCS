package com.kft.gcs.feature.settings

import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.kft.gcs.ui.design.KftTheme
import com.kft.gcs.ui.design.ThemeMode
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The signing-keys screen and unlock prompt as an operator meets them. Headless, part of `./gradlew check`. Each test also
 * writes its picture to `build/reports/34-*.png`; the ones worth keeping are copied to `docs/design`.
 */
@OptIn(ExperimentalTestApi::class)
class SigningKeysUiTest {
    private val keys = listOf(
        KeyRow("000102030405060708090a0b", "0001…0a0b", "Test aircraft", "v1", "Last used 2026-10-24 14:03 UTC"),
        KeyRow("ff80017f00a55a1032547698", "ff80…7698", "Survey copter 2", "v2", "Never used"),
    )
    private val unlocked = SigningKeysUiState(phase = VaultPhase.UNLOCKED, keys = keys)

    private fun androidx.compose.ui.test.ComposeUiTest.snapshot(name: String) {
        val out = File("build/reports/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out)
    }

    @Test
    fun theKeyListShowsEachDroneAndNoKeyMaterial() {
        for (mode in ThemeMode.entries) {
            runDesktopComposeUiTest(width = 760, height = 620) {
                setContent { KftTheme(mode) { Surface { SigningKeysScreen(unlocked, SigningKeysActions()) } } }
                onNodeWithText("Test aircraft").assertExists()
                onNodeWithText("0001…0a0b · v1").assertExists()
                onNodeWithText("Never used").assertExists()
                onNodeWithText("Unlocked").assertExists()
                onNodeWithContentDescription("Remove Test aircraft").assertExists()
                snapshot("34-keys-unlocked-${mode.name.lowercase()}")
            }
        }
    }

    @Test
    fun creatingNeedsThePinTypedTwiceAndPassesBothToTheViewModel() = runDesktopComposeUiTest(width = 760, height = 620) {
        var created: Pair<String, String>? = null
        setContent {
            KftTheme(ThemeMode.LIGHT) {
                Surface { SigningKeysScreen(SigningKeysUiState(), SigningKeysActions(onCreate = { pin, confirm -> created = pin to confirm })) }
            }
        }
        onNodeWithText("PIN").performTextInput("hunter22")
        onNodeWithText("Repeat the PIN").performTextInput("hunter22")
        snapshot("34-keys-create-light")
        onNodeWithText("Create vault").performClick()

        assertEquals("hunter22" to "hunter22", created)
    }

    @Test
    fun lockedShowsAPinFieldAndUnlockStaysOffUntilSomethingIsTyped() = runDesktopComposeUiTest(width = 760, height = 620) {
        var unlockedWith: String? = null
        setContent {
            KftTheme(ThemeMode.DARK) {
                Surface { SigningKeysScreen(SigningKeysUiState(phase = VaultPhase.LOCKED, error = "Wrong PIN or passphrase, or the file is damaged."), SigningKeysActions(onUnlock = { unlockedWith = it })) }
            }
        }
        onNodeWithText("Unlock").assertIsNotEnabled()
        onNodeWithText("Wrong PIN or passphrase, or the file is damaged.").assertExists()
        onNodeWithText("PIN").performTextInput("1234")
        onNodeWithText("Unlock").assertIsEnabled()
        snapshot("34-keys-locked-dark")
        onNodeWithText("Unlock").performClick()

        assertEquals("1234", unlockedWith)
    }

    @Test
    fun removeAsksFirst() = runDesktopComposeUiTest(width = 760, height = 620) {
        var removed = 0
        setContent {
            KftTheme(ThemeMode.DARK) {
                Surface { SigningKeysScreen(unlocked.copy(confirmRemove = keys[0]), SigningKeysActions(onRemoveConfirm = { removed++ })) }
            }
        }
        onNodeWithText("Remove “Test aircraft”?").assertExists()
        assertEquals(0, removed, "nothing is removed until the operator confirms")
        onNodeWithText("Remove").performClick()
        assertEquals(1, removed)
    }

    @Test
    fun importAsksForThePassphraseOfTheChosenBundle() = runDesktopComposeUiTest(width = 760, height = 620) {
        var passphrase: String? = null
        setContent {
            KftTheme(ThemeMode.DARK) {
                Surface { SigningKeysScreen(unlocked.copy(askPassphrase = true), SigningKeysActions(onPassphrase = { passphrase = it })) }
            }
        }
        onNodeWithText("Bundle passphrase").assertExists()
        onNodeWithText("Import").assertIsNotEnabled()
        onNodeWithText("Passphrase").performTextInput("acorn bamboo")
        onNodeWithText("Import").performClick()
        assertEquals("acorn bamboo", passphrase)
    }

    @Test
    fun theStartUpPromptHasUnlockAndSkip() = runDesktopComposeUiTest(width = 600, height = 400) {
        var skipped = false
        var pin: String? = null
        setContent {
            KftTheme(ThemeMode.DARK) {
                Surface { SigningUnlockDialog(SigningKeysUiState(phase = VaultPhase.LOCKED, promptUnlock = true), { pin = it }, { skipped = true }) }
            }
        }
        onNodeWithText("Unlock signing keys").assertExists()
        onNodeWithText("PIN").performTextInput("123456")
        onNodeWithText("Unlock").performClick()
        assertEquals("123456", pin)
        onNodeWithText("Skip").performClick()
        assertEquals(true, skipped)
    }
}
