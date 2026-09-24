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
    }
}

rootProject.name = "kft-gcs"

// ---- core: no UI. core/geo and core/planning are pure Kotlin (no I/O). ----
include(":core:geo")
include(":core:planning")
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
include(":feature:settings")

// ---- apps ----
include(":app:shared")   // KMP: App() root composable, DI graph, navigation
include(":app:android")  // Android application shell
include(":app:desktop")  // Desktop JVM shell
