// Shared app root: App() composable, theme, navigation host and the Koin DI graph. Used by both app shells.
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins { id("kft.kmp.compose") }

kotlin {
    // java.io exists on Android and desktop alike, so file storage is written once in "jvmCommon" (as core:mavlink
    // does for sockets) instead of being copied into androidMain and jvmMain.
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
            implementation(project(":feature:connections"))
            implementation(project(":feature:fly"))
            implementation(project(":feature:plan"))
            implementation(project(":feature:settings"))
            implementation(project(":core:mavlink")) // for mavlinkModule and the IoDispatcher qualifier
            implementation(project(":core:vehicle"))
            implementation(libs.jb.navigation.compose)
            implementation(project(":ui:map")) // App() owns the one MapView (ADR-001 F10); DesktopApp gives it a GPU context
            implementation(libs.jb.lifecycle.viewmodel.compose)
            implementation(libs.jb.lifecycle.runtime.compose)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose) // the document picker for plan files
        }
    }
}
