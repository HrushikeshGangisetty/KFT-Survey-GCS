// THROWAWAY week-1 map spike (checks M1–M8, docs/implementation/00-maps-decision.md §4). Delete after ADR-001.
import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins { id("kft.kmp.compose") }

kotlin {
    // AGP 9's KMP library plugin leaves Android resources off by default, which silently drops our
    // composeResources from the APK (Res.getUri then crashes at startup). Found on the emulator in this spike.
    android { androidResources { enable = true } }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.maplibre.compose)
            // Compose Resources gives both platforms a URI for the bundled offline .mbtiles (M6).
            implementation(libs.compose.components.resources)
        }
        androidMain.dependencies {
            runtimeOnly(libs.maplibre.compose.runtime.opengl.android)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            runtimeOnly(libs.maplibre.compose.runtime.vulkan.windows.x64)
        }
    }
}

compose.resources {
    packageOfResClass = "com.kft.gcs.spikes.mapspike.res"
}

compose.desktop {
    application {
        mainClass = "com.kft.gcs.spikes.mapspike.MainKt"
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "KFT-MapSpike"
            packageVersion = "0.0.1"
        }
    }
}

// M4: the Esri key comes from local.properties (esri.apiKey=) or ESRI_API_KEY and is handed to `run` as an
// environment variable only. It is never baked into the packaged app or committed.
val esriKey: String? = rootProject.file("local.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } }.getProperty("esri.apiKey") }
    ?: providers.environmentVariable("ESRI_API_KEY").orNull
tasks.withType<JavaExec>().configureEach {
    if (!esriKey.isNullOrBlank()) environment("ESRI_API_KEY", esriKey)
}
