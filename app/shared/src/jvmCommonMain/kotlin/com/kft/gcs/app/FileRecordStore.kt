package com.kft.gcs.app

import com.kft.gcs.core.vehicle.RecordStore
import java.io.File
import java.io.FileOutputStream

/**
 * Mission progress records as files in [dir] (Pass 31). [append] opens, writes, forces the bytes to the disk and
 * closes, once per event (a photo, an item reached): a few dozen bytes about once a second, and the reason the
 * record is still there after the app is killed.
 */
class FileRecordStore(private val dir: File) : RecordStore {
    override fun names(): List<String> = dir.list()?.toList() ?: emptyList()

    override fun read(name: String): String? = File(dir, name).takeIf { it.isFile }?.readText()

    override fun write(name: String, text: String) {
        dir.mkdirs()
        File(dir, name).writeText(text)
    }

    override fun append(name: String, text: String) {
        dir.mkdirs()
        FileOutputStream(File(dir, name), true).use { out ->
            out.write(text.encodeToByteArray())
            out.fd.sync()
        }
    }
}
