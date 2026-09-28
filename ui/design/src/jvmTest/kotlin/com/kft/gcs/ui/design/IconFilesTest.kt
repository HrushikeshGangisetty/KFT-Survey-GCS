package com.kft.gcs.ui.design

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every icon file is well-formed XML. A broken one only fails when it's first drawn (Pass 27 lost a `>` while
 * dropping the Android tint, and the desktop app showed an error dialog on opening the Video dialog), so check them
 * all here.
 */
class IconFilesTest {
    @Test
    fun everyIconParses() {
        val icons = File("src/commonMain/composeResources/drawable").listFiles { f -> f.name.endsWith(".xml") }.orEmpty()
        assertTrue(icons.size > 40, "icons found: ${icons.size}")
        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        for (icon in icons) {
            val root = runCatching { parser.parse(icon).documentElement }.getOrElse { throw AssertionError("${icon.name}: ${it.message}") }
            assertTrue(root.getAttribute("android:tint").isEmpty(), "${icon.name}: the Android-only tint must be removed")
        }
    }
}
