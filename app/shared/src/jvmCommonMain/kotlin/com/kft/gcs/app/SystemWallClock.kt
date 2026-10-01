package com.kft.gcs.app

import com.kft.gcs.core.vehicle.di.WallClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The device's own clock, in its own time zone. */
object SystemWallClock : WallClock {
    override fun nowMs() = System.currentTimeMillis()

    // A new formatter each call: SimpleDateFormat isn't thread-safe, and this is called a few times a minute at most.
    override fun timeOfDay(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
}
