plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.gradle.plugin.kotlin)
    compileOnly(libs.gradle.plugin.compose.compiler)
    compileOnly(libs.gradle.plugin.compose)
    compileOnly(libs.gradle.plugin.android)
}
