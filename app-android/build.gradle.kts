import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.application)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val androidMain by getting {
            dependencies {
                implementation(project(":core"))
                implementation(project(":transport-xmpp"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.lifecycle.viewmodel.compose)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

android {
    namespace = "dev.lumen.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.lumen.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // LOCKED hardening — see docs/plan.md locked decision #10.
    // allowBackup=false: Android auto-backup would exfiltrate the local DB
    // (and keys) to Google Drive in plaintext. This is how E2EE dies.
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            manifestPlaceholders["enableBackup"] = "false"
        }
        getByName("debug") {
            manifestPlaceholders["enableBackup"] = "false"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
