package com.kft.gcs.feature.settings

import androidx.compose.runtime.Immutable

enum class VaultPhase { NO_VAULT, LOCKED, UNLOCKED }

/** One key in the list. Deliberately has no key bytes: the screen cannot show what it is never given. */
data class KeyRow(
    val droneId: String,
    /** "0001…0a0b": enough to recognise a drone, short enough for a phone. */
    val shortId: String,
    val label: String,
    val version: String,
    val lastUsed: String,
)

/** Everything the signing-keys screen and the start-up unlock prompt draw. */
@Immutable
data class SigningKeysUiState(
    val phase: VaultPhase = VaultPhase.NO_VAULT,
    val keys: List<KeyRow> = emptyList(),
    /** Argon2 takes a moment (64 MiB, deliberately slow): buttons are off and a spinner shows meanwhile. */
    val busy: Boolean = false,
    /** Why the last create / unlock / import failed. Cleared by the next action. */
    val error: String? = null,
    /** The start-up prompt: the vault exists, is locked, and the operator has not chosen Skip. */
    val promptUnlock: Boolean = false,
    /** A bundle was picked and is waiting for its one-time passphrase. */
    val askPassphrase: Boolean = false,
    /** The key the operator is about to remove (asks first). */
    val confirmRemove: KeyRow? = null,
)

sealed interface SigningKeysEffect {
    data class ShowMessage(val text: String) : SigningKeysEffect

    /** A signing vehicle could not be sent to: the app shows [text] with an "Open signing keys" action. */
    data class Refused(val text: String) : SigningKeysEffect
}

/** Shortest PIN accepted. Argon2 slows guessing, but a 4-digit PIN still only has 10 000 values. */
const val MIN_PIN_LENGTH = 6
