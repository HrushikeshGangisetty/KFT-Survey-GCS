package com.kft.gcs.app

actual object Platform {
    actual val name: String =
        "${System.getProperty("os.name")} · JVM ${System.getProperty("java.version")}"
}
