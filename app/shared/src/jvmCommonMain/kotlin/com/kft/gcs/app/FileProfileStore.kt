package com.kft.gcs.app

import com.kft.gcs.feature.connections.ProfileStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Saved connection profiles in one UTF-8 file. Each shell picks the folder: `%APPDATA%\KFT-GCS` on desktop, the
 * app's private files directory on Android.
 */
class FileProfileStore(private val file: File) : ProfileStore {

    override fun read(): String? = file.takeIf { it.exists() }?.readText()

    /**
     * Writes a temporary file next to the real one, then renames it over the top. A rename within one folder
     * replaces the file in one step, so a crash mid-save leaves either the old file or the new one, never half of one.
     */
    override fun write(text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
