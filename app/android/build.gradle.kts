// Android application shell. Almost empty on purpose: all UI and logic live in shared KMP modules.
// AGP 9 compiles Kotlin itself (built-in Kotlin), so no separate kotlin-android plugin is applied.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.kft.gcs.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // Not "com.kft.gcs": the existing KFT GCS (v1.3.x) owns that id on field tablets, and this app installs beside it.
        applicationId = "com.kft.survey"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        // com.kft.survey.dev: a dev build installs next to the release build instead of replacing it.
        debug { applicationIdSuffix = ".dev" }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(project(":app:shared"))
    implementation(libs.androidx.activity.compose)
}
