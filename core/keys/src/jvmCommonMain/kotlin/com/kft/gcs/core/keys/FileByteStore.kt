package com.kft.gcs.core.keys

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A [ByteStore] in one file, saved the same way as the app's `FileTextStore`: write a temporary file next to it, flush it
 * to disk, then rename it over the real one. A rename within one folder replaces the file in one step, so a crash leaves
 * either the old vault or the new one, never half of one. A leftover `.tmp` from a crash is never read, only overwritten.
 *
 * [replace] is the rename step, injectable so a test can make it fail exactly where a power cut would.
 */
class FileByteStore(
    private val file: File,
    private val replace: (tmp: File, target: File) -> Unit = ::atomicReplace,
) : ByteStore {

    override fun read(): ByteArray? = file.takeIf { it.exists() }?.readBytes()

    override fun write(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use {
            it.write(bytes)
            it.fd.sync() // the data must be on disk before the rename makes it the vault
        }
        replace(tmp, file)
    }
}

private fun atomicReplace(tmp: File, target: File) {
    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}
