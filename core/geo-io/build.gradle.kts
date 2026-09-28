// Import/export parsing: camera lists (JSON), QGC .plan and Mission Planner .waypoints, ArduPilot parameter metadata
// (apm.pdef.json / .xml). Text in, values out; the app decides where the text comes from (files, bundled strings, web).
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    id("kft.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // The parameter metadata XML is read with the platform's SAX parser (javax.xml.parsers), which Android and the
    // desktop JVM both have, so it's written once in "jvmCommon" (as app:shared does for files) instead of twice.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmCommon") {
                withJvm()
                withCompilations { it.target.platformType == KotlinPlatformType.androidJvm }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:geo"))
            api(project(":core:planning"))
            // Mission files are lists of MissionItem: the plain model module, not the MAVLink one.
            api(project(":core:mission"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
