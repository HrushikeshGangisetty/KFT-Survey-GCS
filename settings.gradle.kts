pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle download the JDK named in gradle/gradle-daemon-jvm.properties (JDK 25) automatically.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // usb-serial-for-android is published only on JitPack. Limited to its group, so no other dependency can
        // ever be resolved from JitPack by accident.
        maven("https://jitpack.io") { content { includeGroup("com.github.mik3y") } }
    }
}

rootProject.name = "kft-gcs"

// ---- core: no UI. core/geo and core/planning are pure Kotlin (no I/O). ----
include(":core:geo")
include(":core:planning")
include(":core:mission")
include(":core:mavlink")
include(":core:vehicle")
include(":core:terrain")
include(":core:geo-io")

// ---- ui: shared UI building blocks ----
include(":ui:map")

// ---- features: one screen family each, never depend on each other ----
include(":feature:connections")
include(":feature:fly")
include(":feature:plan")
include(":feature:params")
include(":feature:settings")

// ---- apps ----
include(":app:shared")   // KMP: App() root composable, DI graph, navigation
include(":app:android")  // Android application shell
include(":app:desktop")  // Desktop JVM shell

// ---- spikes: throwaway experiments, deleted once their ADR is written ----
include(":spikes:map-spike")          // KMP: shared spike UI + desktop entry point (docs/decisions/ADR-001)
include(":spikes:map-spike-android")  // Android application shell for the spike (AGP 9: apps can't be KMP modules)
