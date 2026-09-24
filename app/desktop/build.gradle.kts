// Desktop (JVM) application shell. Runs on JDK 25 (required by maplibre-compose's native desktop renderer).
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(project(":app:shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing) // provides Dispatchers.Main on desktop
}

compose.desktop {
    application {
        mainClass = "com.kft.gcs.desktop.MainKt"
        // Needed by native libraries loaded through the Java FFM API (maplibre-compose desktop runtime).
        jvmArgs += "--enable-native-access=ALL-UNNAMED"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "KFT-GCS"
            packageVersion = "0.1.0"
            vendor = "Kapil Future Tech"
            windows {
                menuGroup = "KFT"
                upgradeUuid = "4b1f7c1e-2d7a-4a53-9c1e-6a0f5e2b9d11"
            }
        }
    }
}
