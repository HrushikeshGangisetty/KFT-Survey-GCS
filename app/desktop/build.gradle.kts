// Desktop (JVM) application shell. Runs on JDK 25 (required by maplibre-compose's native desktop renderer).
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// JVM 25 bytecode: the maplibre-compose desktop runtime is published as "JVM 25 or newer" only, and Gradle refuses
// to put it on a JVM 21 app's classpath. The desktop app runs on JDK 25 anyway (CLAUDE.md §6).
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_25) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
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

// Satellite basemap: the Esri key comes from local.properties (esri.apiKey=) or ESRI_API_KEY and is handed to
// `run` as an environment variable only. It is never baked into the packaged app or committed (ADR-001 F7).
val esriKey: String? = rootProject.file("local.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } }.getProperty("esri.apiKey") }
    ?: providers.environmentVariable("ESRI_API_KEY").orNull
tasks.withType<JavaExec>().configureEach {
    if (!esriKey.isNullOrBlank()) environment("ESRI_API_KEY", esriKey)
}
