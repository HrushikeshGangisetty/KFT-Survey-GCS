package com.kft.gcs.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kft.gcs.core.keys.VaultException
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The platform's "Open..." dialog for a `.vnkey` bundle: returns the file's bytes, or null if the operator cancels.
 * The bundle is encrypted, so the bytes can pass through memory safely; nothing is kept.
 */
fun interface BundlePicker {
    suspend fun pick(): ByteArray?
}

/**
 * The signing-keys screen and the unlock prompt (PRD FR-9). Created once for the window so the prompt shows at start
 * whichever tab is open, and so a refused send raises a toast on any tab.
 *
 * Secrets: the PIN and passphrase arrive as arguments and are handed straight to the repository; they are never put in
 * [state]. A picked bundle waits here, encrypted, until its passphrase is entered.
 */
class SigningKeysViewModel(
    private val repository: SigningKeysRepository,
    private val picker: BundlePicker,
) : ViewModel() {

    /** What only this ViewModel knows. [bundle] is the encrypted file waiting for its passphrase (never compared, only held). */
    private data class Local(
        val busy: Boolean = false,
        val error: String? = null,
        val skippedPrompt: Boolean = false,
        val bundle: ByteArray? = null,
        val removeId: String? = null,
    )

    private val local = MutableStateFlow(Local())

    private val _effects = Channel<SigningKeysEffect>(Channel.BUFFERED)
    val effects: Flow<SigningKeysEffect> = _effects.receiveAsFlow()

    val state: StateFlow<SigningKeysUiState> = combine(repository.snapshot, local, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), build(repository.snapshot.value, local.value))

    init {
        viewModelScope.launch {
            // After any unlock, a later lock (idle timer, Lock now) asks again; "Skip" only silences the start-up prompt.
            repository.snapshot.collect { if (it.unlocked) local.update { l -> l.copy(skippedPrompt = false) } }
        }
        viewModelScope.launch {
            // Refused sends repeat every second while a signing vehicle has no key; one toast per cooldown is enough.
            repository.refusals.conflate().collect {
                _effects.trySend(SigningKeysEffect.Refused(it))
                delay(REFUSAL_COOLDOWN_MS)
            }
        }
    }

    /** Any screen input: keeps the idle-lock timer from running out while the operator is working. */
    fun onUserActive() = repository.touch()

    fun onCreateClicked(pin: String, confirm: String) {
        val problem = when {
            pin.length < MIN_PIN_LENGTH -> "Use at least $MIN_PIN_LENGTH characters."
            pin != confirm -> "The two PINs are different."
            else -> null
        }
        if (problem != null) return fail(problem)
        run { repository.create(pin) }
    }

    fun onUnlockClicked(pin: String) = run { repository.unlock(pin) }

    fun onSkipUnlockClicked() = local.update { it.copy(skippedPrompt = true) }

    fun onLockClicked() = repository.lock()

    /**
     * The app left the screen (Android). Locks the keys, except while the file dialog is up: on Android that dialog is
     * another screen, and locking then would make the import fail the moment the operator picks the file.
     */
    fun onAppBackgrounded() {
        if (!picking) repository.lock()
    }

    private var picking = false

    fun onImportClicked() {
        viewModelScope.launch {
            picking = true
            val bundle = try {
                picker.pick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch fail("Could not read the file: ${e.message}")
            } finally {
                picking = false
            }
            if (bundle != null) local.update { it.copy(bundle = bundle, error = null) }
        }
    }

    fun onPassphraseCancelled() = local.update { it.copy(bundle = null) }

    fun onPassphraseSubmitted(passphrase: String) {
        val bundle = local.value.bundle ?: return
        run {
            val result = repository.importBundle(bundle, passphrase)
            local.update { it.copy(bundle = null) }
            val skipped = if (result.skippedOlder.isEmpty()) "" else " Skipped ${result.skippedOlder.size} older than the key already stored."
            _effects.trySend(SigningKeysEffect.ShowMessage("Imported ${result.added} key${if (result.added == 1) "" else "s"}.$skipped"))
        }
    }

    fun onRemoveClicked(droneId: String) = local.update { it.copy(removeId = droneId) }

    fun onRemoveCancelled() = local.update { it.copy(removeId = null) }

    fun onRemoveConfirmed() {
        val id = local.value.removeId ?: return
        local.update { it.copy(removeId = null) }
        run { repository.remove(id) }
    }

    private fun fail(text: String) = local.update { it.copy(error = text, busy = false) }

    /** Runs one vault operation with the busy flag on, and turns its failures into the error text. */
    private fun run(block: suspend () -> Unit) {
        local.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
                local.update { it.copy(busy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VaultException) {
                fail(
                    when (e) {
                        is VaultException.WrongSecret -> "Wrong PIN or passphrase, or the file is damaged."
                        is VaultException.Locked -> "Unlock the vault first."
                        else -> e.message ?: "Couldn't open the key file."
                    },
                )
            } catch (e: Exception) {
                fail("Failed: ${e.message}")
            }
        }
    }

    private fun build(snapshot: VaultSnapshot, l: Local): SigningKeysUiState {
        val phase = when {
            !snapshot.exists -> VaultPhase.NO_VAULT
            snapshot.unlocked -> VaultPhase.UNLOCKED
            else -> VaultPhase.LOCKED
        }
        val rows = snapshot.keys.map(::keyRow)
        return SigningKeysUiState(
            phase = phase,
            keys = rows,
            busy = l.busy,
            error = l.error,
            promptUnlock = phase == VaultPhase.LOCKED && !l.skippedPrompt,
            askPassphrase = l.bundle != null && phase == VaultPhase.UNLOCKED,
            confirmRemove = rows.find { it.droneId == l.removeId },
        )
    }

    private companion object {
        const val REFUSAL_COOLDOWN_MS = 30_000L
    }
}

internal fun keyRow(key: StoredKey) = KeyRow(
    droneId = key.info.droneId,
    shortId = key.info.droneId.take(4) + "…" + key.info.droneId.takeLast(4),
    label = key.info.label,
    version = "v${key.info.keyVersion}",
    lastUsed = key.lastUsedMs?.let { "Last used ${lastUsedText(it)}" } ?: "Never used",
)

/** "2026-10-24 14:03 UTC": UTC on purpose, so the same moment reads the same on every device and in the tlogs. */
internal fun lastUsedText(unixMs: Long): String = Instant.fromEpochMilliseconds(unixMs).toString().take(16).replace('T', ' ') + " UTC"
