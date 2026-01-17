plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    // This is the essential plugin for Kotlin 2.0+
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false
}