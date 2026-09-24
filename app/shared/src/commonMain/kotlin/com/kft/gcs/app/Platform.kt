package com.kft.gcs.app

/**
 * What the shared code knows about the platform it runs on.
 *
 * `expect` declares the API in commonMain; each platform source set (androidMain, jvmMain)
 * provides an `actual` implementation. The compiler checks every target has one.
 */
expect object Platform {
    /** Human-readable platform name, e.g. "Android 15 (API 35)" or "Windows 11 · JVM 25". */
    val name: String
}
