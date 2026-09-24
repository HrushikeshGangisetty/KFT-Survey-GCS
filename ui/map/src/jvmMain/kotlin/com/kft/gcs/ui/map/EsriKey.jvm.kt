package com.kft.gcs.ui.map

/** Desktop reads ESRI_API_KEY from the environment. `gradlew run` sets it from local.properties (`esri.apiKey=`). */
actual fun esriApiKey(): String? = System.getenv("ESRI_API_KEY")?.takeIf { it.isNotBlank() }
