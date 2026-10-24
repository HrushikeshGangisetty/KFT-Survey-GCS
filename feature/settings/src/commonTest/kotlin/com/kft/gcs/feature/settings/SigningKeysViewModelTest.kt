package com.kft.gcs.feature.settings

import app.cash.turbine.test
import com.kft.gcs.core.keys.ImportResult
import com.kft.gcs.core.keys.KeyInfo
import com.kft.gcs.core.keys.VaultException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private const val DRONE = "000102030405060708090a0b"

private fun stored(lastUsedMs: Long? = null) = StoredKey(KeyInfo(DRONE, 2, "Test aircraft", null, "2026-10-23T00:00:00+00:00"), lastUsedMs)

/** A vault the test drives by hand: it changes [snapshot] like the real one does, or throws what the real one throws. */
private class FakeKeysRepository : SigningKeysRepository {
    override val snapshot = MutableStateFlow(VaultSnapshot(exists = false, unlocked = false, keys = emptyList()))
    val refusalFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val refusals = refusalFlow
    val calls = mutableListOf<String>()
    var failWith: Exception? = null
    var importResult = ImportResult(added = 2, skippedOlder = emptyList())
    var touches = 0
    override var signedVehicleConnected = false

    private fun maybeFail() = failWith?.let { throw it }

    override suspend fun create(pin: String) {
        calls += "create $pin"; maybeFail()
        snapshot.value = VaultSnapshot(exists = true, unlocked = true, keys = emptyList())
    }
    override suspend fun unlock(pin: String) {
        calls += "unlock $pin"; maybeFail()
        snapshot.value = snapshot.value.copy(unlocked = true, keys = listOf(stored()))
    }
    override fun lock() {
        calls += "lock"
        snapshot.value = snapshot.value.copy(unlocked = false, keys = emptyList())
    }
    override suspend fun remove(droneId: String) {
        calls += "remove $droneId"; maybeFail()
        snapshot.value = snapshot.value.copy(keys = emptyList())
    }
    override suspend fun importBundle(bundle: ByteArray, passphrase: String): ImportResult {
        calls += "import ${bundle.size} $passphrase"; maybeFail()
        return importResult
    }
    override fun touch() { touches++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SigningKeysViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeKeysRepository()
    private var picked: ByteArray? = byteArrayOf(1, 2, 3)
    private var pickerRan = 0
    private val vm by lazy { SigningKeysViewModel(repository) { pickerRan++; picked } }

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun lockedVault() { repository.snapshot.value = VaultSnapshot(exists = true, unlocked = false, keys = emptyList()) }

    @Test
    fun withNoVaultTheScreenOffersToCreateOneAndNeverPromptsToUnlock() = runTest(dispatcher) {
        vm.state.test {
            runCurrent()
            val s = expectMostRecentItem()
            assertEquals(VaultPhase.NO_VAULT, s.phase)
            assertFalse(s.promptUnlock)
        }
    }

    @Test
    fun aShortOrMismatchedPinIsRefusedBeforeAnythingIsCreated() = runTest(dispatcher) {
        vm.state.test {
            runCurrent()
            vm.onCreateClicked("12345", "12345")
            runCurrent()
            assertEquals("Use at least $MIN_PIN_LENGTH characters.", expectMostRecentItem().error)

            vm.onCreateClicked("123456", "123457")
            runCurrent()
            assertEquals("The two PINs are different.", expectMostRecentItem().error)
        }
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun creatingTheVaultUnlocksItAndNeverPutsThePinInTheState() = runTest(dispatcher) {
        vm.state.test {
            runCurrent()
            vm.onCreateClicked("hunter22", "hunter22")
            runCurrent()
            val s = expectMostRecentItem()
            assertEquals(VaultPhase.UNLOCKED, s.phase)
            assertNull(s.error)
            assertFalse(s.busy)
            assertFalse(s.toString().contains("hunter22"), "secrets are arguments, not state")
        }
        assertEquals(listOf("create hunter22"), repository.calls)
    }

    @Test
    fun aLockedVaultPromptsAtStartUntilSkipped() = runTest(dispatcher) {
        lockedVault()
        vm.state.test {
            runCurrent()
            val s = expectMostRecentItem()
            assertEquals(VaultPhase.LOCKED, s.phase)
            assertTrue(s.promptUnlock)

            vm.onSkipUnlockClicked()
            runCurrent()
            assertFalse(expectMostRecentItem().promptUnlock, "Skip leaves it locked and quiet")
            assertEquals(VaultPhase.LOCKED, vm.state.value.phase)
        }
    }

    @Test
    fun afterAnUnlockALaterLockAsksAgain() = runTest(dispatcher) {
        lockedVault()
        vm.state.test {
            runCurrent()
            vm.onSkipUnlockClicked()
            runCurrent()
            vm.onUnlockClicked("1234")
            runCurrent()
            assertEquals(VaultPhase.UNLOCKED, expectMostRecentItem().phase)

            vm.onLockClicked() // Lock now, or the idle timer
            runCurrent()
            assertTrue(expectMostRecentItem().promptUnlock)
        }
    }

    @Test
    fun aWrongPinShowsAnErrorAndKeepsTheVaultLocked() = runTest(dispatcher) {
        lockedVault()
        repository.failWith = VaultException.WrongSecret()
        vm.state.test {
            runCurrent()
            vm.onUnlockClicked("0000")
            runCurrent()
            val s = expectMostRecentItem()
            assertEquals("Wrong PIN or passphrase, or the file is damaged.", s.error)
            assertEquals(VaultPhase.LOCKED, s.phase)
            assertFalse(s.busy)

            repository.failWith = null
            vm.onUnlockClicked("1234")
            runCurrent()
            assertNull(expectMostRecentItem().error, "the next attempt clears the old error")
        }
    }

    @Test
    fun unlockedKeysAreListedWithoutKeyBytes() = runTest(dispatcher) {
        lockedVault()
        // 1_792_850_580_000 ms since 1970 is 2026-10-24 14:03:00 UTC.
        repository.snapshot.value = VaultSnapshot(exists = true, unlocked = true, keys = listOf(stored(lastUsedMs = 1_792_850_580_000)))
        vm.state.test {
            runCurrent()
            assertEquals(
                KeyRow(DRONE, "0001…0a0b", "Test aircraft", "v2", "Last used 2026-10-24 14:03 UTC"),
                expectMostRecentItem().keys.single(),
            )
        }
        repository.snapshot.value = repository.snapshot.value.copy(keys = listOf(stored(lastUsedMs = null)))
        vm.state.test {
            runCurrent()
            assertEquals("Never used", expectMostRecentItem().keys.single().lastUsed)
        }
    }

    @Test
    fun removingAKeyAsksFirstAndCancelChangesNothing() = runTest(dispatcher) {
        repository.snapshot.value = VaultSnapshot(exists = true, unlocked = true, keys = listOf(stored()))
        vm.state.test {
            runCurrent()
            vm.onRemoveClicked(DRONE)
            runCurrent()
            assertEquals("Test aircraft", expectMostRecentItem().confirmRemove?.label)

            vm.onRemoveCancelled()
            runCurrent()
            assertNull(expectMostRecentItem().confirmRemove)
            assertTrue(repository.calls.isEmpty())

            vm.onRemoveClicked(DRONE)
            vm.onRemoveConfirmed()
            runCurrent()
            assertTrue(expectMostRecentItem().keys.isEmpty())
        }
        assertEquals(listOf("remove $DRONE"), repository.calls)
    }

    @Test
    fun importPicksTheFileThenWaitsForItsPassphrase() = runTest(dispatcher) {
        repository.snapshot.value = VaultSnapshot(exists = true, unlocked = true, keys = emptyList())
        vm.effects.test {
            vm.state.test {
                runCurrent()
                vm.onImportClicked()
                runCurrent()
                assertTrue(expectMostRecentItem().askPassphrase)
                assertTrue(repository.calls.isEmpty(), "nothing is decrypted before the passphrase")

                vm.onPassphraseSubmitted("acorn bamboo cactus delta ember forest")
                runCurrent()
                assertFalse(expectMostRecentItem().askPassphrase)
            }
            assertEquals(SigningKeysEffect.ShowMessage("Imported 2 keys."), awaitItem())
        }
        assertEquals(listOf("import 3 acorn bamboo cactus delta ember forest"), repository.calls)
    }

    @Test
    fun cancellingTheFileDialogOrThePassphraseImportsNothing() = runTest(dispatcher) {
        repository.snapshot.value = VaultSnapshot(exists = true, unlocked = true, keys = emptyList())
        vm.state.test {
            runCurrent()
            picked = null
            vm.onImportClicked()
            runCurrent()
            assertFalse(vm.state.value.askPassphrase)

            picked = byteArrayOf(9)
            vm.onImportClicked()
            runCurrent()
            vm.onPassphraseCancelled()
            runCurrent()
            assertFalse(expectMostRecentItem().askPassphrase)
        }
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun aWrongPassphraseKeepsTheBundleSoTheOperatorCanRetype() = runTest(dispatcher) {
        repository.snapshot.value = VaultSnapshot(exists = true, unlocked = true, keys = emptyList())
        vm.state.test {
            runCurrent()
            vm.onImportClicked()
            runCurrent()
            repository.failWith = VaultException.WrongSecret()
            vm.onPassphraseSubmitted("wrong")
            runCurrent()
            val failed = expectMostRecentItem()
            assertTrue(failed.askPassphrase, "the dialog stays for another try")
            assertEquals("Wrong PIN or passphrase, or the file is damaged.", failed.error)
        }
    }

    @Test
    fun skippedOlderKeysAreMentionedInTheMessage() = runTest(dispatcher) {
        repository.snapshot.value = VaultSnapshot(exists = true, unlocked = true, keys = emptyList())
        repository.importResult = ImportResult(added = 1, skippedOlder = listOf(DRONE))
        vm.effects.test {
            vm.onImportClicked()
            runCurrent()
            vm.onPassphraseSubmitted("words")
            runCurrent()
            assertEquals(
                SigningKeysEffect.ShowMessage("Imported 1 key. Skipped 1 older than the key already stored."),
                awaitItem(),
            )
        }
    }

    @Test
    fun aRefusedSendBecomesAToastWithAtMostOneEveryThirtySeconds() = runTest(dispatcher) {
        vm.effects.test {
            runCurrent()
            repository.refusalFlow.emit("first")
            runCurrent()
            assertEquals(SigningKeysEffect.Refused("first"), awaitItem())

            // The gateway refuses every second while the vehicle has no key: the rest are held back, the latest kept.
            repository.refusalFlow.emit("second")
            repository.refusalFlow.emit("third")
            advanceTimeBy(29_000)
            expectNoEvents()
            advanceTimeBy(1_500)
            assertEquals(SigningKeysEffect.Refused("third"), awaitItem())
        }
    }

    @Test
    fun backgroundingLocksExceptWhileTheFileDialogIsUp() = runTest(dispatcher) {
        repository.snapshot.value = VaultSnapshot(exists = true, unlocked = true, keys = emptyList())
        picked = null
        var duringPick: (() -> Unit)? = null
        val vm = SigningKeysViewModel(repository) { duringPick?.invoke(); picked }
        runCurrent()

        duringPick = { vm.onAppBackgrounded() } // Android stops the activity while the system picker is on top
        vm.onImportClicked()
        runCurrent()
        assertTrue(repository.snapshot.value.unlocked, "locking now would break the import")

        vm.onAppBackgrounded()
        assertFalse(repository.snapshot.value.unlocked)
    }

    @Test
    fun backgroundingNeverLocksWhileASignedVehicleIsConnected() = runTest(dispatcher) {
        repository.snapshot.value = VaultSnapshot(exists = true, unlocked = true, keys = emptyList())
        repository.signedVehicleConnected = true
        vm.onAppBackgrounded()
        assertTrue(repository.snapshot.value.unlocked)

        repository.signedVehicleConnected = false
        vm.onAppBackgrounded()
        assertFalse(repository.snapshot.value.unlocked)
    }

    @Test
    fun operatorInputRestartsTheIdleTimer() = runTest(dispatcher) {
        vm.onUserActive()
        vm.onUserActive()
        assertEquals(2, repository.touches)
        assertEquals(0, pickerRan)
    }
}
