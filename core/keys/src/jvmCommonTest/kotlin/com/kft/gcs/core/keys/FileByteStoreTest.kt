package com.kft.gcs.core.keys

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FileByteStoreTest {
    private val dir = Files.createTempDirectory("filebytestore").toFile()

    @Test
    fun writesCreateFoldersAndReadBackExactly() {
        val store = FileByteStore(File(dir, "a/b/signing.vnks"))
        assertNull(store.read())
        val bytes = ByteArray(300) { it.toByte() }

        store.write(bytes)

        assertContentEquals(bytes, store.read())
    }

    @Test
    fun aCrashBeforeTheRenameKeepsTheOldBytesAndTheNextSaveRecovers() {
        val file = File(dir, "signing.vnks")
        FileByteStore(file).write(byteArrayOf(1, 2, 3))

        assertFailsWith<IOException> { FileByteStore(file) { _, _ -> throw IOException("power cut") }.write(byteArrayOf(9, 9, 9, 9)) }
        assertContentEquals(byteArrayOf(1, 2, 3), FileByteStore(file).read())

        FileByteStore(file).write(byteArrayOf(4, 5)) // the stale .tmp is simply overwritten
        assertContentEquals(byteArrayOf(4, 5), FileByteStore(file).read())
    }

    @Test
    fun aWrappedStoreHoldsOnlyWrappedBytesOnDisk() {
        val plain = ByteArray(40) { 7 }
        val xor = object : DeviceWrap {
            override fun wrap(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
            override fun unwrap(wrapped: ByteArray) = wrap(wrapped)
        }
        val inner = MemoryStore()

        val store = WrappedByteStore(inner, xor)
        store.write(plain)

        assertContentEquals(xor.wrap(plain), inner.bytes)
        assertContentEquals(plain, store.read())
    }
}
