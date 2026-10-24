package com.kft.gcs.core.keys

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Proves our crypto and file layout agree with an independent implementation. `bundle_v1.json` is the S6 vector made by
 * GNSS-Denied's `tools/signing/make_bundle_vector.py` (python `cryptography` + `argon2-cffi`, commit 8ff868c); the passphrase
 * and keys in it are public test values.
 */
class BundleInteropTest {
    // Gradle runs the desktop and Android host tests with the module directory as the working directory.
    private val vector: JsonObject =
        Json.parseToJsonElement(File("src/commonTest/resources/signing/bundle_v1.json").readText()).jsonObject
    private val passphrase = vector.str("passphrase")
    private val bundle = vector.str("bundle_hex").hex()

    private fun JsonObject.str(name: String) = getValue(name).jsonPrimitive.content

    @Test
    fun argon2idMatchesRfc9106Section512() {
        // RFC 9106 section 5.3 "Argon2id Test Vectors": password 32x01, salt 16x02, secret 8x03, data 12x04, t=3, m=32 KiB, p=4.
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(ByteArray(16) { 2 }).withSecret(ByteArray(8) { 3 }).withAdditional(ByteArray(12) { 4 })
            .withMemoryAsKB(32).withIterations(3).withParallelism(4)
            .build()
        val tag = ByteArray(32).also { Argon2BytesGenerator().apply { init(params) }.generateBytes(ByteArray(32) { 1 }, it) }

        assertContentEquals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659".hex(), tag)
    }

    @Test
    fun ourSealingReproducesThePythonBundleByteForByte() {
        val salt = vector.str("salt_hex").hex()
        val key = testCrypto.argon2id(passphrase.encodeToByteArray(), salt, KdfParams.V1)

        val ours = Envelope.seal("VNKB", key, salt, vector.str("plaintext_utf8").encodeToByteArray(), testCrypto, vector.str("nonce_hex").hex())

        assertContentEquals(vector.str("header_hex").hex(), Envelope.header("VNKB", salt), "33-byte header layout")
        assertContentEquals(bundle, ours, "header + nonce + AES-GCM ciphertext and tag")
    }

    @Test
    fun theBundleImportsBothDronesWithTheirKeysAndLabels() = runTest {
        val vault = createdVault()

        val result = BundleImport(vault, testCrypto).import(bundle, "  $passphrase \n")

        assertEquals(ImportResult(added = 2, skippedOlder = emptyList()), result)
        val expected = vector.getValue("expected_entries").jsonArray.map { it.jsonObject }
        assertEquals(expected.map { it.str("drone_id") }, vault.list().map { it.droneId })
        for (e in expected) {
            val key = vault.keyFor(e.str("drone_id"))!!
            assertContentEquals(java.util.Base64.getDecoder().decode(e.str("key_b64")), key)
        }
        assertEquals("Test aircraft α", vault.list()[0].label, "non-ASCII labels survive")
        assertEquals(2, vault.list()[1].keyVersion)
        assertEquals("2026-10-23T00:00:00+00:00", vault.list()[0].addedAt)
    }

    @Test
    fun importingTheSameBundleTwiceChangesNothing() = runTest {
        val vault = createdVault()
        val importer = BundleImport(vault, testCrypto)
        importer.import(bundle, passphrase)
        importer.import(bundle, passphrase)

        assertEquals(2, vault.list().size)
    }

    @Test
    fun aBundleOlderThanTheStoredKeyIsSkipped() = runTest {
        val vault = createdVault()
        val second = vector.getValue("expected_entries").jsonArray[1].jsonObject.str("drone_id") // bundle has key_version 2
        vault.add(VaultEntry(KeyInfo(second, 5, "rekeyed", null, "2026-10-24T00:00:00+00:00"), ByteArray(32) { 9 }))

        val result = BundleImport(vault, testCrypto).import(bundle, passphrase)

        assertEquals(ImportResult(added = 1, skippedOlder = listOf(second)), result)
        assertEquals(9.toByte(), vault.keyFor(second)!![0])
    }

    @Test
    fun aWrongPassphraseOrDamagedBundleAddsNothing() = runTest {
        val store = MemoryStore()
        val vault = createdVault(store)
        val importer = BundleImport(vault, testCrypto)
        val writes = store.writes

        assertFailsWith<VaultException.WrongSecret> { importer.import(bundle, "acorn bamboo cactus delta ember garden") }
        assertFailsWith<VaultException.WrongSecret> {
            importer.import(bundle.copyOf().also { it[bundle.lastIndex] = (it[bundle.lastIndex].toInt() xor 1).toByte() }, passphrase)
        }
        assertFailsWith<VaultException.Corrupt> { importer.import(bundle.copyOf(30), passphrase) }
        assertTrue(vault.list().isEmpty())
        assertEquals(writes, store.writes)
    }

    @Test
    fun aVaultFileCannotBePassedOffAsABundle() = runTest {
        val store = MemoryStore()
        createdVault(store).also { it.add(entry('a')) }
        val other = createdVault()

        assertFailsWith<VaultException.Corrupt> { BundleImport(other, testCrypto).import(store.bytes!!, "1234") }
    }

    @Test
    fun importNeedsAnUnlockedVault() = runTest {
        val vault = createdVault().also { it.lock() }

        assertFailsWith<VaultException.Locked> { BundleImport(vault, testCrypto).import(bundle, passphrase) }
    }
}
