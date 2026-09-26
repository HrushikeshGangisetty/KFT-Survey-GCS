package com.kft.gcs.app

import com.kft.gcs.feature.connections.ProfileStore
import com.kft.gcs.feature.plan.SettingsStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One UTF-8 text file: the saved connection profiles, or the plan settings (one instance per file). Each shell picks
 * the folder: `%APPDATA%\KFT-GCS` on desktop, the app's private files directory on Android. Both store interfaces
 * are "read the text, write the text", so one class serves both.
 */
class FileTextStore(private val file: File) : ProfileStore, SettingsStore {

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
