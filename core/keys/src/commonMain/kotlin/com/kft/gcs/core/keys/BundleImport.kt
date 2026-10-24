package com.kft.gcs.core.keys

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

/** What an import did: how many keys went in, and which drone ids were skipped because the vault already held a newer key. */
data class ImportResult(val added: Int, val skippedOlder: List<String>)

/**
 * Adds the keys from a `.vnkey` bundle made by `tools/signing/provision.py export-gcs` to the [vault].
 *
 * A bundle uses the vault's file layout with magic `VNKB`, encrypted under the one-time passphrase the operator was read
 * out, so the same [Envelope] code opens it. Its body is a plain JSON list of entries. The vault must already be unlocked:
 * the keys go straight into it and the bundle is not kept.
 */
class BundleImport(
    private val vault: Vault,
    private val crypto: VaultCrypto,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Throws [VaultException.WrongSecret] for a wrong passphrase or damaged bundle, [VaultException.Locked] if the vault is locked. */
    suspend fun import(bundle: ByteArray, passphrase: String): ImportResult {
        if (!vault.isUnlocked()) throw VaultException.Locked()
        val entries = withContext(dispatcher) { decrypt(bundle, passphrase.trim()) }
        try {
            val skipped = vault.addAll(entries)
            return ImportResult(entries.size - skipped.size, skipped)
        } finally {
            entries.forEach { it.key.fill(0) }
        }
    }

    private fun decrypt(bundle: ByteArray, passphrase: String): List<VaultEntry> {
        val secret = passphrase.encodeToByteArray()
        val opened = try {
            Envelope.open("VNKB", bundle, secret, crypto)
        } finally {
            secret.fill(0)
        }
        opened.key.fill(0) // a bundle is read once; only the vault keeps its AES key
        return try {
            vaultJson.decodeFromString(ListSerializer(EntryJson.serializer()), opened.plaintext.decodeToString()).map { it.toEntry() }
        } catch (e: VaultException) {
            throw e
        } catch (e: Exception) {
            throw VaultException.Corrupt("bad bundle JSON")
        } finally {
            opened.plaintext.fill(0)
        }
    }
}
