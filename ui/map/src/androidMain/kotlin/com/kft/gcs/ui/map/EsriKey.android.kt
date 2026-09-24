package com.kft.gcs.ui.map

// ponytail: no key on Android until GS-1 decides how keys reach an APK (per-user key vs KFT proxy). ADR-001 F7.
actual fun esriApiKey(): String? = null
