package com.kft.gcs.ui.map

import java.io.File

/** Desktop reads ESRI_API_KEY from the environment. `gradlew run` sets it from local.properties (`esri.apiKey=`). */
actual fun esriApiKey(): String? = System.getenv("ESRI_API_KEY")?.takeIf { it.isNotBlank() }

// A file URI's path is "/C:/dir/my%20file.mbtiles" on Windows; MapLibre wants the drive letter first.
internal actual fun mbtilesUrl(path: String): String = "mbtiles://" + File(path).toURI().rawPath.replace(Regex("^/(?=[A-Za-z]:)"), "")
