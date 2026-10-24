// Signing-key vault (PRD "Key management"): PIN-encrypted key store, SigningKeyring over it, timestamp file, bundle import.
// Argon2id (BouncyCastle lightweight API) and AES-GCM live in jvmCommonMain; the vault logic itself is common.
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    id("kft.kmp.library")
    alias(libs.plugins.kotlin.serialization) // vault body and timestamp file are JSON
}

kotlin {
    // javax.crypto and java.io exist on Android and desktop alike, so the crypto and file store are written once in
    // "jvmCommon" (as core:mavlink does for sockets).
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
            api(project(":core:mavlink")) // SigningKeyring
            implementation(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
        }
        // bcprov is used for Argon2BytesGenerator only; the lightweight API registers no JCA provider (safe on Android).
        getByName("jvmCommonMain").dependencies { implementation(libs.bouncycastle.bcprov) }
    }
}
