package com.kft.gcs.app

import java.io.File
import java.io.InputStream

/**
 * Copies [input] into [dir] under [name], or "name (2).ext" and so on if that's taken. Never over an existing file:
 * on Windows a map file the map has open can't be replaced, and a silent overwrite would lose the older one.
 * Returns the new file's path.
 */
internal fun copyInto(dir: File, name: String, input: InputStream): String {
    dir.mkdirs()
    val base = name.substringBeforeLast('.')
    val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    val target = generateSequence(1) { it + 1 }.map { n -> File(dir, if (n == 1) name else "$base ($n)$ext") }.first { !it.exists() }
    target.outputStream().use { input.copyTo(it) }
    return target.path
}
