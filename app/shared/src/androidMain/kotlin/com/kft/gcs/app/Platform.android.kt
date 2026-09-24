package com.kft.gcs.app

import android.os.Build

actual object Platform {
    actual val name: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
}
