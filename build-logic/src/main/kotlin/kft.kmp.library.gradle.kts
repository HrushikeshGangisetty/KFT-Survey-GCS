// Convention plugin for every Kotlin Multiplatform library module.
// Targets: Android (library) + desktop JVM. Shared code goes in commonMain; tests in commonTest run on the JVM.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        namespace = kftNamespace
        compileSdk = libs.version("android-compileSdk").toInt()
        minSdk = libs.version("android-minSdk").toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        // Also run commonTest on the Android host JVM, so androidMain `actual`s are exercised.
        withHostTest {}
    }

    // "jvm" is the desktop target. Source sets: jvmMain / jvmTest.
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    compilerOptions {
        // expect/actual *classes* are still marked beta; we use them for platform transports.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.lib("kotlinx-coroutines-test"))
            implementation(libs.lib("turbine"))
        }
    }
}
