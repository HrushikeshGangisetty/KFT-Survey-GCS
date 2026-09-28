// Transports (UDP/TCP/serial/BT behind MavTransport), framing via mavlink-kotlin, connection manager,
// and MavTxGateway: the ONLY class allowed to write bytes to a transport.
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    id("kft.kmp.library")
    alias(libs.plugins.kotlin.serialization) // LinkConfig is saved to disk as part of a connection profile
}

kotlin {
    // Socket transports use java.net, which exists on both Android and desktop. A shared "jvmCommon"
    // source set lets us write them once instead of copying the same file into androidMain and jvmMain.
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
            api(libs.mavlink.api)
            api(libs.mavlink.definitions)
            implementation(libs.mavlink.serialization)
            implementation(libs.mavlink.connection.core)
            implementation(libs.mavlink.adapter.coroutines)
            implementation(libs.okio)
            implementation(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
        }
        jvmMain.dependencies {
            implementation(libs.jserialcomm)
        }
        androidMain.dependencies {
            implementation(libs.usb.serial.android)
        }
    }
}

// Records raw Skydroid T12 serial traffic for the replay test: gradlew :core:mavlink:t12Capture --args="<file> [seconds]".
// See tools/t12/capture/README.md.
tasks.register<JavaExec>("t12Capture") {
    group = "kft"
    description = "Records the Skydroid T12's poll answers to a capture file (hardware bench)."
    val jvmMain = kotlin.jvm().compilations.getByName("main")
    classpath = files(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles)
    mainClass.set("com.kft.gcs.core.mavlink.T12CaptureKt")
    workingDir = rootDir
}
