package com.kft.gcs.spikes.mapspike

actual fun usedHeapMb(): Long = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / 1_048_576 }
