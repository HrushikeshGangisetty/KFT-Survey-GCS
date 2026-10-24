package com.kft.gcs.feature.settings

import com.kft.gcs.core.keys.BundleImport
import com.kft.gcs.core.keys.IdleAutoLock
import com.kft.gcs.core.keys.ImportResult
import com.kft.gcs.core.keys.KeyInfo
import com.kft.gcs.core.keys.Vault
import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.Links
import com.kft.gcs.core.mavlink.signing.SigningKeyring
import com.kft.gcs.core.mavlink.signing.SigningStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One stored key as the screen needs it: its details plus when this device last signed with it (null = never). */
data class StoredKey(val info: KeyInfo, val lastUsedMs: Long?)

/** What the key vault looks like right now. Keys are listed only while it is unlocked. */
data class VaultSnapshot(val exists: Boolean, val unlocked: Boolean, val keys: List<StoredKey>)

/**
 * The signing-key vault as the keys screen sees it. An interface so the ViewModel is tested against a fake, with no
 * Argon2 and no disk. The operations throw `core:keys`'s `VaultException`; the ViewModel turns those into text.
 */
interface SigningKeysRepository {
    val snapshot: StateFlow<VaultSnapshot>

    /** Why the gateway refused to send to a signing vehicle (no key, or locked): one text per refused message. */
    val refusals: Flow<String>

    suspend fun create(pin: String)
    suspend fun unlock(pin: String)
    fun lock()
    suspend fun remove(droneId: String)
    suspend fun importBundle(bundle: ByteArray, passphrase: String): ImportResult

    /** The operator did something: restart the idle-lock timer. */
    fun touch()

    /** True while a vehicle is connected and signed: locking now would zero its key and the FC would drop our commands. */
    val signedVehicleConnected: Boolean
}

/**
 * The real repository over the [Vault]. Also runs the idle auto-lock (PRD FR-9): the vault locks after 5 minutes
 * without operator input, **unless** a vehicle is connected and signed, because zeroing the keys then would make the
 * flight controller drop every command we send.
 */
class VaultSigningKeysRepository(
    private val vault: Vault,
    private val importer: BundleImport,
    private val keyring: SigningKeyring,
    private val links: Links,
    scope: CoroutineScope,
    nowMs: () -> Long,
) : SigningKeysRepository {

    private fun read() = VaultSnapshot(
        exists = vault.exists(),
        unlocked = vault.isUnlocked(),
        keys = vault.list().map { StoredKey(it, keyring.loadTimestamp(it.droneId)?.let(::ticksToUnixMs)) },
    )

    override val snapshot: StateFlow<VaultSnapshot> = vault.revision.map { read() }.stateIn(scope, SharingStarted.Eagerly, read())

    override val refusals: Flow<String> = links.signingRefusals.map { "Not sent (${it.message}): ${it.reason}" }

    override val signedVehicleConnected: Boolean get() = links.hasSignedVehicle()

    private val idle = IdleAutoLock(vault, nowMs, busy = { signedVehicleConnected })

    init {
        scope.launch { idle.run() }
    }

    override suspend fun create(pin: String) = vault.create(pin)
    override suspend fun unlock(pin: String) = vault.unlock(pin)
    override fun lock() = vault.lock()
    override suspend fun remove(droneId: String) = vault.remove(droneId)
    override suspend fun importBundle(bundle: ByteArray, passphrase: String) = importer.import(bundle, passphrase)
    override fun touch() = idle.touch()
}

private fun Links.hasSignedVehicle() = open.value.any { link ->
    (link.state.value as? LinkState.Connected)?.vehicles.orEmpty().any {
        it.signing is SigningStatus.Signed || it.signing is SigningStatus.BadSignature
    }
}

/** Signing timestamps count 10 us ticks from 2015-01-01 00:00:00 UTC (PRD "Timestamps"); this is Unix milliseconds. */
internal fun ticksToUnixMs(ticks: Long): Long = ticks / 100 + 1_420_070_400_000L
